package org.freenetproject.mobile.ui.main.activity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.freenetproject.mobile.net.Tor;

import javax.net.ssl.HttpsURLConnection;

/**
 * Asks Freenet's invite server for a code to the public River room, as the form on
 * freenet.org/quickstart does. The server only accepts browser requests from that page,
 * so a page opened anywhere else gets "Failed to fetch"; the app is not bound by that rule.
 */
final class RiverInvite {
    private static final String API = "https://gkapi.freenet.org";
    private static final byte[] POW_PREFIX =
            "freenet-river-invite-pow-v1".getBytes(StandardCharsets.UTF_8);
    private static final int CHALLENGE_BYTES = 16;
    private static final int MAX_DIFFICULTY_BITS = 40;
    private static final int TIMEOUT_MILLIS = 20000;
    private static final int MAX_ANSWER_BYTES = 64 * 1024;

    private RiverInvite() {
    }

    /** Runs on a background thread: the anti-spam proof can take a while. */
    static String request() throws IOException, JSONException, NoSuchAlgorithmException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Answer challenge = send("/invite-challenge", null);
            if (challenge.status == HttpsURLConnection.HTTP_NOT_FOUND) {
                return inviteCode(send("/create-invite", "{}"));
            }
            JSONObject fields = parsed(challenge);
            JSONObject body = new JSONObject()
                    .put("challenge", fields.get("challenge"))
                    .put("issued_at", fields.get("issued_at"))
                    .put("difficulty", fields.get("difficulty"))
                    .put("signature", fields.get("signature"))
                    .put("nonce", solve(fields.getString("challenge"), fields.getInt("difficulty")));
            Answer invite = send("/create-invite", body.toString());
            // An expired or already used challenge: the page asks for a new one once, too.
            if (attempt == 0 && (invite.status == 400 || invite.status == 409 || invite.status == 410)) {
                continue;
            }
            return inviteCode(invite);
        }
        throw new IOException("Invite verification could not be completed");
    }

    private static String inviteCode(Answer answer) throws IOException, JSONException {
        return parsed(answer).getString("invite_code");
    }

    private static JSONObject parsed(Answer answer) throws IOException, JSONException {
        if (answer.status < 200 || answer.status >= 300) {
            String reason = "Invite server error (" + answer.status + ")";
            try {
                reason = new JSONObject(answer.text).optString("error", reason);
            } catch (JSONException notJson) {
                // the status code says enough
            }
            throw new IOException(reason);
        }
        return new JSONObject(answer.text);
    }

    private static long solve(String challengeHex, int difficulty)
            throws IOException, NoSuchAlgorithmException {
        if (challengeHex.length() != CHALLENGE_BYTES * 2 || difficulty < 0
                || difficulty > MAX_DIFFICULTY_BITS) {
            throw new IOException("The invite server returned an invalid challenge");
        }
        ByteBuffer input = ByteBuffer.allocate(POW_PREFIX.length + CHALLENGE_BYTES + Long.BYTES);
        input.put(POW_PREFIX);
        for (int i = 0; i < CHALLENGE_BYTES; i++) {
            int high = Character.digit(challengeHex.charAt(i * 2), 16);
            int low = Character.digit(challengeHex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IOException("The invite server returned an invalid challenge");
            }
            input.put((byte) (high * 16 + low));
        }
        int nonceAt = input.position();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (long nonce = 0; nonce < Long.MAX_VALUE; nonce++) {
            input.putLong(nonceAt, nonce); // big endian, as ByteBuffer is by default
            if (hasLeadingZeroBits(sha256.digest(input.array()), difficulty)) {
                return nonce;
            }
        }
        throw new IOException("Invite verification took too long");
    }

    private static boolean hasLeadingZeroBits(byte[] hash, int bits) {
        int whole = bits / 8;
        for (int i = 0; i < whole; i++) {
            if (hash[i] != 0) {
                return false;
            }
        }
        int rest = bits % 8;
        return rest == 0 || (hash[whole] & 0xFF) >> (8 - rest) == 0;
    }

    private static final class Answer {
        int status;
        String text;
    }

    private static Answer send(String path, String json) throws IOException {
        HttpsURLConnection connection = Tor.open(API + path);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        try {
            if (json != null) {
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            Answer answer = new Answer();
            answer.status = connection.getResponseCode();
            InputStream stream = answer.status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        bytes.write(buffer, 0, read);
                        if (bytes.size() > MAX_ANSWER_BYTES) {
                            throw new IOException("Answer too long");
                        }
                    }
                }
            }
            answer.text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            return answer;
        } finally {
            connection.disconnect();
        }
    }
}

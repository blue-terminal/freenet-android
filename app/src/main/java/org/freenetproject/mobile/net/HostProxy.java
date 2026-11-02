package org.freenetproject.mobile.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.services.node.Manager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A minimal HTTP proxy that sits in front of the Freenet node and requires a password.
 *
 * The PC browser connects to port {@link #PORT} with HTTP Basic Auth. After the
 * first valid request the proxy becomes a transparent TCP tunnel so WebSocket upgrades
 * and chunked streaming from the node all pass through unchanged.
 */
public final class HostProxy implements Runnable {

    public static final int PORT = 7510;
    private static final String PREF_PASSWORD = "proxy_password";
    private static final String HISTORY_FILE = "proxy-password-history.txt";
    private static final String USER = "freenet";
    private static final String TAG = "FreenetProxy";
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final ServerSocket server;
    private final String password;

    private HostProxy(ServerSocket server, String password) {
        this.server = server;
        this.password = password;
    }

    /**
     * Returns the stored password, or generates and stores a new one if none exists.
     */
    public static String getOrCreatePassword(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String existing = prefs.getString(PREF_PASSWORD, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        return newPassword(context);
    }

    /** Replaces the stored password with a freshly generated one and returns it. */
    public static String newPassword(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String old = prefs.getString(PREF_PASSWORD, null);
        if (old != null && !old.isEmpty()) {
            recordOldPassword(context, old);
        }
        String password = generate();
        prefs.edit().putString(PREF_PASSWORD, password).apply();
        return password;
    }

    private static void recordOldPassword(Context context, String old) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())
                + "  " + old + "\n";
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), HISTORY_FILE), true)) {
            writer.write(line);
        } catch (IOException ignored) {
        }
    }

    /** The history file, or null when there is none. */
    public static File historyFile(Context context) {
        File f = new File(context.getFilesDir(), HISTORY_FILE);
        return f.isFile() ? f : null;
    }

    public static String readHistory(Context context) {
        File f = new File(context.getFilesDir(), HISTORY_FILE);
        if (!f.isFile()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static void clearHistory(Context context) {
        new File(context.getFilesDir(), HISTORY_FILE).delete();
    }

    private static String generate() {
        // 8 characters, unambiguous alphabet (no 0/O, 1/I/l)
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Starts the proxy and returns the running instance, or null if the port cannot be opened.
     */
    public static HostProxy start(Context context) {
        String password = getOrCreatePassword(context);
        try {
            ServerSocket server = new ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"));
            server.setReuseAddress(true);
            HostProxy proxy = new HostProxy(server, password);
            new Thread(proxy, "freenet-proxy").start();
            Log.i(TAG, "Proxy started on port " + PORT);
            return proxy;
        } catch (IOException e) {
            Log.e(TAG, "Cannot start proxy", e);
            return null;
        }
    }

    public void stop() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
        Log.i(TAG, "Proxy stopped");
    }

    public String getPassword() {
        return password;
    }

    @Override
    public void run() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                new Thread(() -> handle(client), "freenet-proxy-conn").start();
            } catch (IOException e) {
                if (!server.isClosed()) {
                    Log.w(TAG, "Accept error", e);
                }
                break;
            }
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(30_000);
            InputStream cin = c.getInputStream();
            OutputStream cout = c.getOutputStream();

            // Read the HTTP request headers (up to the blank line).
            byte[] headerBytes = readHeaders(cin);
            if (headerBytes == null) {
                return;
            }
            String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);

            if (!authorized(headers)) {
                cout.write((
                        "HTTP/1.1 401 Unauthorized\r\n" +
                        "WWW-Authenticate: Basic realm=\"Freenet\"\r\n" +
                        "Content-Length: 12\r\n" +
                        "Connection: close\r\n\r\n" +
                        "Unauthorized"
                ).getBytes(StandardCharsets.ISO_8859_1));
                return;
            }

            // Auth OK — open the backend and forward the headers we already read.
            try (Socket backend = new Socket("127.0.0.1", Manager.WEB_PORT)) {
                backend.setSoTimeout(120_000);
                OutputStream bout = backend.getOutputStream();
                bout.write(headerBytes);

                // Pipe both directions concurrently; the connection ends when either side closes.
                Thread forward = new Thread(
                        () -> pipe(cin, bout), "freenet-proxy-fwd");
                forward.setDaemon(true);
                forward.start();
                pipe(backend.getInputStream(), cout);
                forward.join(5_000);
            }
        } catch (Exception e) {
            Log.d(TAG, "Connection closed: " + e.getMessage());
        }
    }

    /** Reads bytes until the HTTP header terminator {@code \r\n\r\n}. */
    private static byte[] readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(2048);
        int b0 = 0, b1 = 0, b2 = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b0 == '\r' && b1 == '\n' && b2 == '\r' && b == '\n') {
                return buf.toByteArray();
            }
            if (buf.size() > MAX_HEADER_BYTES) {
                return null;
            }
            b0 = b1;
            b1 = b2;
            b2 = b;
        }
        return null;
    }

    private boolean authorized(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("authorization: basic ")) {
                String encoded = line.substring("authorization: basic ".length()).trim();
                try {
                    String decoded = new String(
                            Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                    int colon = decoded.indexOf(':');
                    if (colon < 0) {
                        return false;
                    }
                    String u = decoded.substring(0, colon);
                    String p = decoded.substring(colon + 1);
                    return USER.equals(u) && password.equals(p);
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return false;
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }
}

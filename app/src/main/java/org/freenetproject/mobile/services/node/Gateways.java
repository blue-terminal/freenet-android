package org.freenetproject.mobile.services.node;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.freenetproject.mobile.net.Tor;

import javax.net.ssl.HttpsURLConnection;

/**
 * Prepares the list of bootstrap gateways for the node.
 *
 * The node is a Linux program and looks for DNS servers in /etc/resolv.conf, which Android
 * does not have. So the app fetches the list the node would fetch, turns names into addresses
 * and leaves a file the node reads with --skip-load-from-network.
 */
final class Gateways {
    private static final String TAG = "Freenet";
    private static final String BASE_URL = "https://freenet.org/";
    private static final int TIMEOUT_MILLIS = 10000;
    private static final int MAX_BYTES = 1024 * 1024;
    // What comes from the network ends up in a TOML file and in a path: only plain values pass.
    private static final Pattern ENTRY = Pattern.compile(
            "public_key\\s*=\\s*\"(keys/[A-Za-z0-9._-]+\\.pem)\"\\s*"
                    + "\\[gateways\\.address\\]\\s*"
                    + "hostname\\s*=\\s*\"([A-Za-z0-9.-]+):([0-9]{1,5})\"");

    private Gateways() {
    }

    /** Keeps the last good list if the network is not there: the node can use it. */
    static void prepare(File configDir, File secretsDir) throws IOException {
        File target = new File(configDir, "gateways.toml");
        try {
            String list = fetch(BASE_URL + "keys/gateways.toml");
            String resolved = resolve(list, secretsDir);
            Files.createDirectories(configDir.toPath());
            File temporary = new File(configDir, "gateways.toml.tmp");
            Files.write(temporary.toPath(), resolved.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (!target.isFile()) {
                throw e;
            }
            Log.w(TAG, "Using the saved gateway list", e);
        }
    }

    private static String resolve(String list, File secretsDir) throws IOException {
        StringBuilder out = new StringBuilder();
        Matcher entry = ENTRY.matcher(list.replaceAll("(?m)^\\s*#.*$", ""));
        Files.createDirectories(secretsDir.toPath());
        while (entry.find()) {
            String keyPath = entry.group(1);
            String host = entry.group(2);
            try {
                File key = new File(secretsDir, new File(keyPath).getName());
                Files.write(key.toPath(), fetch(BASE_URL + keyPath).getBytes(StandardCharsets.UTF_8));
                out.append("[[gateways]]\npublic_key = \"").append(key.getAbsolutePath())
                        .append("\"\n[gateways.address]\nhostname = \"").append(address(host))
                        .append(':').append(entry.group(3)).append("\"\n\n");
            } catch (IOException e) {
                Log.w(TAG, "Skipping gateway " + host, e); // the others may still work
            }
        }
        if (out.length() == 0) {
            throw new IOException("No usable gateway found");
        }
        return out.toString();
    }

    private static String address(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress candidate : all) {
            if (candidate instanceof Inet4Address) {
                return candidate.getHostAddress();
            }
        }
        return "[" + all[0].getHostAddress() + "]";
    }

    private static String fetch(String url) throws IOException {
        HttpsURLConnection connection = Tor.open(url);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false); // the addresses must come from this host
        try (InputStream in = connection.getInputStream()) {
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode() + " from " + url);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_BYTES) {
                    throw new IOException("Answer too long from " + url);
                }
            }
            return bytes.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }
}

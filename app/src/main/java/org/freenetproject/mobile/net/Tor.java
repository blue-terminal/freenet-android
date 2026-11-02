package org.freenetproject.mobile.net;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.Proxy;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * The app's own internet requests (update check, Room chat invite, gateway list).
 * With Tor turned on they go through Orbot's SOCKS port on this phone instead of
 * straight out. The Freenet node itself is not affected: it speaks UDP, which Tor
 * does not carry.
 */
public final class Tor {
    public static final String KEY = "tor_mode";
    private static final String HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 9050;

    /** Read once at startup and on every change, so the call sites need no context. */
    private static volatile boolean enabled;

    private Tor() {
    }

    public static void load(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        enabled = preferences.getBoolean(KEY, false);
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Orbot's package, so the settings screen can open it when it is not running. */
    public static final String ORBOT = "org.torproject.android";

    /**
     * True when something answers on the SOCKS port, which in practice means Orbot is
     * running. Without it every request through Tor would fail, so the setting checks
     * this instead of silently turning on.
     */
    public static boolean available() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, SOCKS_PORT), 1500);
            return true;
        } catch (IOException notRunning) {
            return false;
        }
    }

    public static HttpsURLConnection open(String url) throws IOException {
        URL target = new URL(url);
        if (!enabled) {
            return (HttpsURLConnection) target.openConnection();
        }
        // Unresolved on purpose: the name is handed to the proxy instead of being looked up here.
        Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(HOST, SOCKS_PORT));
        return (HttpsURLConnection) target.openConnection(proxy);
    }
}

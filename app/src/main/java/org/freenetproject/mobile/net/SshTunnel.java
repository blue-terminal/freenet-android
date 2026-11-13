package org.freenetproject.mobile.net;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import org.freenetproject.mobile.Security;

import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.io.PathUtils;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.ForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.freenetproject.mobile.services.node.Manager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access from a PC on the same Wi-Fi, protected by SSH: the PC opens a tunnel with
 * {@code ssh -N -L 7509:127.0.0.1:7509 -p 2222 freenet@PHONE} and then uses the node as if it
 * ran on the PC itself. The node keeps listening only on the phone; the server here gives no
 * shell, no commands and no files, and forwards only to the node's own port.
 */
public final class SshTunnel {
    public static final int PORT = 2222;
    public static final String USER = "freenet";
    private static final String TAG = "FreenetSsh";

    private static final String CHANNEL = "ssh_alerts";
    // Wrong passwords per address: {count, time of the first}. A locked address gets no more tries.
    private static final Map<String, long[]> failures = new ConcurrentHashMap<>();

    private final SshServer server;

    private SshTunnel(SshServer server) {
        this.server = server;
    }

    public static SshTunnel start(Context context) {
        Context app = context.getApplicationContext();
        if (Security.extreme(app)) {
            Log.i(TAG, "SSH access is off at the extreme security level");
            return null;
        }
        String password = HostProxy.getOrCreatePassword(app);
        File home = new File(app.getFilesDir(), "ssh");
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        OsUtils.setAndroid(Boolean.TRUE);
        PathUtils.setUserHomeFolderResolver(home::toPath);
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("0.0.0.0");
        sshd.setPort(PORT);
        // The same host key every time, so the PC can tell it is still this phone.
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(new File(home, "host_key").toPath()));
        sshd.setPasswordAuthenticator((user, given, session) -> {
            String from = addressOf(session.getClientAddress());
            if (locked(app, from)) {
                return false;
            }
            boolean ok = USER.equals(user) && same(given, password);
            if (ok) {
                failures.remove(from);
            } else {
                failed(app, from);
            }
            return ok;
        });
        sshd.setPublickeyAuthenticator((user, key, session) -> false);
        sshd.setKeyboardInteractiveAuthenticator(null);
        sshd.setShellFactory(null);
        sshd.setCommandFactory(null);
        sshd.setSubsystemFactories(Collections.emptyList());
        sshd.setForwardingFilter(new ForwardingFilter() {
            @Override
            public boolean canForwardAgent(Session session, String requestType) {
                return false;
            }

            @Override
            public boolean canForwardX11(Session session, String requestType) {
                return false;
            }

            // The PC may not make the phone listen on anything.
            @Override
            public boolean canListen(SshdSocketAddress address, Session session) {
                return false;
            }

            // Only to the node on this phone: never to other machines of the network.
            @Override
            public boolean canConnect(Type type, SshdSocketAddress address, Session session) {
                String host = address.getHostName();
                boolean local = "127.0.0.1".equals(host) || "localhost".equals(host);
                return type == Type.Direct && local && address.getPort() == Manager.WEB_PORT;
            }
        });
        try {
            sshd.start();
            Log.i(TAG, "SSH access started on port " + PORT);
            return new SshTunnel(sshd);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "SSH access did not start", e);
            return null;
        }
    }

    private static String addressOf(SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            InetSocketAddress inet = (InetSocketAddress) address;
            return inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
        }
        return String.valueOf(address);
    }

    private static boolean locked(Context context, String from) {
        long[] entry = failures.get(from);
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry[1] > Security.sshLockoutMs(context)) {
            failures.remove(from);
            return false;
        }
        return entry[0] >= Security.sshMaxFailures(context);
    }

    private static void failed(Context context, String from) {
        long[] entry = failures.computeIfAbsent(from, k -> new long[]{0, System.currentTimeMillis()});
        entry[0]++;
        boolean nowLocked = entry[0] >= Security.sshMaxFailures(context);
        Log.w(TAG, "Wrong SSH password from " + from);
        // The phone owner sees every wrong try and where it came from on the local network.
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Avvisi di sicurezza SSH",
                NotificationManager.IMPORTANCE_HIGH));
        String text = nowLocked
                ? "Troppi tentativi da " + from + ": bloccato per " + Security.sshLockoutMs(context) / 60_000 + " minuti"
                : "Password sbagliata da " + from + " (" + entry[0] + " di " + Security.sshMaxFailures(context) + ")";
        manager.notify(from.hashCode(), new android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Tentativo di accesso SSH")
                .setContentText(text)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(text
                        + "\nSe non eri tu, spegni Accesso dal PC e cambia la password."))
                .setAutoCancel(true)
                .build());
    }

    private static boolean same(String given, String expected) {
        if (given == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    public void stop() {
        try {
            server.stop(true);
        } catch (IOException ignored) {
        }
    }
}

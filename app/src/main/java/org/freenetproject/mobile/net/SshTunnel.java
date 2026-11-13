package org.freenetproject.mobile.net;

import android.content.Context;
import android.util.Log;

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
import java.util.Collections;

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

    private final SshServer server;

    private SshTunnel(SshServer server) {
        this.server = server;
    }

    public static SshTunnel start(Context context) {
        Context app = context.getApplicationContext();
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
        sshd.setPasswordAuthenticator((user, given, session) -> USER.equals(user) && same(given, password));
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

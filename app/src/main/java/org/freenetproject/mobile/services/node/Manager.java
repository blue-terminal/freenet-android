package org.freenetproject.mobile.services.node;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.net.HostProxy;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Starts and stops the Freenet node, a native program shipped as libfreenet.so, and
 * exposes its state to the UI.
 */
public class Manager {
    public static final int WEB_PORT = 7509;
    public static final String WEB_URL = "http://127.0.0.1:" + WEB_PORT + "/";

    private static final String TAG = "Freenet";
    private static final int STARTUP_TIMEOUT_SECONDS = 180;
    private static final int STOP_TIMEOUT_SECONDS = 10;
    private static final int LOG_TAIL_BYTES = 4096;
    private static final int LOG_COPY_BYTES = 64 * 1024;
    private static final String MIN_CONNECTIONS_WHEN_LIMITED = "5";
    // Saving mode: 250 KB/s, few connections, 2 GB of cache and few threads keep the phone cool.
    private static final String SAVING_BANDWIDTH = "250000";
    private static final String SAVING_CONNECTIONS = "10";
    private static final String SAVING_DISK = "2147483648";
    private static final String SAVING_THREADS = "4";
    private static final int BLOCKED_BY_ANDROID_CODE = 128 + 31; // SIGSYS

    private static Manager instance = null;

    public enum Status {
        STARTING_UP,
        STARTED,
        STOPPING,
        STOPPED,
        ERROR,
        PAUSED
    }

    /** Why the node should not run right now. It resumes when none of these is left. */
    public enum PauseReason {
        NETWORK,
        BATTERY
    }

    private final Map<PauseReason, Boolean> pauses = new EnumMap<>(PauseReason.class);
    private final MutableLiveData<Status> statusData = new MutableLiveData<>(Status.STOPPED);
    private volatile Status status = Status.STOPPED;
    private volatile long startedAtMs = 0;
    private Process process = null;
    private HostProxy proxy = null;
    private File logFile = null;
    private volatile String lastProblem = "";

    private Manager() {
    }

    public static synchronized Manager getInstance() {
        if (instance == null) {
            instance = new Manager();
        }
        return instance;
    }

    public LiveData<Status> getStatus() {
        return statusData;
    }

    private void setStatus(Status value) {
        status = value;
        statusData.postValue(value);
    }

    /** Starts the foreground service, which keeps the node alive in the background. */
    public void startService(Context context) {
        Intent intent = new Intent(context, Service.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public void stopService(Context context) {
        setStatus(Status.STOPPING);
        context.stopService(new Intent(context, Service.class));
    }

    /** Runs on a background thread: it can wait for the network and for the web port. */
    public void startNode(Context context) {
        synchronized (this) {
            if (process != null && process.isAlive()) {
                return;
            }
            if (!pauses.isEmpty()) {
                setStatus(Status.PAUSED);
                return;
            }
            setStatus(Status.STARTING_UP);
            lastProblem = "";
        }
        // Outside the lock: a slow network must not block stopping or pausing the node.
        try {
            Gateways.prepare(new File(configHome(context), "freenet"),
                    new File(context.getFilesDir(), "home/.local/share/freenet/secrets"));
        } catch (IOException e) {
            synchronized (this) {
                Log.e(TAG, "Cannot prepare the gateways", e);
                lastProblem = String.valueOf(e.getMessage());
                if (status == Status.STARTING_UP) {
                    setStatus(Status.ERROR);
                }
            }
            return;
        }
        Process started;
        synchronized (this) {
            if (status != Status.STARTING_UP) {
                return; // stopped or paused while waiting for the network
            }
            try {
                logFile = new File(context.getFilesDir(), "freenet.log");
                process = buildProcess(context).start();
            } catch (IOException e) {
                Log.e(TAG, "Cannot start the node", e);
                lastProblem = String.valueOf(e.getMessage());
                setStatus(Status.ERROR);
                return;
            }
            started = process;
        }
        Context appContext = context.getApplicationContext();
        new Thread(() -> waitForPort(started, appContext), "freenet-wait-port").start();
        new Thread(() -> watch(started), "freenet-watch").start();
    }

    /** The developer setting when it is set, otherwise the fallback saving mode asks for. */
    private static String setting(SharedPreferences preferences, String key, String fallback) {
        String chosen = preferences.getString(key, "");
        return chosen.isEmpty() ? fallback : chosen;
    }

    private ProcessBuilder buildProcess(Context context) throws IOException {
        String program = context.getApplicationInfo().nativeLibraryDir + "/libfreenet.so";
        // The APK ships one version: the node must not try to replace itself.
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        List<String> command = new ArrayList<>(Arrays.asList(
                program, "network", "--disable-auto-update", "--skip-load-from-network"));
        // Saving mode sets gentle limits; a developer setting always wins over it.
        boolean saving = preferences.getBoolean("saving_mode", false);
        String bandwidth = setting(preferences, "dev_bandwidth", saving ? SAVING_BANDWIDTH : "");
        if (!bandwidth.isEmpty()) {
            command.add("--total-bandwidth-limit");
            command.add(bandwidth);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        String connections = setting(preferences, "dev_connections", saving ? SAVING_CONNECTIONS : "");
        if (!connections.isEmpty()) {
            env.put("MAX_NUMBER_OF_CONNECTIONS", connections);
            env.put("MIN_NUMBER_OF_CONNECTIONS", MIN_CONNECTIONS_WHEN_LIMITED);
        }
        applyEnvironment(context, env);
        // Upstream sends telemetry unless told otherwise.
        env.put("FREENET_TELEMETRY_ENABLED", "false");
        // Only the phone itself can reach the node's web interface and its keys,
        // unless host mode was turned on: then the local network can reach it too.
        env.put("FREENET_WS_API_ADDRESS",
                preferences.getBoolean("host_mode", false) ? "0.0.0.0" : "127.0.0.1");
        String disk = setting(preferences, "dev_disk", saving ? SAVING_DISK : "");
        if (!disk.isEmpty()) {
            env.put("MAX_HOSTING_DISK", disk);
        }
        String threads = setting(preferences, "dev_threads", saving ? SAVING_THREADS : "");
        if (!threads.isEmpty()) {
            env.put("MAX_BLOCKING_THREADS", threads);
        }
        env.put("LOG_LEVEL", preferences.getString("dev_log", "warn"));
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile);
        builder.directory(new File(env.get("HOME")));
        return builder;
    }

    /**
     * Every private address of this phone, Wi-Fi first, as "wlan0: 192.168.1.5". The Wi-Fi
     * one is the only one a computer on the same network can reach: the others belong to
     * mobile data, to a VPN or to the hotspot, and a computer cannot use them. Only private
     * addresses are listed, never a public one, because the node has no password.
     */
    public static List<String> localAddresses() {
        List<String> wifi = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (network.isLoopback() || !network.isUp()) {
                    continue;
                }
                String name = String.valueOf(network.getName());
                // Mobile data and tunnels carry an address a computer can never reach.
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("ccmni") || name.startsWith("clat")) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        String found = name + ": " + address.getHostAddress();
                        if (name.startsWith("wlan")) {
                            wifi.add(found);
                        } else {
                            others.add(found);
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
            // No network: the caller shows the USB instructions alone.
        }
        wifi.addAll(others);
        return wifi;
    }

    /** The most likely address, the Wi-Fi one, or null when the phone has no local network. */
    public static String localAddress() {
        List<String> found = localAddresses();
        return found.isEmpty() ? null : found.get(0).substring(found.get(0).indexOf(": ") + 2);
    }

    public static File configHome(Context context) {
        return new File(context.getFilesDir(), "home/.config");
    }

    /** Freenet's programs expect a Linux home: everything stays in the app's private folder. */
    public static void applyEnvironment(Context context, Map<String, String> env) throws IOException {
        File home = new File(context.getFilesDir(), "home");
        File tmp = new File(context.getCacheDir(), "tmp");
        if (!home.isDirectory() && !home.mkdirs() || !tmp.isDirectory() && !tmp.mkdirs()) {
            throw new IOException("Cannot create the node folders");
        }
        env.put("HOME", home.getAbsolutePath());
        env.put("XDG_CONFIG_HOME", configHome(context).getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
        env.put("XDG_CACHE_HOME", new File(home, ".cache").getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
    }

    private void waitForPort(Process started, Context context) {
        for (int i = 0; i < STARTUP_TIMEOUT_SECONDS && started.isAlive(); i++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", WEB_PORT), 1000);
                synchronized (this) {
                    if (process == started && status == Status.STARTING_UP) {
                        startedAtMs = System.currentTimeMillis();
                        setStatus(Status.STARTED);
                        SharedPreferences prefs =
                                PreferenceManager.getDefaultSharedPreferences(context);
                        if (prefs.getBoolean("host_mode", false) && proxy == null) {
                            proxy = HostProxy.start(context);
                        }
                    }
                }
                return;
            } catch (IOException notReady) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        synchronized (this) {
            if (process == started && status == Status.STARTING_UP) {
                Log.e(TAG, "The web port did not open in time");
                started.destroy();
            }
        }
    }

    private void watch(Process started) {
        int code = -1;
        try {
            code = started.waitFor();
        } catch (InterruptedException ignored) {
        }
        synchronized (this) {
            if (process != started) {
                return; // already replaced or stopped on purpose
            }
            process = null;
            stopProxy();
            Log.e(TAG, "The node stopped by itself with code " + code);
            lastProblem = "Exit code " + code + (code == BLOCKED_BY_ANDROID_CODE
                    ? ": Android blocked a system call the node needs" : "");
            setStatus(Status.ERROR);
        }
    }

    private void stopProxy() {
        HostProxy p = proxy;
        proxy = null;
        if (p != null) {
            p.stop();
        }
    }

    /** Called when the host_mode preference changes while the node is running. */
    public synchronized void onHostModeChanged(Context context, boolean enabled) {
        if (enabled && status == Status.STARTED && proxy == null) {
            proxy = HostProxy.start(context.getApplicationContext());
        } else if (!enabled) {
            stopProxy();
        }
    }

    /** The password the proxy uses, or null if the proxy is not running. */
    public String proxyPassword(Context context) {
        HostProxy p = proxy;
        if (p != null) {
            return p.getPassword();
        }
        return null;
    }

    public synchronized void stopNode(boolean paused) {
        stopProxy();
        Process running = process;
        process = null;
        if (running != null && running.isAlive()) {
            setStatus(Status.STOPPING);
            running.destroy();
            try {
                if (!running.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    running.destroyForcibly();
                }
            } catch (InterruptedException e) {
                running.destroyForcibly();
            }
        }
        setStatus(paused ? Status.PAUSED : Status.STOPPED);
    }

    public synchronized void pause(PauseReason reason) {
        pauses.put(reason, true);
        if (status != Status.STOPPED) {
            stopNode(true);
        }
    }

    public synchronized void resume(Context context, PauseReason reason) {
        pauses.remove(reason);
        if (pauses.isEmpty() && status == Status.PAUSED) {
            startNode(context);
        }
    }

    public synchronized void clearPauses() {
        pauses.clear();
    }

    /** The end of the node's log, to copy into a bug report. */
    public static String logTail(Context context) {
        File file = new File(context.getFilesDir(), "freenet.log");
        try (RandomAccessFile tail = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, tail.length() - LOG_COPY_BYTES);
            byte[] bytes = new byte[(int) (tail.length() - start)];
            tail.seek(start);
            tail.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The last line of the node's log, to explain an error. */
    public String lastLogLine() {
        File file = logFile;
        if (file == null || !file.isFile() || file.length() == 0) {
            return lastProblem;
        }
        try (RandomAccessFile tail = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, tail.length() - LOG_TAIL_BYTES);
            byte[] bytes = new byte[(int) (tail.length() - start)];
            tail.seek(start);
            tail.readFully(bytes);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).trim().split("\n");
            return lines[lines.length - 1].trim();
        } catch (IOException e) {
            Log.w(TAG, "Cannot read the log", e);
        }
        return lastProblem;
    }

    /** Milliseconds since the node started, or 0 if it is not running. */
    public long uptimeMs() {
        return startedAtMs == 0 || status != Status.STARTED ? 0
                : System.currentTimeMillis() - startedAtMs;
    }

    public Boolean isStopped() {
        return status == Status.STOPPED;
    }

    public Boolean isPaused() {
        return status == Status.PAUSED;
    }

    public Boolean isRunning() {
        return status == Status.STARTED;
    }

    public Boolean hasError() {
        return status == Status.ERROR;
    }

    public Boolean isTransitioning() {
        return status == Status.STARTING_UP || status == Status.STOPPING;
    }
}

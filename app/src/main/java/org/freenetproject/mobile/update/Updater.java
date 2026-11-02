package org.freenetproject.mobile.update;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.net.Tor;
import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.R;
import org.freenetproject.mobile.ui.settings.activity.SettingsActivity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Looks for new versions in the project's GitHub releases.
 *
 * Android asks the user to confirm every install, and only accepts an update signed with the
 * same key as the installed app: a tampered release cannot replace it.
 */
public final class Updater {
    private static final String TAG = "Freenet";
    // Not the GitHub API: it allows 60 requests an hour per network, shared by all its devices.
    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/blue-terminal/freenet-android/main/update.json";
    private static final String DOWNLOAD_PREFIX =
            "https://github.com/blue-terminal/freenet-android/releases/download/";
    // Second road, when the first one is filtered by the network or the phone.
    private static final String RELEASES_API =
            "https://api.github.com/repos/blue-terminal/freenet-android/releases?per_page=5";
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int TIMEOUT_MILLIS = 20000;
    private static final int MAX_LIST_BYTES = 2 * 1024 * 1024;
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    private static final String LAST_CHECK = "update_last_check";
    private static final long AUTO_CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(10);
    private static final String CHANNEL_ID = "FREENET UPDATES";
    private static final int NOTIFICATION_ID = 2;

    private Updater() {
    }

    private static final class Release {
        String version;
        String url;
        String sha256;
    }

    /** Every time the app opens, when the setting is on. It stays silent unless there is an update. */
    public static void autoCheck(Activity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        long now = System.currentTimeMillis();
        if (!preferences.getBoolean("auto_update", true)
                || now - preferences.getLong(LAST_CHECK, 0) < AUTO_CHECK_INTERVAL) {
            return;
        }
        preferences.edit().putLong(LAST_CHECK, now).apply();
        check(activity, false);
    }

    /** Returns false when GitHub could not be reached, so the check can be tried again. */
    static boolean notifyIfNew(Context context) {
        try {
            Release release = newestRelease();
            if (release != null) {
                showNotification(context, release);
            }
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Background update check failed", e);
            return false;
        }
    }

    private static void showNotification(Context context, Release release) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.updates), NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_CHECK_UPDATE, true);
        manager.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.update_available_title))
                .setContentText(release.version)
                .setSmallIcon(R.drawable.ic_freenet_logo_notification)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 1, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build());
    }

    public static void check(Activity activity, boolean showResult) {
        new Thread(() -> {
            try {
                Release release = newestRelease();
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    if (release != null) {
                        askToInstall(activity, release);
                    } else if (showResult) {
                        toast(activity, activity.getString(R.string.update_none, BuildConfig.VERSION_NAME));
                    }
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Update check failed", e);
                if (showResult) {
                    activity.runOnUiThread(() -> toast(activity,
                            activity.getString(R.string.update_unreachable, describe(e))));
                }
            }
        }, "freenet-update-check").start();
    }

    private static Release newestRelease() throws IOException, JSONException {
        try {
            return fromManifest();
        } catch (IOException | JSONException first) {
            Log.w(TAG, "Update file not available, trying the GitHub API", first);
            try {
                return fromApi();
            } catch (IOException | JSONException second) {
                throw first; // the first failure says more about the phone's network
            }
        }
    }

    private static Release fromApi() throws IOException, JSONException {
        JSONArray releases = new JSONArray(fetchText(RELEASES_API));
        int[] newest = parseVersion(BuildConfig.VERSION_NAME);
        Release found = null;
        for (int i = 0; newest != null && i < releases.length(); i++) {
            JSONObject item = releases.getJSONObject(i);
            int[] version = parseVersion(item.getString("tag_name"));
            if (item.getBoolean("draft") || version == null || compare(version, newest) <= 0) {
                continue;
            }
            JSONArray assets = item.getJSONArray("assets");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String digest = asset.optString("digest", "");
                if (asset.getString("name").endsWith(Build.SUPPORTED_ABIS[0] + "-release.apk")
                        && digest.startsWith("sha256:")) {
                    found = new Release();
                    found.version = item.getString("tag_name");
                    found.url = asset.getString("browser_download_url");
                    found.sha256 = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
                    newest = version;
                }
            }
        }
        if (found != null && !found.url.startsWith(DOWNLOAD_PREFIX)) {
            throw new IOException("Unexpected download address");
        }
        return found;
    }

    private static Release fromManifest() throws IOException, JSONException {
        JSONObject manifest = new JSONObject(fetchText(MANIFEST_URL));
        String tag = manifest.getString("version");
        int[] version = parseVersion(tag);
        int[] current = parseVersion(BuildConfig.VERSION_NAME);
        if (version == null || current == null || compare(version, current) <= 0) {
            return null;
        }
        JSONObject asset = manifest.getJSONObject("assets").optJSONObject(Build.SUPPORTED_ABIS[0]);
        if (asset == null) {
            return null;
        }
        Release release = new Release();
        release.version = tag;
        release.url = asset.getString("url");
        release.sha256 = asset.getString("sha256").toLowerCase(Locale.ROOT);
        if (!release.url.startsWith(DOWNLOAD_PREFIX)) {
            throw new IOException("Unexpected download address");
        }
        return release;
    }

    private static int[] parseVersion(String text) {
        Matcher matcher = VERSION.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))};
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static void askToInstall(Activity activity, Release release) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(activity.getString(R.string.update_available, release.version))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_download, (d, w) -> {
                    toast(activity, activity.getString(R.string.update_downloading));
                    new Thread(() -> download(activity, release), "freenet-update-download").start();
                })
                .show();
    }

    private static void download(Activity activity, Release release) {
        Context context = activity.getApplicationContext();
        try {
            File directory = new File(context.getCacheDir(), "updates");
            emptyDirectory(directory);
            File apk = new File(directory, "update.apk");
            saveVerified(release, apk);
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", apk);
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(install);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            Log.w(TAG, "Update download failed", e);
            activity.runOnUiThread(() -> toast(activity,
                    activity.getString(R.string.update_failed, e.getMessage())));
        }
    }

    private static void emptyDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory);
        }
        File[] old = directory.listFiles();
        if (old != null) {
            for (File file : old) {
                if (!file.delete()) {
                    Log.w(TAG, "Cannot delete " + file);
                }
            }
        }
    }

    private static void saveVerified(Release release, File apk)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpsURLConnection connection = open(release.url);
        long total = 0;
        try (InputStream in = connection.getInputStream();
             OutputStream out = new FileOutputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_APK_BYTES) {
                    throw new IOException("File too large");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        if (!hex.toString().equals(release.sha256)) {
            if (!apk.delete()) {
                Log.w(TAG, "Cannot delete the bad download");
            }
            throw new IOException("Checksum mismatch");
        }
    }

    private static String fetchText(String url) throws IOException {
        HttpsURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_LIST_BYTES) {
                    throw new IOException("Answer too long");
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection open(String url) throws IOException {
        HttpsURLConnection connection = Tor.open(url);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "freenet-android-updater");
        return connection;
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}

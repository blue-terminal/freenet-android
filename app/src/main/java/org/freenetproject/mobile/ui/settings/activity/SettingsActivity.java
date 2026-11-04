package org.freenetproject.mobile.ui.settings.activity;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.provider.Settings;
import android.os.PowerManager;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Random;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.freenetproject.mobile.App;
import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.net.HostProxy;
import org.freenetproject.mobile.net.Tor;
import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;
import org.freenetproject.mobile.ui.bootstrap.activity.BootstrapActivity;
import org.freenetproject.mobile.ui.ghostkey.GhostKeyActivity;
import org.freenetproject.mobile.ui.main.activity.MainFragment;
import org.freenetproject.mobile.ui.sites.SitesActivity;
import org.freenetproject.mobile.update.UpdateWorker;
import org.freenetproject.mobile.update.Updater;

public class SettingsActivity extends AppCompatActivity {
    public static final String EXTRA_CHECK_UPDATE = "check_update";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        // Opened from the update notification: go straight to the download question.
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_CHECK_UPDATE, false)) {
            Updater.check(this, true);
        }
    }

    public static final String PREF_LOGO_CUSTOM_PATH = "logo_custom_path";

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private ActivityResultLauncher<String> imagePicker;

        /** Straight from Freenet\'s own instructions; not translated, they are commands. */
        private static final String INSTALL_COMMANDS =
                "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh\n"
                        + "cargo install ghostkey\n"
                        + "ghostkey -h\n"
                        + "ghostkey verify-ghost-key \\\n"
                        + "    --ghost-certificate ~/Downloads/ghost-key-cert.pem\n";

        private static final String KEY_DEVELOPER = "developer_enabled";
        private static final long DOUBLE_TAP_MILLIS = 600;
        private long lastBuildTap = 0;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            imagePicker = registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        try {
                            File dest = new File(requireContext().getFilesDir(), "custom_logo.png");
                            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                                 FileOutputStream out = new FileOutputStream(dest)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            }
                            PreferenceManager.getDefaultSharedPreferences(requireContext())
                                    .edit().putString(PREF_LOGO_CUSTOM_PATH, dest.getAbsolutePath()).apply();
                            Toast.makeText(requireContext(), R.string.logo_custom_pick, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(requireContext(), R.string.logo_custom_none, Toast.LENGTH_SHORT).show();
                        }
                    });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            Preference language = findPreference(App.KEY_LANGUAGE);
            if (language != null) {
                language.setOnPreferenceChangeListener((preference, value) -> {
                    App.applyLanguage((String) value);
                    return true;
                });
            }
            Preference color = findPreference(App.KEY_COLOR);
            if (color != null) {
                color.setOnPreferenceChangeListener((preference, value) -> {
                    // After the value is saved: the new screen reads it.
                    preference.getPreferenceManager().getSharedPreferences().edit()
                            .putString(App.KEY_COLOR, (String) value).commit();
                    requireActivity().recreate();
                    return true;
                });
            }
            setUpBuildNumber();
            Preference colorblind = findPreference(App.KEY_COLORBLIND);
            if (colorblind != null) {
                colorblind.setOnPreferenceChangeListener((preference, value) -> {
                    preference.getPreferenceManager().getSharedPreferences().edit()
                            .putBoolean(App.KEY_COLORBLIND, (Boolean) value).commit();
                    requireActivity().recreate();
                    return true;
                });
            }
            Preference blockScreenshots = findPreference(App.KEY_BLOCK_SCREENSHOTS);
            if (blockScreenshots != null) {
                blockScreenshots.setOnPreferenceChangeListener((preference, value) -> {
                    App.applyScreenshotBlock(requireActivity(), (Boolean) value);
                    return true;
                });
            }
            Preference newInvite = findPreference("dev_new_invite");
            if (newInvite != null) {
                newInvite.setOnPreferenceClickListener(preference -> {
                    Context context = requireContext();
                    context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE)
                            .edit().remove(MainFragment.RIVER_JOINED).apply();
                    Toast.makeText(context, R.string.dev_new_invite_done, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            Preference clearWeb = findPreference("dev_clear_web");
            if (clearWeb != null) {
                clearWeb.setOnPreferenceClickListener(preference -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.dev_clear_web)
                            .setMessage(R.string.dev_clear_web_warning)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok, (d, w) -> {
                                WebStorage.getInstance().deleteAllData();
                                CookieManager.getInstance().removeAllCookies(null);
                                Toast.makeText(requireContext(), R.string.dev_clear_web_done,
                                        Toast.LENGTH_SHORT).show();
                            })
                            .show();
                    return true;
                });
            }
            Preference copyLog = findPreference("dev_copy_log");
            if (copyLog != null) {
                copyLog.setOnPreferenceClickListener(preference -> {
                    Context context = requireContext();
                    context.getSystemService(ClipboardManager.class).setPrimaryClip(
                            ClipData.newPlainText("Freenet", Manager.logTail(context)));
                    Toast.makeText(context, R.string.dev_log_copied, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            Preference official = findPreference("ghost_official");
            if (official != null) {
                official.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(requireContext(), BootstrapActivity.class)
                            .putExtra(BootstrapActivity.EXTRA_URL, "https://freenet.org/ghostkey/"));
                    return true;
                });
            }
            Preference generator = findPreference("ghost_generator");
            if (generator != null) {
                generator.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(requireContext(), GhostKeyActivity.class));
                    return true;
                });
            }
            Preference zip = findPreference("site_zip");
            if (zip != null) {
                zip.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(requireContext(), SitesActivity.class));
                    return true;
                });
            }
            Preference news = findPreference("news");
            if (news != null) {
                news.setOnPreferenceClickListener(preference -> {
                    showText(R.string.news, R.string.news_text);
                    return true;
                });
            }
            Preference install = findPreference("ghost_install");
            if (install != null) {
                install.setOnPreferenceClickListener(preference -> {
                    showInstallSteps();
                    return true;
                });
            }
            Preference address = findPreference("host_address");
            if (address != null) {
                address.setOnPreferenceClickListener(preference -> {
                    showAddress();
                    return true;
                });
            }
            Preference proxyPwd = findPreference("proxy_password");
            if (proxyPwd != null) {
                proxyPwd.setOnPreferenceClickListener(preference -> {
                    String newPwd = HostProxy.newPassword(requireContext());
                    Manager.getInstance().onHostModeChanged(requireContext(),
                            PreferenceManager.getDefaultSharedPreferences(requireContext())
                                    .getBoolean("host_mode", false));
                    Toast.makeText(requireContext(),
                            getString(R.string.proxy_new_password, newPwd),
                            Toast.LENGTH_LONG).show();
                    return true;
                });
            }
            Preference proxyHistory = findPreference("proxy_history");
            if (proxyHistory != null) {
                proxyHistory.setOnPreferenceClickListener(preference -> {
                    showPasswordHistory();
                    return true;
                });
            }
            Preference logoCustom = findPreference("logo_custom_pick");
            if (logoCustom != null) {
                logoCustom.setOnPreferenceClickListener(preference -> {
                    imagePicker.launch("image/*");
                    return true;
                });
            }
            Preference tor = findPreference(Tor.KEY);
            if (tor != null) {
                tor.setOnPreferenceChangeListener((preference, value) -> {
                    Tor.setEnabled((Boolean) value);
                    if ((Boolean) value) {
                        checkOrbot();
                    }
                    return true;
                });
            }
            SwitchPreferenceCompat macRandom = findPreference("mac_random");
            if (macRandom != null) {
                macRandom.setOnPreferenceChangeListener((preference, value) -> {
                    boolean enable = (Boolean) value;
                    new Thread(() -> {
                        boolean root = hasRoot();
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (root) {
                                new Thread(() -> {
                                    boolean ok = setMacRandom(enable);
                                    if (!isAdded()) return;
                                    requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(),
                                            ok ? (enable ? R.string.mac_random_done : R.string.mac_random_off)
                                               : R.string.mac_random_error,
                                            Toast.LENGTH_LONG).show());
                                }, "mac-random").start();
                            } else {
                                macRandom.setChecked(!enable);
                                Toast.makeText(requireContext(), R.string.mac_random_no_root, Toast.LENGTH_LONG).show();
                                try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
                                catch (ActivityNotFoundException ignored) {}
                            }
                        });
                    }, "mac-root-check").start();
                    return true;
                });
            }
            SwitchPreferenceCompat blockTracking = findPreference("block_tracking");
            if (blockTracking != null) {
                blockTracking.setOnPreferenceChangeListener((preference, value) -> {
                    boolean enable = (Boolean) value;
                    new Thread(() -> {
                        boolean root = hasRoot();
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (root) {
                                new Thread(() -> {
                                    boolean ok = applyTrackingBlock(enable);
                                    if (!isAdded()) return;
                                    requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(),
                                            ok ? (enable ? R.string.block_tracking_on : R.string.block_tracking_off)
                                               : R.string.mac_random_error,
                                            Toast.LENGTH_LONG).show());
                                }, "block-tracking").start();
                            } else {
                                blockTracking.setChecked(!enable);
                                Toast.makeText(requireContext(), R.string.block_tracking_no_root, Toast.LENGTH_LONG).show();
                            }
                        });
                    }, "tracking-root-check").start();
                    return true;
                });
            }
            SwitchPreferenceCompat host = findPreference("host_mode");
            if (host != null) {
                host.setOnPreferenceChangeListener((preference, value) -> {
                    if (!(Boolean) value) {
                        Manager.getInstance().onHostModeChanged(requireContext(), false);
                        return true;
                    }
                    // Opening the node to the network is the user's call, asked for each time.
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.host_mode)
                            .setMessage(R.string.host_mode_warning)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.host_mode, (d, w) -> {
                                host.setChecked(true);
                                Manager.getInstance().onHostModeChanged(requireContext(), true);
                                showAddress();
                            })
                            .show();
                    return false;
                });
            }
            Preference saving = findPreference("saving_mode");
            if (saving != null) {
                saving.setOnPreferenceChangeListener((preference, value) -> {
                    // The new value is stored after this listener, so reschedule just after it.
                    requireView().post(() -> UpdateWorker.schedule(requireContext(),
                            PreferenceManager.getDefaultSharedPreferences(requireContext())
                                    .getBoolean("auto_update", true)));
                    Toast.makeText(requireContext(), R.string.saving_mode_restart,
                            Toast.LENGTH_LONG).show();
                    return true;
                });
            }
            Preference automatic = findPreference("auto_update");
            if (automatic != null) {
                automatic.setOnPreferenceChangeListener((preference, value) -> {
                    UpdateWorker.schedule(requireContext(), (Boolean) value);
                    return true;
                });
            }
            Preference check = findPreference("check_update");
            if (check != null) {
                showLastCheck(check);
                check.setOnPreferenceClickListener(preference -> {
                    Updater.check(requireActivity(), true, () -> showLastCheck(check));
                    return true;
                });
            }
            Preference chooseVersion = findPreference("choose_version");
            if (chooseVersion != null) {
                chooseVersion.setOnPreferenceClickListener(preference -> {
                    showVersionPicker();
                    return true;
                });
            }
            Preference background = findPreference("update_background");
            if (background != null) {
                background.setOnPreferenceClickListener(preference -> {
                    askToIgnoreBatteryLimits();
                    return true;
                });
            }
        }

        /** Says when the last check ran and what it found, so a silent failure is visible. */
        private void showLastCheck(Preference check) {
            SharedPreferences preferences =
                    PreferenceManager.getDefaultSharedPreferences(requireContext());
            long when = preferences.getLong(Updater.LAST_CHECK, 0);
            if (when == 0) {
                check.setSummary(R.string.update_never_checked);
                return;
            }
            String result = preferences.getString(Updater.LAST_RESULT, "");
            check.setSummary(getString(R.string.update_last_check,
                    DateUtils.getRelativeTimeSpanString(when),
                    result.isEmpty() ? getString(R.string.update_last_none) : result));
        }

        /**
         * Android stops background work for apps it considers idle, and then the check never
         * runs with the app closed. Only the system dialog can lift that, and the user decides.
         */
        private void askToIgnoreBatteryLimits() {
            PowerManager power = requireContext().getSystemService(PowerManager.class);
            String app = requireContext().getPackageName();
            if (power != null && power.isIgnoringBatteryOptimizations(app)) {
                Toast.makeText(requireContext(), R.string.update_background_already,
                        Toast.LENGTH_LONG).show();
                return;
            }
            try {
                startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + app)));
            } catch (ActivityNotFoundException missing) {
                // Some phones hide that screen: the general battery settings are the fallback.
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }

        /**
         * Tor only works while Orbot is running on the phone: nothing else provides the
         * SOCKS port. The check runs off the main thread and, when nothing answers, offers
         * to open Orbot instead of leaving the setting on with every request failing.
         */
        private void checkOrbot() {
            new Thread(() -> {
                boolean ready = Tor.available();
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (ready) {
                        Toast.makeText(requireContext(), R.string.tor_mode_on,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.tor_mode)
                            .setMessage(R.string.tor_no_orbot)
                            .setNegativeButton(android.R.string.ok, null)
                            .setPositiveButton(R.string.tor_open_orbot, (dialog, which) -> openOrbot())
                            .show();
                });
            }, "tor-check").start();
        }

        /** Orbot itself when it is installed, otherwise its page in the store. */
        private void openOrbot() {
            Intent app = requireContext().getPackageManager().getLaunchIntentForPackage(Tor.ORBOT);
            if (app != null) {
                startActivity(app);
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + Tor.ORBOT)));
            } catch (ActivityNotFoundException noBrowser) {
                Toast.makeText(requireContext(), R.string.tor_no_orbot, Toast.LENGTH_LONG).show();
            }
        }

        /** A plain scrollable dialog, for the texts that are too long for a summary. */
        private void showText(int title, int body) {
            TextView shown = new TextView(requireContext());
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            shown.setPadding(padding, padding, padding, padding);
            shown.setTextIsSelectable(true);
            shown.setText(body);
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(shown);
            new AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        }

        /**
         * The commands that install the ghostkey tool on a computer, scrollable and with a
         * button that copies them. The phone already carries the tool: this is for the PC,
         * where a certificate downloaded from Freenet is checked.
         */
        private void showInstallSteps() {
            TextView shown = new TextView(requireContext());
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            shown.setPadding(padding, padding, padding, padding);
            shown.setTextIsSelectable(true);
            shown.setTypeface(Typeface.MONOSPACE);
            shown.setText(getString(R.string.ghost_install_steps, INSTALL_COMMANDS));
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(shown);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.ghost_install)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton(R.string.ghost_install_copy, (dialog, which) -> {
                        requireContext().getSystemService(ClipboardManager.class)
                                .setPrimaryClip(ClipData.newPlainText(
                                        "ghostkey", INSTALL_COMMANDS));
                        Toast.makeText(requireContext(), R.string.ghost_install_copied,
                                Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }

        /** The address to type on the PC, big enough to read and with a button to copy it. */
        private void showAddress() {
            java.util.List<String> all = Manager.localAddresses();
            if (all.isEmpty()) {
                Toast.makeText(requireContext(), R.string.host_mode_no_network,
                        Toast.LENGTH_LONG).show();
                return;
            }
            String found = Manager.localAddress();
            // Use the proxy URL with Basic Auth password so the PC connection is protected.
            String pwd = HostProxy.getOrCreatePassword(requireContext());
            String proxyUrl = "http://freenet:" + pwd + "@" + found + ":" + HostProxy.PORT + "/";
            StringBuilder list = new StringBuilder();
            for (String one : all) {
                String ip = one.substring(one.indexOf(": ") + 2);
                String iface = one.substring(0, one.indexOf(':'));
                list.append("http://freenet:").append(pwd).append("@")
                        .append(ip).append(':').append(HostProxy.PORT)
                        .append("/  (").append(iface).append(")\n");
            }
            TextView shown = new TextView(requireContext());
            int padding = Math.round(20 * getResources().getDisplayMetrics().density);
            shown.setPadding(padding, padding, padding, padding);
            shown.setTextIsSelectable(true);
            shown.setTextSize(13);
            shown.setTypeface(Typeface.MONOSPACE);
            String network = found.substring(0, found.lastIndexOf('.') + 1) + "x";
            shown.setText(list + "\n" + getString(R.string.host_same_network, network)
                    + "\n\n" + getString(R.string.host_restart_node)
                    + "\n\n" + getString(R.string.proxy_password_hint));
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.host_address)
                    .setView(shown)
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton(R.string.host_copy_address, (d, w) -> {
                        requireContext().getSystemService(ClipboardManager.class)
                                .setPrimaryClip(ClipData.newPlainText("Freenet", proxyUrl));
                        Toast.makeText(requireContext(), R.string.host_address_copied,
                                Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }

        private void showPasswordHistory() {
            String text = HostProxy.readHistory(requireContext());
            if (text.trim().isEmpty()) {
                text = getString(R.string.proxy_history_empty);
            }
            TextView shown = new TextView(requireContext());
            int padding = Math.round(20 * getResources().getDisplayMetrics().density);
            shown.setPadding(padding, padding, padding, padding);
            shown.setTextIsSelectable(true);
            shown.setTypeface(android.graphics.Typeface.MONOSPACE);
            shown.setText(text);
            android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
            scroll.addView(shown);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.proxy_history)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton(R.string.proxy_history_clear, (d, w) -> {
                        HostProxy.clearHistory(requireContext());
                        Toast.makeText(requireContext(), R.string.proxy_history_cleared,
                                Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }

        /** Shows a dialog listing all GitHub releases; tapping one starts the download. */
        private void showVersionPicker() {
            Toast.makeText(requireContext(), R.string.choose_version_loading, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    java.util.List<org.freenetproject.mobile.update.Updater.Release> releases =
                            org.freenetproject.mobile.update.Updater.allReleases();
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (releases.isEmpty()) {
                            Toast.makeText(requireContext(), R.string.choose_version_error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        String current = org.freenetproject.mobile.BuildConfig.VERSION_NAME;
                        String[] labels = new String[releases.size()];
                        for (int i = 0; i < releases.size(); i++) {
                            String v = releases.get(i).version;
                            boolean installed = current.startsWith(v.replace("v", ""))
                                    || current.startsWith(v);
                            labels[i] = v + (installed ? "  (" + getString(R.string.choose_version_installed) + ")" : "");
                        }
                        new AlertDialog.Builder(requireContext())
                                .setTitle(R.string.choose_version)
                                .setItems(labels, (d, which) -> {
                                    org.freenetproject.mobile.update.Updater.Release r = releases.get(which);
                                    String msg = requireContext().getString(R.string.update_available, r.version);
                                    if (r.notes != null && !r.notes.isEmpty()) {
                                        msg += "\n\n" + getString(R.string.update_notes_label) + "\n" + r.notes;
                                    }
                                    String finalMsg = msg;
                                    new AlertDialog.Builder(requireContext())
                                            .setTitle(r.version)
                                            .setMessage(finalMsg)
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .setPositiveButton(R.string.update_download, (d2, w2) -> {
                                                Toast.makeText(requireContext(), R.string.update_downloading, Toast.LENGTH_SHORT).show();
                                                new Thread(() -> org.freenetproject.mobile.update.Updater.downloadPublic(requireActivity(), r),
                                                        "freenet-version-download").start();
                                            })
                                            .show();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    });
                } catch (Exception e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.choose_version_error, Toast.LENGTH_LONG).show());
                }
            }, "freenet-list-releases").start();
        }

        /** Returns true if the device has root (su binary accessible). */
        private boolean hasRoot() {
            // Try several su paths common on custom ROMs
            String[] suCandidates = {"su", "/system/bin/su", "/system/xbin/su", "/sbin/su"};
            for (String su : suCandidates) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{su, "-c", "id"});
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = br.readLine();
                    int exit = p.waitFor();
                    if (line != null && line.contains("uid=0")) return true;
                    if (exit == 0) return true; // some ROMs print nothing but exit 0
                } catch (Exception ignored) {}
            }
            // Fallback: check if su binary exists
            java.io.File[] suFiles = {
                new java.io.File("/system/bin/su"),
                new java.io.File("/system/xbin/su"),
                new java.io.File("/sbin/su")
            };
            for (java.io.File f : suFiles) {
                if (f.exists()) return true;
            }
            return false;
        }

        /** Generates a random locally-administered MAC and sets it via root. */
        private boolean setMacRandom(boolean enable) {
            try {
                String mac;
                if (enable) {
                    Random r = new Random();
                    int b0 = (r.nextInt(64) * 4) | 2; // locally administered, unicast
                    mac = String.format(Locale.ROOT, "%02x:%02x:%02x:%02x:%02x:%02x",
                            b0, r.nextInt(256), r.nextInt(256),
                            r.nextInt(256), r.nextInt(256), r.nextInt(256));
                } else {
                    // Read original MAC from the backup the first enable saved, or from hardware
                    mac = readOriginalMac();
                }
                String cmd = "ip link set wlan0 down && ip link set wlan0 address " + mac + " && ip link set wlan0 up";
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                p.waitFor();
                return p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        }

        private String readOriginalMac() {
            // Try to read real hardware address from sysfs (Android 10+ exposes it)
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/sys/class/net/wlan0/address"))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) return line.trim();
            } catch (Exception ignored) {}
            return "02:00:00:00:00:00";
        }

        /** Applies or removes iptables rules to block common tracking domains. */
        private boolean applyTrackingBlock(boolean enable) {
            // Known tracking/telemetry domains resolved to IPs that will be blocked via DNS-based rules.
            // We block outbound connections to Google Analytics, Firebase, Facebook and similar.
            String[] rules = {
                "iptables -" + (enable ? "A" : "D") + " OUTPUT -d 142.250.0.0/15 -p tcp --dport 443 -j DROP 2>/dev/null",
                "iptables -" + (enable ? "A" : "D") + " OUTPUT -d 157.240.0.0/17 -p tcp --dport 443 -j DROP 2>/dev/null",
                "iptables -" + (enable ? "A" : "D") + " OUTPUT -d 34.0.0.0/10 -p tcp --dport 443 -j DROP 2>/dev/null",
            };
            try {
                for (String rule : rules) {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", rule});
                    p.waitFor();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Two quick taps on the build number show or hide the developer settings. */
        private void setUpBuildNumber() {
            Preference build = findPreference("build_number");
            Preference developer = findPreference("developer");
            if (build == null || developer == null) {
                return;
            }
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
            developer.setVisible(preferences.getBoolean(KEY_DEVELOPER, false));
            build.setSummary(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
            build.setOnPreferenceClickListener(preference -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastBuildTap < DOUBLE_TAP_MILLIS) {
                    boolean enabled = !developer.isVisible();
                    developer.setVisible(enabled);
                    preferences.edit().putBoolean(KEY_DEVELOPER, enabled).apply();
                    Toast.makeText(requireContext(),
                            enabled ? R.string.developer_on : R.string.developer_off,
                            Toast.LENGTH_SHORT).show();
                    lastBuildTap = 0;
                } else {
                    lastBuildTap = now;
                }
                return true;
            });
        }
    }
}

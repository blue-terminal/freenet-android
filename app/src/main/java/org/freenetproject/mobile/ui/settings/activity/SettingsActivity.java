package org.freenetproject.mobile.ui.settings.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.App;
import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;
import org.freenetproject.mobile.ui.bootstrap.activity.BootstrapActivity;
import org.freenetproject.mobile.ui.ghostkey.GhostKeyActivity;
import org.freenetproject.mobile.ui.main.activity.MainFragment;
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

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final String KEY_DEVELOPER = "developer_enabled";
        private static final long DOUBLE_TAP_MILLIS = 600;
        private long lastBuildTap = 0;

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
            Preference automatic = findPreference("auto_update");
            if (automatic != null) {
                automatic.setOnPreferenceChangeListener((preference, value) -> {
                    UpdateWorker.schedule(requireContext(), (Boolean) value);
                    return true;
                });
            }
            Preference check = findPreference("check_update");
            if (check != null) {
                check.setOnPreferenceClickListener(preference -> {
                    Updater.check(requireActivity(), true);
                    return true;
                });
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

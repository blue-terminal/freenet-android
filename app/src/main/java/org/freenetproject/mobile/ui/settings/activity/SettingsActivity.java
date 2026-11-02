package org.freenetproject.mobile.ui.settings.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.App;
import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.R;
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

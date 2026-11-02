package org.freenetproject.mobile.ui.settings.activity;

import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.freenetproject.mobile.App;
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
    }
}

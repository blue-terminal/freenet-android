package org.freenetproject.mobile;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.update.UpdateWorker;

public class App extends Application {
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_COLOR = "color";

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        applyLanguage(preferences.getString(KEY_LANGUAGE, ""));
        registerActivityLifecycleCallbacks(new ColorApplier());
        UpdateWorker.schedule(this, preferences.getBoolean("auto_update", true));
    }

    /** An empty value follows the language of the phone. */
    public static void applyLanguage(String tag) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    private static int colorStyle(String name) {
        switch (name) {
            case "blue":
                return R.style.Accent_Blue;
            case "violet":
                return R.style.Accent_Violet;
            case "orange":
                return R.style.Accent_Orange;
            case "pink":
                return R.style.Accent_Pink;
            case "green":
                return R.style.Accent_Green;
            default:
                return R.style.Accent_Teal;
        }
    }

    /** Runs inside each activity's super.onCreate, before it builds its screen. */
    private static final class ColorApplier implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            String color = PreferenceManager.getDefaultSharedPreferences(activity)
                    .getString(KEY_COLOR, "teal");
            activity.getTheme().applyStyle(colorStyle(color), true);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}

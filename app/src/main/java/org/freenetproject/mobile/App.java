package org.freenetproject.mobile;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.update.UpdateWorker;

public class App extends Application {
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_COLOR = "color";
    public static final String KEY_BLOCK_SCREENSHOTS = "dev_block_screenshots";
    private static final String KEY_MINIMAL_DESIGN = "minimal_design";

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.getBoolean(KEY_MINIMAL_DESIGN, false)) {
            preferences.edit().putBoolean(KEY_MINIMAL_DESIGN, true).putString(KEY_COLOR, "blue").apply();
        }
        applyLanguage(preferences.getString(KEY_LANGUAGE, ""));
        registerActivityLifecycleCallbacks(new ColorApplier());
        UpdateWorker.schedule(this, preferences.getBoolean("auto_update", true));
    }

    public static void applyScreenshotBlock(Activity activity, boolean block) {
        if (block) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** An empty value follows the language of the phone. */
    public static void applyLanguage(String tag) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    private static int colorStyle(String name) {
        switch (name) {
            case "teal":
                return R.style.Accent_Teal;
            case "violet":
                return R.style.Accent_Violet;
            case "orange":
                return R.style.Accent_Orange;
            case "pink":
                return R.style.Accent_Pink;
            case "green":
                return R.style.Accent_Green;
            default:
                return R.style.Accent_Blue;
        }
    }

    /** Runs inside each activity's super.onCreate, before it builds its screen. */
    private static final class ColorApplier implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            String color = PreferenceManager.getDefaultSharedPreferences(activity)
                    .getString(KEY_COLOR, "blue");
            activity.getTheme().applyStyle(colorStyle(color), true);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            applyScreenshotBlock(activity, preferences.getBoolean(KEY_BLOCK_SCREENSHOTS, false));
            if (!preferences.getBoolean("dev_animations", true)) {
                activity.getWindow().setWindowAnimations(0);
            }
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            // A screen left open while the option changed must follow it too.
            applyScreenshotBlock(activity, PreferenceManager.getDefaultSharedPreferences(activity)
                    .getBoolean(KEY_BLOCK_SCREENSHOTS, false));
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

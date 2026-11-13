package org.freenetproject.mobile;

import android.content.Context;

import androidx.preference.PreferenceManager;

/**
 * The security level the user picked in the settings.
 * Standard: everything on. High: few wrong SSH passwords lock the sender out for longer.
 * Extreme: no SSH access from the PC, no root commands, no video call through an outside server,
 * no screenshots of the app.
 */
public final class Security {
    public static final String KEY = "security_level";

    private Security() {
    }

    public static String level(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "standard");
    }

    public static boolean high(Context context) {
        String level = level(context);
        return "high".equals(level) || "extreme".equals(level);
    }

    public static boolean extreme(Context context) {
        return "extreme".equals(level(context));
    }

    /** Wrong SSH passwords allowed from one address before it is locked out. */
    public static int sshMaxFailures(Context context) {
        return high(context) ? 3 : 10;
    }

    public static long sshLockoutMs(Context context) {
        return (high(context) ? 60 : 15) * 60_000L;
    }
}

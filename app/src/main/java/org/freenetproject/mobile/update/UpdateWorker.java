package org.freenetproject.mobile.update;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.preference.PreferenceManager;

import java.util.concurrent.TimeUnit;

/** Looks for updates even when the app is closed: Android decides the exact moment. */
public class UpdateWorker extends Worker {
    private static final String NAME = "freenet-update-check";
    private static final long INTERVAL_HOURS = 6;
    // Saving mode checks four times less often, and only on an unmetered network.
    private static final long SAVING_INTERVAL_HOURS = 24;

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void schedule(Context context, boolean enabled) {
        WorkManager manager = WorkManager.getInstance(context);
        if (!enabled) {
            manager.cancelUniqueWork(NAME);
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean saving = preferences.getBoolean("saving_mode", false);
        manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(UpdateWorker.class,
                        saving ? SAVING_INTERVAL_HOURS : INTERVAL_HOURS, TimeUnit.HOURS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(
                                        saving ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(saving)
                                .build())
                        .build());
    }

    @NonNull
    @Override
    public Result doWork() {
        return Updater.notifyIfNew(getApplicationContext()) ? Result.success() : Result.retry();
    }
}

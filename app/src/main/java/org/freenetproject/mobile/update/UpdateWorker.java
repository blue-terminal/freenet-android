package org.freenetproject.mobile.update;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Looks for updates even when the app is closed: Android decides the exact moment. */
public class UpdateWorker extends Worker {
    private static final String NAME = "freenet-update-check";
    private static final long INTERVAL_HOURS = 6;

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void schedule(Context context, boolean enabled) {
        WorkManager manager = WorkManager.getInstance(context);
        if (!enabled) {
            manager.cancelUniqueWork(NAME);
            return;
        }
        manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(UpdateWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .build());
    }

    @NonNull
    @Override
    public Result doWork() {
        return Updater.notifyIfNew(getApplicationContext()) ? Result.success() : Result.retry();
    }
}

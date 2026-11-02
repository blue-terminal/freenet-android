package org.freenetproject.mobile.services.node;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.receivers.BatteryLevelReceiver;
import org.freenetproject.mobile.receivers.PowerConnectionReceiver;
import org.freenetproject.mobile.ui.notification.Notification;
import org.freenetproject.mobile.update.Updater;

import java.util.concurrent.TimeUnit;

public class Service extends android.app.Service {
    private static final long UPDATE_CHECK_MILLIS = TimeUnit.HOURS.toMillis(6);

    private final PowerConnectionReceiver powerConnectionReceiver = new PowerConnectionReceiver();
    private final BatteryLevelReceiver batteryLevelReceiver = new BatteryLevelReceiver();
    private final Manager manager = Manager.getInstance();
    private final Observer<Manager.Status> notificationUpdater =
            status -> Notification.update(this, status);
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean started = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateChecks = new Runnable() {
        @Override
        public void run() {
            Updater.notifyInBackground(getApplicationContext());
            handler.postDelayed(this, UPDATE_CHECK_MILLIS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("Freenet", "Called service onStartCommand");

        android.app.Notification notification = Notification.show(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notification.ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Notification.ID, notification);
        }

        if (!started) {
            started = true;
            manager.clearPauses();
            manager.getStatus().observeForever(notificationUpdater);
            registerConditions();
            updateChecks.run();
            new Thread(() -> manager.startNode(getApplicationContext()), "freenet-start").start();
        }

        // If Android kills the app the node goes with it: the user starts it again.
        return START_NOT_STICKY;
    }

    private void registerConditions() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        // A nearly empty battery always stops the node.
        registerReceiver(batteryLevelReceiver, new IntentFilter(Intent.ACTION_BATTERY_LOW),
                Context.RECEIVER_NOT_EXPORTED);

        if (preferences.getBoolean("preserve_battery", false)) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            registerReceiver(powerConnectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            BatteryManager battery = getSystemService(BatteryManager.class);
            if (!battery.isCharging()) {
                manager.pause(Manager.PauseReason.BATTERY);
            }
        }

        if (preferences.getBoolean("preserve_data", true)) {
            connectivityManager = getSystemService(ConnectivityManager.class);
            // Without this check a node started on mobile data would never be paused.
            if (connectivityManager.isActiveNetworkMetered()) {
                manager.pause(Manager.PauseReason.NETWORK);
            }
            Context context = getApplicationContext();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    new Thread(() -> manager.resume(context, Manager.PauseReason.NETWORK)).start();
                }

                @Override
                public void onLost(Network network) {
                    new Thread(() -> manager.pause(Manager.PauseReason.NETWORK)).start();
                }
            };
            connectivityManager.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .build(), networkCallback);
        }
    }

    @Override
    public void onDestroy() {
        Log.i("Freenet", "Stopping service");
        handler.removeCallbacks(updateChecks);
        if (started) {
            unregisterReceiver(batteryLevelReceiver);
            try {
                unregisterReceiver(powerConnectionReceiver);
            } catch (IllegalArgumentException notRegistered) {
                Log.d("Freenet", "Power receiver was not registered");
            }
            if (networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
            manager.getStatus().removeObserver(notificationUpdater);
        }
        new Thread(() -> manager.stopNode(false), "freenet-stop").start();
        Notification.remove(getApplicationContext());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

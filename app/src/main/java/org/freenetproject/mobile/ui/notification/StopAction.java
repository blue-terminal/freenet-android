package org.freenetproject.mobile.ui.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.freenetproject.mobile.services.node.Manager;

/** Stops the node when the user taps "Stop" in the foreground notification. */
public class StopAction extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Manager manager = Manager.getInstance();
        if (manager.isRunning() || manager.isPaused() || manager.isTransitioning()) {
            manager.stopService(context);
        }
    }
}

package org.freenetproject.mobile.ui.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.freenetproject.mobile.services.node.Manager;

/**
 * Puts the running notification back when it is swiped away. Android allows a foreground
 * service notification to be dismissed, and without it there is no way to see that the node
 * is running or to get back to the app, so it is shown again as long as the node is up.
 */
public class Restore extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Manager.Status status = Manager.getInstance().getStatus().getValue();
        if (status == Manager.Status.STARTED || status == Manager.Status.STARTING_UP
                || status == Manager.Status.PAUSED) {
            Notification.update(context, status);
        }
    }
}

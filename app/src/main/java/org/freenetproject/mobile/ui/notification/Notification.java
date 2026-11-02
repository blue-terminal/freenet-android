package org.freenetproject.mobile.ui.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;
import org.freenetproject.mobile.ui.main.activity.MainActivity;

/**
 * Class responsible for creating the notification in order to be able to run Freenet on background.
 */
public class Notification {
    public static final int ID = 1;
    private static final String CHANNEL_ID = "FREENET SERVICE";

    /**
     * Create a notification to remain on background.
     *
     * @param context Application context
     * @return the notification for the foreground service
     */
    public static android.app.Notification show(Context context) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, context.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return build(context, context.getString(R.string.node_starting_up), "");
    }

    public static void update(Context context, Manager.Status status) {
        String title;
        String text = "";
        switch (status) {
            case STARTED:
                title = context.getString(R.string.node_running);
                long ms = Manager.getInstance().uptimeMs();
                if (ms > 0) {
                    long s = ms / 1000;
                    long h = s / 3600;
                    long min = (s % 3600) / 60;
                    long sec = s % 60;
                    text = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, min, sec);
                } else {
                    text = context.getString(R.string.connected_to_the_network);
                }
                break;
            case PAUSED:
                title = context.getString(R.string.node_paused);
                text = context.getString(R.string.battery_and_data);
                break;
            case ERROR:
                title = context.getString(R.string.error_starting_up);
                text = context.getString(R.string.error_detail);
                break;
            case STARTING_UP:
                title = context.getString(R.string.node_starting_up);
                break;
            default:
                return;
        }
        context.getSystemService(NotificationManager.class).notify(ID, build(context, title, text));
    }

    private static android.app.Notification build(Context context, String title, String text) {
        PendingIntent stopIntent = PendingIntent.getBroadcast(
                context,
                3,
                new Intent(context.getApplicationContext(), StopAction.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Action stopAction = new android.app.Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_baseline_power_settings_new_24),
                context.getString(R.string.notification_stop),
                stopIntent).build();
        return new android.app.Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .addAction(stopAction)
                .setDeleteIntent(PendingIntent.getBroadcast(
                        context,
                        2,
                        new Intent(context.getApplicationContext(), Restore.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setSmallIcon(R.drawable.ic_freenet_logo_notification)
                .setContentIntent(PendingIntent.getActivity(
                        context,
                        0,
                        new Intent(context.getApplicationContext(), MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    /**
     * Removes all notifications.
     * @param context
     */
    public static void remove(Context context) {
        context.getSystemService(NotificationManager.class).cancelAll();
    }
}

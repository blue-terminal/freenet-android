package org.freenetproject.mobile.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen sharing for the FreenetTV Live page: Android's WebView has no getDisplayMedia, so the app
 * captures the screen (after the user accepts the system dialog) and hands JPEG frames to the page,
 * which draws them on a canvas and streams that like a camera.
 */
public class ScreenCaptureService extends Service {
    public interface Frames {
        void frame(String jpegBase64);

        void ended();
    }

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "screen";
    private static final int MAX_WIDTH = 960;
    private static final long FRAME_MS = 200;

    private static Frames listener;
    private static ScreenCaptureService running;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private long lastFrame;

    public static void setListener(Frames frames) {
        listener = frames;
    }

    public static void stop() {
        if (running != null) {
            running.stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Condivisione schermo",
                NotificationManager.IMPORTANCE_LOW));
        Notification note = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle("Stai condividendo lo schermo")
                .setContentText("Diretta FreenetTV")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(7, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, note);
        }
        Intent data = intent == null ? null : intent.getParcelableExtra(EXTRA_DATA);
        int code = intent == null ? 0 : intent.getIntExtra(EXTRA_CODE, 0);
        if (data == null || code != Activity.RESULT_OK) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            begin(code, data);
            running = this;
        } catch (RuntimeException e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin(int code, Intent data) {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(code, data);
        thread = new HandlerThread("screen");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, handler);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float scale = Math.min(1f, (float) MAX_WIDTH / Math.max(metrics.widthPixels, metrics.heightPixels));
        int width = Math.round(metrics.widthPixels * scale) & ~1;
        int height = Math.round(metrics.heightPixels * scale) & ~1;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image == null) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                if (now - lastFrame < FRAME_MS || listener == null) {
                    return;
                }
                lastFrame = now;
                listener.frame(encode(image, width, height));
            } finally {
                image.close();
            }
        }, handler);
        display = projection.createVirtualDisplay("web70-screen", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
    }

    private static String encode(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowPixels = plane.getRowStride() / plane.getPixelStride();
        Bitmap padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap frame = rowPixels == width ? padded : Bitmap.createBitmap(padded, 0, 0, width, height);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        frame.compress(Bitmap.CompressFormat.JPEG, 60, out);
        if (frame != padded) {
            frame.recycle();
        }
        padded.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    @Override
    public void onDestroy() {
        running = null;
        if (display != null) {
            display.release();
        }
        if (reader != null) {
            reader.close();
        }
        if (projection != null) {
            projection.stop();
        }
        if (thread != null) {
            thread.quitSafely();
        }
        if (listener != null) {
            listener.ended();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package javax.microedition.shell;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

import namod.j2me.R;

public class ForegroundService extends Service {
    private static final String CHANNEL_ID = "foreground_service";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "ForegroundService";
    private SharedPreferences preferences;
    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastCpuTime = 0;
    private long lastRealTime = 0;
    private long lastPeriodicGcTime = 0;
    private long lastThresholdGcTime = 0;
    private String lastGcStatus = "";

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            updateNotification();
            handler.postDelayed(this, 3000L);
        }
    };

    public class LocalBinder extends Binder {
        public ForegroundService getService() {
            return ForegroundService.this;
        }
    }

    private void checkForceGC(long currentAppRamMB) {
        if (!preferences.getBoolean("pref_force_gc", false)) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean shouldClean = false;
        String reason = "";

        try {
            int threshold = Integer.parseInt(preferences.getString("pref_gc_threshold", "0"));
            if (threshold > 0 && currentAppRamMB >= threshold) {
                if (now - lastThresholdGcTime > 10000) {
                    lastThresholdGcTime = now;
                    reason = "App RAM vượt ngưỡng " + threshold + "MB";
                    shouldClean = true;
                }
            }
        } catch (Exception ignored) {}

        if (!shouldClean) {
            try {
                int interval = Integer.parseInt(preferences.getString("pref_gc_interval", "0"));
                if (interval > 0 && (now - lastPeriodicGcTime >= interval * 1000L)) {
                    lastPeriodicGcTime = now;
                    reason = "Định kỳ " + interval + "s";
                    shouldClean = true;
                }
            } catch (Exception ignored) {}
        }

        if (shouldClean) {
            performOverkillClean(reason);
        }
    }

    private void performOverkillClean(String reason) {
        Runtime runtime = Runtime.getRuntime();
        try {
            Image.clearCache();
            Font.clearCache();
        } catch (Throwable ignored) {}

        new Thread(() -> {
            try {
                Graphics3D.getInstance().releaseTarget();
            } catch (Throwable ignored) {}

            try {
                getApplication().onTrimMemory(60);
                for (int i = 0; i < 5; i++) {
                    System.runFinalization();
                    System.gc();
                    runtime.gc();
                    Thread.sleep(200L);
                }
                handler.post(() -> {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    lastGcStatus = "[Dọn lúc " + sdf.format(new Date()) + "]";
                    updateNotification();
                });
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi dọn dẹp: " + e.getMessage());
            }
        }).start();
    }

    private Notification getNotification(String content) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("J2ME Loader Status")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(getPendingIntent())
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return builder.build();
    }

    private PendingIntent getPendingIntent() {
        Intent intent = new Intent(this, MicroActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private long getTotalRamMB(ActivityManager.MemoryInfo memoryInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return memoryInfo.totalMem / (1024 * 1024);
        }
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r");
            String line = reader.readLine();
            reader.close();
            Matcher matcher = Pattern.compile("(\\d+)").matcher(line);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1)) / 1024;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public void updateNotification() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memInfo);
        }
        long totalRamMB = getTotalRamMB(memInfo);
        long usedRamMB = totalRamMB - (memInfo.availMem / (1024 * 1024));

        Debug.MemoryInfo debugMemInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(debugMemInfo);
        long appPssMB = debugMemInfo.getTotalPss() / 1024;

        checkForceGC(appPssMB);

        Runtime runtime = Runtime.getRuntime();
        long elapsedCpuTime = Process.getElapsedCpuTime();
        long elapsedRealtime = SystemClock.elapsedRealtime();
        double cpuUsage = 0.0;

        if (lastRealTime != 0) {
            long cpuDiff = elapsedCpuTime - lastCpuTime;
            long realDiff = elapsedRealtime - lastRealTime;
            if (realDiff > 0) {
                int cpus = runtime.availableProcessors();
                cpuUsage = ((double) cpuDiff / (double) realDiff / (double) cpus) * 100.0;
            }
        }
        lastCpuTime = elapsedCpuTime;
        lastRealTime = elapsedRealtime;

        String title = String.format(Locale.getDefault(), "RAM: %d/%d MB | App: %d MB", usedRamMB, totalRamMB, appPssMB);
        String text = String.format(Locale.getDefault(), "CPU: %.1f%% %s", cpuUsage, lastGcStatus);

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(getPendingIntent())
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Foreground Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(updateRunnable);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, getNotification("Đang tính toán..."), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, getNotification("Đang tính toán..."));
            }
            handler.post(updateRunnable);
        } catch (Throwable t) {
            Log.e(TAG, "Failed startForeground", t);
        }
        return START_STICKY;
    }
}

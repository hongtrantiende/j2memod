package javax.microedition.shell;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
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
    public static long lastAppRamMB = 0;
    public static double lastCpuPercent = 0.0;

    private SharedPreferences preferences;
    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastCpuTime = 0;
    private long lastRealTime = 0;
    private long lastPeriodicGcTime = 0;
    private long lastThresholdGcTime = 0;
    private String lastGcStatus = "";
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

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

    private void acquireLocks() {
        try {
            boolean bgRun = preferences != null && preferences.getBoolean("pref_background_run", true);
            if (bgRun) {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null) {
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "J2MELoader:AfkWakeLock");
                        wakeLock.acquire();
                    }
                }
                if (wifiLock == null) {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wm != null) {
                        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "J2MELoader:AfkWifiLock");
                        wifiLock.acquire();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error acquiring locks: " + t);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                wifiLock = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error releasing locks: " + t);
        }
    }

    private void checkForceGC(long currentAppRamMB) {
        if (!preferences.getBoolean("pref_force_gc", false)) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean shouldClean = false;
        String reason = "";

        int threshold = 200;
        try {
            String thStr = preferences.getString("pref_gc_threshold", "200");
            threshold = Integer.parseInt(thStr);
        } catch (Exception ignored) {}

        if (threshold > 0 && currentAppRamMB >= threshold) {
            if (now - lastThresholdGcTime > 15000) {
                lastThresholdGcTime = now;
                reason = "App RAM vượt " + threshold + "MB";
                shouldClean = true;
            }
        }

        if (!shouldClean) {
            int interval = 60;
            try {
                String intStr = preferences.getString("pref_gc_interval", "60");
                interval = Integer.parseInt(intStr);
            } catch (Exception ignored) {}

            if (interval > 0 && (now - lastPeriodicGcTime >= interval * 1000L)) {
                lastPeriodicGcTime = now;
                reason = "Định kỳ " + interval + "s";
                shouldClean = true;
            }
        }

        if (shouldClean) {
            performOverkillClean(reason);
        }
    }

    private void performOverkillClean(String reason) {
        try {
            Image.clearCache();
            Font.clearCache();
        } catch (Throwable ignored) {}

        new Thread(() -> {
            try {
                Graphics3D.getInstance().releaseTarget();
            } catch (Throwable ignored) {}

            try {
                getApplication().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
                System.runFinalization();
                System.gc();
                handler.post(() -> {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    lastGcStatus = "[Đã dọn " + sdf.format(new Date()) + "]";
                    updateNotification();
                });
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi dọn dẹp: " + e.getMessage());
            }
        }, "MemoryCleaner").start();
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

        Runtime runtime = Runtime.getRuntime();
        long heapMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long nativeMB = Debug.getNativeHeapAllocatedSize() / (1024 * 1024);
        long appRamMB = heapMB + nativeMB;
        lastAppRamMB = appRamMB;

        checkForceGC(appRamMB);

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
        lastCpuPercent = cpuUsage;

        String title = String.format(Locale.getDefault(), "RAM: %d/%d MB | App: %d MB", usedRamMB, totalRamMB, appRamMB);
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
        acquireLocks();
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
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        acquireLocks();
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

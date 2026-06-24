package xyz.lovestyle.home;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;

public class ForegroundService extends Service {

    private static final String CHANNEL_ID  = "elpis_keepalive";
    private static final int    NOTIF_ID    = 9001;
    private static final String GEO_URL     = "https://love-style.xyz/api/geo/report";
    private static final long   INTERVAL_MS = 10 * 60 * 1000L;

    private Timer locationTimer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Elpis")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build();
        startForeground(NOTIF_ID, notif);
        startLocationReporting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // 用户划掉最近任务时：1秒后重启 service
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PendingIntent pending = PendingIntent.getService(
                this, 0,
                new Intent(this, ForegroundService.class),
                PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000L, pending);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationTimer != null) locationTimer.cancel();
    }

    private void startLocationReporting() {
        locationTimer = new Timer("geo-report", true);
        locationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { reportLocation(); }
        }, 0, INTERVAL_MS);
    }

    private void reportLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;

        Location loc = null;
        try {
            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            // 取时间戳更新的那个
            if (gps != null && net != null) {
                loc = gps.getTime() >= net.getTime() ? gps : net;
            } else {
                loc = gps != null ? gps : net;
            }
        } catch (SecurityException e) {
            return; // 权限未授予，跳过
        }

        if (loc == null) return;
        postGeo(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
    }

    private void postGeo(double lat, double lon, float accuracy) {
        new Thread(() -> {
            try {
                URL url = new URL(GEO_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                byte[] body = String.format(java.util.Locale.US,
                        "{\"lat\":%.6f,\"lon\":%.6f,\"accuracy\":%.1f}",
                        lat, lon, accuracy).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
                conn.getInputStream().close();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Elpis 后台", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持应用后台运行");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}

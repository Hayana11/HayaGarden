package xyz.lovestyle.home;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import org.json.JSONObject;

public class ForegroundService extends Service {

    private static final String CHANNEL_ID   = "elpis_keepalive";
    private static final int    NOTIF_ID     = 9001;
    private static final String GEO_URL      = "https://love-style.xyz/api/geo/report";
    private static final String DEVICE_URL   = "https://love-style.xyz/api/device/report";
    // 推送长轮询：?wait=N 让后端最多挂 N 秒等消息，实现"前端一发、手机就弹"
    private static final String PUSH_URL     = "https://love-style.xyz/api/wake_log/pending_notification";
    private static final int    PUSH_WAIT_S  = 25;
    private static final long   INTERVAL_MS  = 10 * 60 * 1000L;

    private Timer locationTimer;
    private volatile boolean pushRunning = false;
    private Thread pushThread;

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
        startForegroundCompat(notif);
        startLocationReporting();
        startPushLoop();
    }

    private void startForegroundCompat(Notification notif) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

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
        pushRunning = false;
        if (pushThread != null) pushThread.interrupt();
    }

    // ── 持久推送长轮询 ─────────────────────────────────────────
    // 常驻一条 HTTP 长轮询：后端有新消息就立即返回并弹通知（秒级），
    // 没消息则挂到 PUSH_WAIT_S 秒后返回空、马上再轮。WorkManager 的 15min
    // 轮询是这条通道被系统杀掉时的兜底。两条通道共用 NotificationWorker 的去重。
    private void startPushLoop() {
        if (pushRunning) return;
        pushRunning = true;
        pushThread = new Thread(() -> {
            SharedPreferences sp = getSharedPreferences("elpis_push", MODE_PRIVATE);
            int backoff = 0;
            while (pushRunning) {
                try {
                    String lastId = sp.getString("last_notif_id", "");
                    String url = PUSH_URL + "?wait=" + PUSH_WAIT_S
                            + "&since=" + URLEncoder.encode(lastId, "UTF-8");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout((PUSH_WAIT_S + 15) * 1000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder b = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) b.append(l);
                    r.close();
                    c.disconnect();
                    JSONObject j = new JSONObject(b.toString());
                    String cmd = NotificationWorker.showMessage(getApplicationContext(), j);
                    if ("screenshot".equals(cmd)) {
                        ScreenCaptureService.requestCapture(getApplicationContext());
                    }
                    backoff = 0;
                    // 兜底：若后端不支持 wait 会立即返回，睡 3s 再轮，避免空转打爆服务器
                    try { Thread.sleep(3000L); } catch (InterruptedException ie) { break; }
                } catch (Exception e) {
                    // 网络抖动/断线：指数退避，最长 30s，避免空转烧电
                    backoff = Math.min(backoff == 0 ? 2 : backoff * 2, 30);
                    try { Thread.sleep(backoff * 1000L); } catch (InterruptedException ie) { break; }
                }
            }
        }, "elpis-push");
        pushThread.setDaemon(true);
        pushThread.start();
    }

    private void startLocationReporting() {
        locationTimer = new Timer("geo-report", true);
        locationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                reportLocation();
                reportDevice();
            }
        }, 0, INTERVAL_MS);
    }

    // ── 位置上报 ──────────────────────────────────────────────
    private void reportLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        Location loc = null;
        try {
            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null && net != null) {
                loc = gps.getTime() >= net.getTime() ? gps : net;
            } else {
                loc = gps != null ? gps : net;
            }
        } catch (SecurityException e) { return; }
        if (loc == null) return;
        postJson(GEO_URL, String.format(java.util.Locale.US,
                "{\"lat\":%.6f,\"lon\":%.6f,\"accuracy\":%.1f}",
                loc.getLatitude(), loc.getLongitude(), loc.getAccuracy()));
    }

    // ── 电量 + 今日屏幕时间上报 ───────────────────────────────
    private void reportDevice() {
        // 电量
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int pct = -1, charging = 0;
        String chargeType = "none";
        double tempC = -1;
        if (bi != null) {
            int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int temp   = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            pct = scale > 0 ? (level * 100 / scale) : -1;
            charging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                     || status == BatteryManager.BATTERY_STATUS_FULL) ? 1 : 0;
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC)           chargeType = "ac";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_USB)     chargeType = "usb";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) chargeType = "wireless";
            tempC = temp >= 0 ? temp / 10.0 : -1;
        }

        // 今日屏幕总时长（分钟）
        long screenMinutes = -1;
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm != null) {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                List<UsageStats> stats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        cal.getTimeInMillis(), System.currentTimeMillis());
                if (stats != null) {
                    long totalMs = 0;
                    for (UsageStats s : stats) totalMs += s.getTotalTimeInForeground();
                    screenMinutes = totalMs / 60000L;
                }
            }
        } catch (Exception ignored) {}

        postJson(DEVICE_URL, String.format(java.util.Locale.US,
                "{\"battery_percent\":%d,\"battery_charging\":%d,\"charge_type\":\"%s\","
                + "\"temp_c\":%.1f,\"screen_today_minutes\":%d}",
                pct, charging, chargeType, tempC, screenMinutes));
    }

    private void postJson(String urlStr, String body) {
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
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

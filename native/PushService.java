package xyz.lovestyle.home.canary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/**
 * P2D: one held-open HTTP long poll so wake messages reach the phone within
 * about a second. The 15-minute NotificationPollWorker remains the fallback
 * when the system kills this service; both read the same server-side
 * delivered flag, so a message is shown once.
 */
public class PushService extends Service {
    static final String CHANNEL_ID = "elpis_push_keepalive";
    private static final String CHANNEL_NAME = "Elpis 在线";
    private static final int ONGOING_ID = 0xE1F16;
    private static final int WAIT_S = 25;
    // A server that ignores ?wait answers at once; never re-ask faster than this.
    private static final long MIN_CYCLE_MS = 3000L;
    private static final int MAX_BACKOFF_S = 60;

    private volatile boolean running;
    private Thread loop;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification ongoing = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_elpis_notification_cat)
                .setContentTitle(CHANNEL_NAME)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ONGOING_ID, ongoing,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(ONGOING_ID, ongoing);
        }
        startLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startLoop();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
        super.onDestroy();
    }

    private synchronized void startLoop() {
        if (running) {
            return;
        }
        running = true;
        loop = new Thread(this::runLoop, "elpis-push");
        loop.setDaemon(true);
        loop.start();
    }

    private void runLoop() {
        int backoffS = 0;
        while (running) {
            long started = SystemClock.elapsedRealtime();
            try {
                JSONObject payload = fetchOnce();
                backoffS = 0;
                if (payload.optBoolean("has_message", false)) {
                    String content = payload.optString("content", "");
                    if (!content.trim().isEmpty()) {
                        NotificationSupport.showNotification(
                                getApplicationContext(),
                                payload.optString("title", ""),
                                content,
                                payload.optString("id", ""));
                    }
                }
                long elapsed = SystemClock.elapsedRealtime() - started;
                if (elapsed < MIN_CYCLE_MS) {
                    Thread.sleep(MIN_CYCLE_MS - elapsed);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                backoffS = Math.min(backoffS == 0 ? 2 : backoffS * 2, MAX_BACKOFF_S);
                try {
                    Thread.sleep(backoffS * 1000L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private JSONObject fetchOnce() throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(NotificationSupport.API + "?wait=" + WAIT_S);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout((WAIT_S + 15) * 1000);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("push poll http " + status);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            return new JSONObject(body.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}

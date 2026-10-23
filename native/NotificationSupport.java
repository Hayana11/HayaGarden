package xyz.lovestyle.home.canary;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** The sole authority for P2B notification capability, display, and polling. */
public final class NotificationSupport {
    public static final String CHANNEL_ID = "fyodor_msg";
    public static final String CHANNEL_NAME = "费奥多尔的消息";
    public static final String WORK_NAME = "poll_fyodor";
    public static final String API =
            "https://love-style.xyz/api/wake_log/pending_notification";
    public static final String CHAT_URL = "https://love-style.xyz/dash/chat";
    public static final String EXTRA_OPEN_CHAT = "elpis.notification.open_chat";
    public static final int REQUEST_NOTIFICATION_PERMISSION = 2001;
    private static final String DEFAULT_TITLE = "费奥多尔";
    public static final String ACK_API =
            "https://love-style.xyz/api/wake_log/ack";
    private static final String DELIVERY_PREFS = "elpis_delivery";
    private static final String KEY_SHOWN_IDS = "shown_ids";
    private static final String KEY_CURSOR = "wake_cursor";

    /** Outcome of handing one delivered payload to the device. */
    public enum Delivery { SHOWN, ALREADY_SHOWN, NOT_SHOWN, NOTHING }

    private NotificationSupport() {}

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(channel);
    }

    /** Read-only eligibility check: it never triggers a permission prompt. */
    public static boolean canPostNotifications(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel(ctx);
            NotificationManager manager =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return false;
            }
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            return channel != null
                    && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    public static void ensurePollingScheduled(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                NotificationPollWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work);
        // P2D (owner-approved 2026-10-23): seconds-fast delivery on top of the worker.
        PushSupport.ensureRunning(ctx);
    }

    public static boolean showNotification(
            Context ctx, String title, String body, String messageId) {
        if (!canPostNotifications(ctx)) {
            return false;
        }
        Intent intent = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_CHAT, true);
        PendingIntent tapIntent = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String displayTitle = title == null || title.trim().isEmpty()
                ? DEFAULT_TITLE : title;
        String displayBody = body == null ? "" : body;
        if (displayBody.length() > 160) {
            displayBody = displayBody.substring(0, 160) + "…";
        }
        int notificationId = messageId == null || messageId.isEmpty()
                ? 0xE1F15 : messageId.hashCode();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_elpis_notification_cat)
                .setContentTitle(displayTitle)
                .setContentText(displayBody)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(displayBody))
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        NotificationManagerCompat.from(ctx).notify(notificationId, builder.build());
        return true;
    }

    /**
     * Ack-protocol poll URL. since is the highest wake id this device has shown,
     * which lets the server recover an ack lost before a reboot.
     */
    public static String pollUrl(Context ctx, int waitSeconds) {
        long cursor = prefs(ctx).getLong(KEY_CURSOR, 0L);
        return API + "?protocol=ack&wait=" + waitSeconds + "&since=" + cursor;
    }

    /**
     * Show a pending-notification payload at most once per id on this device,
     * then acknowledge it. Shared by NotificationPollWorker and PushService.
     */
    public static Delivery handlePayload(Context ctx, JSONObject payload) {
        if (payload == null || !payload.optBoolean("has_message", false)) {
            return Delivery.NOTHING;
        }
        String id = payload.optString("id", "");
        String content = payload.optString("content", "");
        if (id.isEmpty() || content.trim().isEmpty()) {
            return Delivery.NOTHING;
        }
        Delivery result = showOnce(ctx, id, payload.optString("title", ""), content);
        if (result != Delivery.NOT_SHOWN) {
            ack(id);
        }
        return result;
    }

    static Delivery showOnce(Context ctx, String id, String title, String content) {
        final SharedPreferences sp = prefs(ctx);
        DeliveryLedger.Result result = DeliveryLedger.deliver(
                new DeliveryLedger.Store() {
                    @Override public String readShownIds() {
                        return sp.getString(KEY_SHOWN_IDS, "");
                    }
                    @Override public long readCursor() {
                        return sp.getLong(KEY_CURSOR, 0L);
                    }
                    @Override public void write(String shownIds, long cursor) {
                        sp.edit()
                                .putString(KEY_SHOWN_IDS, shownIds)
                                .putLong(KEY_CURSOR, cursor)
                                .commit();
                    }
                },
                id,
                () -> showNotification(ctx, title, content, id));
        switch (result) {
            case SHOWN: return Delivery.SHOWN;
            case ALREADY_SHOWN: return Delivery.ALREADY_SHOWN;
            default: return Delivery.NOT_SHOWN;
        }
    }

    /** Best effort and idempotent server-side; a lost ack is recovered by the since cursor. */
    public static void ack(String id) {
        HttpURLConnection connection = null;
        try {
            byte[] body = new JSONObject()
                    .put("ids", new JSONArray(Arrays.asList(id)))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(ACK_API).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE);
    }
}

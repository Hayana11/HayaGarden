package xyz.lovestyle.home.canary;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

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
}

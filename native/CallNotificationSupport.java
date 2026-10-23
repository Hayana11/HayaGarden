package xyz.lovestyle.home.canary;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONObject;

/**
 * His incoming call, driven by the long poll's call delta (see CallState).
 * Not a delivery item: no DeliveryLedger, no ack. One open call at a time, so
 * one fixed notification id: a repeat updates it in place, "call": null
 * cancels it. Its own channel, because a channel's importance, sound and
 * vibration cannot be changed after it exists (v1 rang once, so v2 replaces it).
 *
 * It rings like a call: the ringtone and vibration repeat (FLAG_INSISTENT)
 * until the notification goes away: "call": null when the call is answered,
 * declined or missed, a tap that opens the call page, or at the latest
 * RING_CAP_MS, so a phone that misses the withdrawal never rings on and on.
 */
public final class CallNotificationSupport {
    public static final String CHANNEL_ID = "fyodor_call_v2";
    static final String RETIRED_CHANNEL_ID = "fyodor_call_v1";
    /** Server rings for 60 s (voice_call.store.RING_TIMEOUT_S); a little slack, then stop. */
    static final long RING_CAP_MS = 65_000L;
    private static final long[] VIBRATION = {0, 1000, 800, 1000, 800};
    public static final String CHANNEL_NAME = "费奥多尔来电";
    public static final int NOTIFICATION_ID = 0xCA11;
    public static final String CALL_URL = "https://love-style.xyz/dash/call";
    public static final String EXTRA_OPEN_CALL = "elpis.notification.open_call";
    private static final String DEFAULT_TITLE = "费佳来电";
    private static final int TAP_REQUEST_CODE = 0xCA11;  // distinct from message taps

    private CallNotificationSupport() {}

    /** The known_call query part for a poll sent with this snapshot ("" when no call is known). */
    public static String pollQuery(CallState.Snapshot call) {
        String known = call == null ? null : call.known;
        return known == null ? "" : "&known_call=" + known;
    }

    /**
     * Apply a poll response's call delta, fenced by the snapshot its request was
     * sent with. Independent of its message, which is handled as before even
     * when the call delta is stale.
     */
    public static void handleDelta(Context ctx, JSONObject payload, CallState.Snapshot sentWith) {
        if (payload == null || !payload.has("call")) {
            return;
        }
        String id = null;
        String title = DEFAULT_TITLE;
        if (!payload.isNull("call")) {
            JSONObject call = payload.optJSONObject("call");
            if (call == null) {
                return;
            }
            id = call.optString("id", "");
            String given = call.optString("title", "");
            if (!given.trim().isEmpty()) {
                title = given;
            }
        }
        switch (CallState.apply(sentWith, true, id)) {
            case SHOW:
                show(ctx, title);
                break;
            case CANCEL:
                NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
                break;
            default:
                break;
        }
    }

    static void createChannel(Context ctx) {
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
        channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        channel.enableVibration(true);
        channel.setVibrationPattern(VIBRATION);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
        manager.deleteNotificationChannel(RETIRED_CHANNEL_ID);
    }

    /** She opened the call page from the notification: stop ringing. */
    public static void stopRinging(Context ctx) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
    }

    private static boolean canPost(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    private static void show(Context ctx, String title) {
        if (!canPost(ctx)) {
            return;
        }
        createChannel(ctx);
        Intent intent = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_CALL, true);
        PendingIntent tapIntent = PendingIntent.getActivity(
                ctx, TAP_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_elpis_notification_cat)
                .setContentTitle(title)
                .setContentText("点开接听")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setTimeoutAfter(RING_CAP_MS)
                .setContentIntent(tapIntent);
        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_INSISTENT;  // ring and vibrate until it goes away
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification);
    }
}

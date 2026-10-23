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
import org.json.JSONObject;

/**
 * His incoming call, driven by the long poll's call delta (see CallState).
 * Not a delivery item: no DeliveryLedger, no ack. One open call at a time, so
 * one fixed notification id: a repeat updates it in place, "call": null
 * cancels it. Its own channel, because a channel's importance cannot be raised
 * after it exists.
 */
public final class CallNotificationSupport {
    public static final String CHANNEL_ID = "fyodor_call_v1";
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
    public static void handleDelta(Context ctx, JSONObject payload, CallState.Snapshot call) {
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
        switch (CallState.apply(call, true, id)) {
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
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH));
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
                .setContentIntent(tapIntent);
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build());
    }
}

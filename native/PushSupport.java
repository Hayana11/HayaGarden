package xyz.lovestyle.home.canary;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

/** P2D: the only place that starts the push long-poll service. */
public final class PushSupport {
    private PushSupport() {}

    public static void ensureRunning(Context ctx) {
        if (!NotificationSupport.canPostNotifications(ctx)) {
            return;
        }
        try {
            ContextCompat.startForegroundService(
                    ctx.getApplicationContext(),
                    new Intent(ctx.getApplicationContext(), PushService.class));
        } catch (RuntimeException ignored) {
            // Android 12+ refuses some background starts; the 15-minute worker
            // still delivers, and the next foreground start brings the service back.
        }
    }
}

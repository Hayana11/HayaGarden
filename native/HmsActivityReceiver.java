package xyz.lovestyle.home.canary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit PendingIntent target for HMS Activity Identification updates. */
public final class HmsActivityReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null
                && HmsActivityStore.ACTION_ACTIVITY_IDENTIFICATION.equals(intent.getAction())) {
            HmsActivityStore.handleIntent(context, intent);
        }
    }
}

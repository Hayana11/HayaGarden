package xyz.lovestyle.home.canary;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

/** Explicit JS-only notification permission and local diagnostic bridge. */
public final class NotificationBridge {
    private final MainActivity activity;

    public NotificationBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public boolean hasNotificationPermission() {
        return NotificationSupport.canPostNotifications(activity);
    }

    @JavascriptInterface
    public void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationSupport.createChannel(activity);
            NotificationSupport.ensurePollingScheduled(activity);
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            NotificationSupport.ensurePollingScheduled(activity);
            return;
        }
        activity.runOnUiThread(() -> activity.requestPermissions(
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                NotificationSupport.REQUEST_NOTIFICATION_PERMISSION));
    }

    @JavascriptInterface
    public boolean showTestNotification() {
        if (!NotificationSupport.canPostNotifications(activity)) {
            return false;
        }
        return NotificationSupport.showNotification(
                activity, "Elpis Canary", "原生通知连接正常", "elpis-test");
    }
}

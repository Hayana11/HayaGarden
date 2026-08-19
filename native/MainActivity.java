package xyz.lovestyle.home.canary;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

/**
 * Phase 2C.1 Canary activity: P1B back semantics, P2A ElpisNative,
 * P2B notifications, and the foreground-live P2C.1 physical cache.
 */
public class MainActivity extends BridgeActivity {
    private PhysicalStateStore physicalStateStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        physicalStateStore = new PhysicalStateStore(getApplicationContext());

        Bridge bridge = getBridge();
        WebView webView = bridge != null ? bridge.getWebView() : null;
        if (webView != null) {
            webView.addJavascriptInterface(
                new NativeBridge(getApplicationContext()),
                "ElpisNative"
            );
            webView.addJavascriptInterface(
                new NotificationBridge(this),
                "ElpisNotifications"
            );
            webView.addJavascriptInterface(
                new PhysicalBridge(physicalStateStore),
                "ElpisPhysical"
            );
        }

        NotificationSupport.createChannel(this);
        if (NotificationSupport.canPostNotifications(this)) {
            NotificationSupport.ensurePollingScheduled(this);
        }
        handleNotificationIntent(getIntent());

        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    Bridge b = getBridge();
                    WebView wv = b != null ? b.getWebView() : null;
                    if (wv != null && wv.canGoBack()) {
                        wv.goBack();
                        return;
                    }
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (physicalStateStore != null) {
            physicalStateStore.start();
        }
    }

    @Override
    protected void onPause() {
        if (physicalStateStore != null) {
            physicalStateStore.stop();
        }
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NotificationSupport.REQUEST_NOTIFICATION_PERMISSION
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
            NotificationSupport.ensurePollingScheduled(this);
        }
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(
                NotificationSupport.EXTRA_OPEN_CHAT, false)) {
            return;
        }
        intent.removeExtra(NotificationSupport.EXTRA_OPEN_CHAT);
        Bridge bridge = getBridge();
        WebView webView = bridge != null ? bridge.getWebView() : null;
        if (webView != null) {
            webView.post(() -> webView.loadUrl(NotificationSupport.CHAT_URL));
        }
    }
}

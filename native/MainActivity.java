package xyz.lovestyle.home.canary;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.health.connect.client.PermissionController;
import java.util.Set;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int REQUEST_HMS_ACTIVITY_PERMISSION = 19042;
    private PhysicalStateStore physicalStateStore;
    private HmsActivityStore hmsActivityStore;
    private HealthStateStore healthStateStore;
    private ActivityResultLauncher<Set<String>> healthPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        healthPermissionLauncher = registerForActivityResult(
                PermissionController.createRequestPermissionResultContract(),
                granted -> {
                    if (healthStateStore != null) {
                        healthStateStore.getHealthStatus();
                    }
                }
        );

        physicalStateStore = new PhysicalStateStore(getApplicationContext());
        hmsActivityStore = new HmsActivityStore(getApplicationContext());
        healthStateStore = new HealthStateStore(getApplicationContext());

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
            webView.addJavascriptInterface(
                new HmsActivityBridge(hmsActivityStore),
                "ElpisActivity"
            );
            webView.addJavascriptInterface(
                new HealthBridge(this, healthStateStore),
                "ElpisHealth"
            );
        }

        HealthSupport.ensureScheduled(this);
        NotificationSupport.createChannel(this);
        if (NotificationSupport.canPostNotifications(this)) {
            NotificationSupport.ensurePollingScheduled(this);
        }
        handleNotificationIntent(getIntent());
        requestHmsActivityPermissionIfNeeded();

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
    public void onResume() {
        super.onResume();
        if (physicalStateStore != null) {
            physicalStateStore.start();
        }
        if (hmsActivityStore != null) {
            hmsActivityStore.startIfPermitted();
        }
    }

    @Override
    public void onPause() {
        if (physicalStateStore != null) {
            physicalStateStore.stop();
        }
        // HMS updates intentionally remain registered while backgrounded.
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
        if (requestCode == REQUEST_HMS_ACTIVITY_PERMISSION
                && hmsActivityStore != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            hmsActivityStore.startIfPermitted();
        }
        if (requestCode == NotificationSupport.REQUEST_NOTIFICATION_PERMISSION
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
            NotificationSupport.ensurePollingScheduled(this);
        }
    }

    public void requestHealthConnectPermissions() {
        if (healthPermissionLauncher == null) return;

        Set<String> permissions =
                HealthConnectReader.permissionsForRequest(getApplicationContext());
        runOnUiThread(() -> {
            try {
                healthPermissionLauncher.launch(permissions);
            } catch (Exception ignored) {
            }
        });
    }

    private void requestHmsActivityPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (hmsActivityStore != null) {
                hmsActivityStore.startIfPermitted();
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                    REQUEST_HMS_ACTIVITY_PERMISSION
            );
            return;
        }
        if (hmsActivityStore != null) {
            hmsActivityStore.startIfPermitted();
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

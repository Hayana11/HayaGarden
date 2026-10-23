package xyz.lovestyle.home.canary;

import android.Manifest;
import android.graphics.Color;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
    private SpeechBridge speechBridge;
    private VoiceBridge voiceBridge;

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
        configureTopStatusBar();

        physicalStateStore = new PhysicalStateStore(getApplicationContext());
        hmsActivityStore = new HmsActivityStore(getApplicationContext());
        healthStateStore = new HealthStateStore(getApplicationContext());

        Bridge bridge = getBridge();
        WebView webView = bridge != null ? bridge.getWebView() : null;
        if (webView != null) {
            configureImeResize(webView);
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
            webView.addJavascriptInterface(
                new InsetsBridge(getApplicationContext(), webView),
                "ElpisInsets"
            );
            speechBridge = new SpeechBridge(this, webView);
            webView.addJavascriptInterface(speechBridge, "ElpisSpeech");
            voiceBridge = new VoiceBridge(this, webView);
            webView.addJavascriptInterface(voiceBridge, "ElpisVoice");
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

    /**
     * Shrink the native container that owns the WebView when the IME opens.
     * This changes the WebView viewport so fixed-position frontend elements
     * remain above the keyboard without a frontend keyboard-height workaround.
     * Navigation-bar safe area remains owned by the WebView/frontend.
     */
    private void configureImeResize(WebView webView) {
        final ViewGroup imeContainer = webView.getParent() instanceof ViewGroup
                ? (ViewGroup) webView.getParent()
                : webView;
        final int baseBottomPadding = imeContainer.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(imeContainer, (view, insets) -> {
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
            int imeBottom = insets.isVisible(WindowInsetsCompat.Type.ime())
                    ? imeInsets.bottom
                    : 0;
            int targetBottomPadding = baseBottomPadding + imeBottom;

            if (view.getPaddingBottom() != targetBottomPadding) {
                view.setPadding(
                        view.getPaddingLeft(),
                        view.getPaddingTop(),
                        view.getPaddingRight(),
                        targetBottomPadding
                );
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(imeContainer);
    }
    private void configureTopStatusBar() {
        Window window = getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);

        int flags = window.getDecorView().getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
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
        if (speechBridge != null) {
            speechBridge.resumed();
        }
        if (voiceBridge != null) {
            voiceBridge.resumed();
        }
    }

    @Override
    public void onPause() {
        if (physicalStateStore != null) {
            physicalStateStore.stop();
        }
        if (speechBridge != null) {
            speechBridge.release();
        }
        if (voiceBridge != null) {
            voiceBridge.release();
        }
        // HMS updates intentionally remain registered while backgrounded.
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (voiceBridge != null) {
            voiceBridge.close();
        }
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
        if (speechBridge != null) {
            speechBridge.onPermissionResult(requestCode, grantResults);
        }
        if (voiceBridge != null) {
            voiceBridge.onPermissionResult(requestCode, grantResults);
        }
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

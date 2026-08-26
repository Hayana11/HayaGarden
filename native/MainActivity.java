package xyz.lovestyle.home.canary;

import android.Manifest;
import android.graphics.Color;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

        configureTopStatusBar();

        physicalStateStore = new PhysicalStateStore(getApplicationContext());

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
                new InsetsBridge(getApplicationContext(), webView),
                "ElpisInsets"
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

    /**
     * Keep the WebView content viewport above the IME without changing the
     * frontend composer or bottom-navigation contract.
     * Navigation-bar safe area remains owned by the WebView/frontend.
     */
    private void configureImeResize(WebView webView) {
        final int baseBottomPadding = webView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
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
        ViewCompat.requestApplyInsets(webView);
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
    }

    @Override
    public void onPause() {
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

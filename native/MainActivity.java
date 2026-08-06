package xyz.lovestyle.home.canary;

import android.os.Bundle;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

/**
 * Phase 1B canary MainActivity — Capacitor default shell plus minimal Android back:
 * WebView history → goBack(); otherwise → default Activity back (exit/leave).
 *
 * No services, bridges, textZoom, captureInput, or URL reloads on lifecycle.
 */
public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    Bridge bridge = getBridge();
                    WebView webView = bridge != null ? bridge.getWebView() : null;
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                        return;
                    }
                    // No WebView history — hand off to the system back stack.
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        );
    }
}

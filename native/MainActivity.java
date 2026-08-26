package xyz.lovestyle.home.canary;

import android.Manifest;
import android.graphics.Color;
import android.graphics.Rect;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
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
    private static final String IME_DIAGNOSTICS_TAG = "ElpisImeDiag";
    private PhysicalStateStore physicalStateStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureTopStatusBar();

        physicalStateStore = new PhysicalStateStore(getApplicationContext());

        Bridge bridge = getBridge();
        WebView webView = bridge != null ? bridge.getWebView() : null;
        if (webView != null) {
            webView.addJavascriptInterface(
                new ImeDiagnosticsBridge(),
                "ElpisImeDiagnostics"
            );
            configureImeResize(webView);
            scheduleJavascriptImeDiagnostics(webView);
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
     * Shrink the native container that owns the WebView when the IME opens.
     * This existing behavior is left unchanged; the surrounding diagnostics
     * only record the resulting native and JS bounds for a real device run.
     */
    private void configureImeResize(WebView webView) {
        final ViewGroup imeContainer = webView.getParent() instanceof ViewGroup
                ? (ViewGroup) webView.getParent()
                : webView;
        final int baseBottomPadding = imeContainer.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(imeContainer, (view, insets) -> {
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            int imeBottom = imeVisible ? imeInsets.bottom : 0;
            int targetBottomPadding = baseBottomPadding + imeBottom;

            if (view.getPaddingBottom() != targetBottomPadding) {
                view.setPadding(
                        view.getPaddingLeft(),
                        view.getPaddingTop(),
                        view.getPaddingRight(),
                        targetBottomPadding
                );
            }

            String state = imeVisible ? "OPEN" : "CLOSED";
            logImeMetrics(webView, imeContainer, insets, state);
            captureJavascriptViewport(webView, state);
            return insets;
        });
        ViewCompat.requestApplyInsets(imeContainer);
    }

    private void logImeMetrics(
            WebView webView,
            ViewGroup imeContainer,
            WindowInsetsCompat insets,
            String state) {
        View decorView = getWindow().getDecorView();
        View contentView = findViewById(android.R.id.content);
        ViewParent directParent = webView.getParent();
        ViewGroup webViewParent = directParent instanceof ViewGroup
                ? (ViewGroup) directParent
                : null;
        Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
        boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
        Rect visibleFrame = new Rect();
        decorView.getWindowVisibleDisplayFrame(visibleFrame);

        Log.i(
                IME_DIAGNOSTICS_TAG,
                "NATIVE state=" + state
                        + " decorHeight=" + decorView.getHeight()
                        + " contentMeasuredHeight=" + measuredHeight(contentView)
                        + " contentHeight=" + height(contentView)
                        + " webViewParentClass=" + className(webViewParent)
                        + " webViewParentMeasuredHeight=" + measuredHeight(webViewParent)
                        + " webViewParentHeight=" + height(webViewParent)
                        + " webViewParentPaddingBottom=" + paddingBottom(webViewParent)
                        + " webViewMeasuredHeight=" + webView.getMeasuredHeight()
                        + " webViewHeight=" + webView.getHeight()
                        + " webViewPaddingBottom=" + webView.getPaddingBottom()
                        + " imeVisible=" + imeVisible
                        + " imeBottom=" + imeInsets.bottom
                        + " visibleFrameTop=" + visibleFrame.top
                        + " visibleFrameBottom=" + visibleFrame.bottom
                        + " visibleFrameHeight=" + visibleFrame.height()
        );
        Log.i(IME_DIAGNOSTICS_TAG, "VIEW_HIERARCHY state=" + state + " "
                + viewHierarchy(webView));
    }

    private void scheduleJavascriptImeDiagnostics(WebView webView) {
        webView.postDelayed(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (webView.getUrl() != null) {
                    captureJavascriptViewport(webView, "CLOSED");
                    return;
                }
                if (attempts++ < 12) {
                    webView.postDelayed(this, 1000L);
                }
            }
        }, 1500L);
    }

    private void captureJavascriptViewport(WebView webView, String state) {
        String jsState = state.replace("\\", "\\\\").replace("'", "\\'");
        String script =
                "(function(){"
                + "var snapshot=function(s){"
                + "var root=document.documentElement;"
                + "var vv=window.visualViewport;"
                + "var payload={state:s,"
                + "windowInnerHeight:window.innerHeight,"
                + "documentClientHeight:root?root.clientHeight:null,"
                + "visualViewportHeight:vv?vv.height:null,"
                + "visualViewportOffsetTop:vv?vv.offsetTop:null};"
                + "try{console.log('[ElpisImeDiag] WEB_JS '+JSON.stringify(payload));}catch(e){}"
                + "try{if(window.ElpisImeDiagnostics){"
                + "window.ElpisImeDiagnostics.record(JSON.stringify(payload));"
                + "}}catch(e){}"
                + "};"
                + "window.__elpisImeDiagSnapshot=snapshot;"
                + "if(!window.__elpisImeDiagListeners){"
                + "window.__elpisImeDiagListeners=true;"
                + "window.addEventListener('resize',function(){snapshot('RESIZE_EVENT');});"
                + "if(window.visualViewport){"
                + "window.visualViewport.addEventListener('resize',function(){snapshot('VISUAL_VIEWPORT_RESIZE');});"
                + "window.visualViewport.addEventListener('scroll',function(){snapshot('VISUAL_VIEWPORT_SCROLL');});"
                + "}}"
                + "snapshot('" + jsState + "');"
                + "})()";
        webView.evaluateJavascript(script, null);
    }

    private static String viewHierarchy(View leaf) {
        StringBuilder result = new StringBuilder();
        View current = leaf;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (result.length() > 0) {
                result.append(" <- ");
            }
            result.append(viewMetrics(current));
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return result.toString();
    }

    private static String viewMetrics(View view) {
        if (view == null) {
            return "null";
        }
        return view.getClass().getName()
                + "{measuredHeight=" + view.getMeasuredHeight()
                + ",height=" + view.getHeight()
                + ",paddingBottom=" + view.getPaddingBottom() + "}";
    }

    private static String className(View view) {
        return view == null ? "null" : view.getClass().getName();
    }

    private static int measuredHeight(View view) {
        return view == null ? -1 : view.getMeasuredHeight();
    }

    private static int height(View view) {
        return view == null ? -1 : view.getHeight();
    }

    private static int paddingBottom(View view) {
        return view == null ? -1 : view.getPaddingBottom();
    }

    private static final class ImeDiagnosticsBridge {
        @JavascriptInterface
        public void record(String payload) {
            Log.i(IME_DIAGNOSTICS_TAG, "WEB_JS " + payload);
        }
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

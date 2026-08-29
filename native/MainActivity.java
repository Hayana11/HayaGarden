package xyz.lovestyle.home.canary;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int REQUEST_HMS_ACTIVITY_PERMISSION = 19042;
    private static final long HMS_DIAGNOSTIC_REFRESH_MS = 1_500L;
    private PhysicalStateStore physicalStateStore;
    private HmsActivityStore hmsActivityStore;
    private TextView hmsDiagnosticView;
    private final Runnable hmsDiagnosticRefresh = new Runnable() {
        @Override
        public void run() {
            if (hmsDiagnosticView == null) {
                return;
            }
            refreshHmsDiagnosticText();
            hmsDiagnosticView.postDelayed(this, HMS_DIAGNOSTIC_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        physicalStateStore = new PhysicalStateStore(getApplicationContext());
        hmsActivityStore = new HmsActivityStore(getApplicationContext());

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
        }

        NotificationSupport.createChannel(this);
        if (NotificationSupport.canPostNotifications(this)) {
            NotificationSupport.ensurePollingScheduled(this);
        }
        handleNotificationIntent(getIntent());
        installHmsDiagnosticOverlay();
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
        startHmsDiagnosticPolling();
    }

    @Override
    public void onPause() {
        stopHmsDiagnosticPolling();
        if (physicalStateStore != null) {
            physicalStateStore.stop();
        }
        // HMS updates intentionally remain registered while backgrounded.
        super.onPause();
    }

    @Override
    public void onDestroy() {
        stopHmsDiagnosticPolling();
        if (hmsDiagnosticView != null) {
            ViewGroup parent = (ViewGroup) hmsDiagnosticView.getParent();
            if (parent != null) {
                parent.removeView(hmsDiagnosticView);
            }
            hmsDiagnosticView = null;
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

    private boolean isDebugDiagnosticEnabled() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void installHmsDiagnosticOverlay() {
        if (!isDebugDiagnosticEnabled() || hmsDiagnosticView != null) {
            return;
        }

        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xB0000000);
        background.setCornerRadius(dp(8));
        view.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        params.setMargins(dp(8), dp(8), dp(8), 0);
        addContentView(view, params);
        hmsDiagnosticView = view;
        refreshHmsDiagnosticText();
    }

    private void startHmsDiagnosticPolling() {
        if (!isDebugDiagnosticEnabled() || hmsDiagnosticView == null) {
            return;
        }
        hmsDiagnosticView.removeCallbacks(hmsDiagnosticRefresh);
        refreshHmsDiagnosticText();
        hmsDiagnosticView.postDelayed(
                hmsDiagnosticRefresh,
                HMS_DIAGNOSTIC_REFRESH_MS
        );
    }

    private void stopHmsDiagnosticPolling() {
        if (hmsDiagnosticView != null) {
            hmsDiagnosticView.removeCallbacks(hmsDiagnosticRefresh);
        }
    }

    private void refreshHmsDiagnosticText() {
        if (hmsDiagnosticView == null) {
            return;
        }
        HmsActivityStore.State state = hmsActivityStore == null
                ? null
                : hmsActivityStore.snapshot();
        String registration = state == null || state.registrationState == null
                ? "failed"
                : state.registrationState;
        String lastError = state == null || state.lastErrorCode == null
                ? "--"
                : state.lastErrorCode;
        String callbackReceived = state != null && state.callbackReceived
                ? "yes"
                : "no";
        String intentHasExtras = state != null && state.intentHasExtras
                ? "yes"
                : "no";
        String responsePresent = state != null && state.responsePresent
                ? "yes"
                : "no";
        String activityDataCount = state == null
                ? "0"
                : Integer.toString(state.activityDataCount);
        String rawCandidate = state == null
                ? "-1"
                : Integer.toString(state.rawCandidate);
        String rawPossibility = state == null
                ? "-1"
                : Integer.toString(state.rawPossibility);
        if (hmsActivityStore == null || !hmsActivityStore.hasPermission()) {
            hmsDiagnosticView.setText(
                    "HMS: permission unavailable\n"
                            + "raw: UNKNOWN\n"
                            + "p: -1\n"
                            + "rawCandidate: " + rawCandidate + "\n"
                            + "rawPossibility: " + rawPossibility + "\n"
                            + "age: --\n"
                            + "source: none\n"
                            + "registration: " + registration + "\n"
                            + "lastErrorCode: " + lastError + "\n"
                            + "callbackReceived: " + callbackReceived + "\n"
                            + "intentHasExtras: " + intentHasExtras + "\n"
                            + "responsePresent: " + responsePresent + "\n"
                            + "activityDataCount: " + activityDataCount
            );
            return;
        }

        long now = System.currentTimeMillis();
        long ageMs = state.sampledAt > 0L ? now - state.sampledAt : -1L;
        boolean fresh = state.sampledAt > 0L
                && ageMs >= 0L
                && ageMs <= HmsActivityStore.MAX_AGE_MS;
        boolean valid = fresh && state.userActivity != null
                && !"unknown".equals(state.userActivity);
        String age = state.sampledAt > 0L && ageMs >= 0L
                ? Long.toString(ageMs / 1_000L) + "s"
                : "--";
        String activity = valid ? state.userActivity : "unknown";
        String raw = state.rawActivity == null ? "UNKNOWN" : state.rawActivity;
        String source = valid ? "hms" : "none";
        hmsDiagnosticView.setText(
                "HMS: " + activity + "\n"
                        + "raw: " + raw + "\n"
                        + "p: " + state.possibility + "\n"
                        + "rawCandidate: " + rawCandidate + "\n"
                        + "rawPossibility: " + rawPossibility + "\n"
                        + "age: " + age + "\n"
                        + "source: " + source + "\n"
                        + "registration: " + registration + "\n"
                        + "lastErrorCode: " + lastError + "\n"
                        + "callbackReceived: " + callbackReceived + "\n"
                        + "intentHasExtras: " + intentHasExtras + "\n"
                        + "responsePresent: " + responsePresent + "\n"
                        + "activityDataCount: " + activityDataCount
        );
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
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

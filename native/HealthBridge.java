package xyz.lovestyle.home.canary;

import android.webkit.JavascriptInterface;
import org.json.JSONObject;

/** Independent local health bridge; JS calls never perform network I/O. */
public final class HealthBridge {
    private final MainActivity activity;
    private final HealthStateStore store;

    public HealthBridge(MainActivity activity, HealthStateStore store) {
        this.activity = activity;
        this.store = store;
    }

    @JavascriptInterface
    public String getHealthState() {
        return store.getHealthState();
    }

    @JavascriptInterface
    public String getHealthStatus() {
        return store.getHealthStatus();
    }

    @JavascriptInterface
    public String syncNow() {
        try {
            HealthSupport.enqueueNow(activity.getApplicationContext());
            JSONObject result = new JSONObject();
            result.put("accepted", true);
            result.put("result", "queued");
            return result.toString();
        } catch (Exception ignored) {
            return "{\"accepted\":false,\"result\":\"unavailable\"}";
        }
    }

    /** Explicit user action only; reads never trigger a permission prompt. */
    @JavascriptInterface
    public String requestHealthConnectPermission() {
        activity.requestHealthConnectPermissions();
        return "{\"accepted\":true,\"result\":\"permission_request_started\"}";
    }
}

package xyz.lovestyle.home.canary;

import android.webkit.JavascriptInterface;
import org.json.JSONObject;

/** Independent local health bridge; JS calls never perform network I/O. */
public final class HealthBridge {
    private final MainActivity activity;
    private final HealthStateStore store;
    private final HealthCredentialStore credentialStore;

    public HealthBridge(MainActivity activity, HealthStateStore store) {
        this.activity = activity;
        this.store = store;
        this.credentialStore = new HealthCredentialStore(activity.getApplicationContext());
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
    public String getInstallId() {
        return credentialStore.getInstallId();
    }

    @JavascriptInterface
    public String provisionDeviceCredential(String jsonString) {
        try {
            JSONObject request = new JSONObject(jsonString == null ? "" : jsonString);
            String deviceId = request.optString("deviceId", "");
            String credential = request.optString("credential", "");
            if (deviceId.length() > 80 || credential.length() > 512
                    || deviceId.isEmpty() || credential.isEmpty()) {
                return "{\"ok\":false,\"configured\":false}";
            }
            if (!credentialStore.provision(deviceId, credential)) {
                return "{\"ok\":false,\"configured\":false}";
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("deviceId", credentialStore.getDeviceId());
            result.put("configured", true);
            return result.toString();
        } catch (Exception ignored) {
            return "{\"ok\":false,\"configured\":false}";
        }
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

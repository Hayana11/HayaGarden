package xyz.lovestyle.home.canary;

import android.webkit.JavascriptInterface;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only JS surface for independent HMS userActivity state. */
public final class HmsActivityBridge {
    private final HmsActivityStore store;

    public HmsActivityBridge(HmsActivityStore store) {
        this.store = store;
    }

    @JavascriptInterface
    public String getActivityState() {
        HmsActivityStore.State state = store.snapshot();
        long now = System.currentTimeMillis();
        long age = state.sampledAt <= 0L ? Long.MAX_VALUE : now - state.sampledAt;
        boolean fresh = age >= 0L && age <= HmsActivityStore.MAX_AGE_MS;
        try {
            JSONObject result = new JSONObject();
            result.put("schemaVersion", 1);
            result.put("available", fresh && !"unknown".equals(state.userActivity));
            result.put("userActivity", fresh ? state.userActivity : "unknown");
            result.put("rawActivity", state.rawActivity);
            result.put("activityPossibility", state.possibility);
            result.put(
                    "activitySampledAt",
                    state.sampledAt > 0L ? state.sampledAt : JSONObject.NULL
            );
            result.put("source", fresh ? "hms" : "none");
            return result.toString();
        } catch (JSONException impossible) {
            return "{\"schemaVersion\":1,\"available\":false,"
                    + "\"userActivity\":\"unknown\",\"source\":\"none\"}";
        }
    }
}

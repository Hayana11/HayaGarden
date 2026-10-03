package xyz.lovestyle.home.canary;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local-only Health Connect snapshot cache. Empty/denied/unavailable
 * collections update status while retaining the last good records.
 */
public final class HealthStateStore {
    private static final String PREFS = "elpis_health_bridge";
    private static final String KEY_STATE = "state";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RECORDS = 500;
    private static final int HEART_RATE_LIMIT = 300;
    private static final int RESTING_HEART_RATE_LIMIT = 30;
    private static final int STEPS_LIMIT = 100;
    private static final int SLEEP_LIMIT = 100;
    private final SharedPreferences prefs;
    private final HealthCredentialStore credentialStore;

    public HealthStateStore(Context context) {
        Context applicationContext = context.getApplicationContext();
        prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        credentialStore = new HealthCredentialStore(applicationContext);
    }

    public synchronized String getHealthState() {
        return readState().toString();
    }

    public synchronized String getHealthStatus() {
        JSONObject state = readState();
        try {
            JSONObject result = new JSONObject();
            result.put("schemaVersion", SCHEMA_VERSION);
            result.put("available", state.optBoolean("available", false));
            result.put("permission", state.optString("permission", "unknown"));
            result.put("permissionState", state.optJSONObject("permissionState"));
            result.put("enrollment", credentialStore.getEnrollmentState());
            result.put("providerStatus", state.optString("providerStatus", "UNAVAILABLE"));
            result.put("lastCollectedAt", nullable(state, "lastCollectedAt"));
            result.put("lastUploadAt", nullable(state, "lastUploadAt"));
            result.put("backgroundSync", state.optString("backgroundSync", "scheduled"));
            JSONObject metrics = new JSONObject();
            JSONObject statuses = state.optJSONObject("metricStatuses");
            JSONArray records = state.optJSONArray("records");
            for (String metric : new String[]{"heart_rate", "resting_heart_rate", "steps", "sleep"}) {
                JSONObject item = statuses == null ? null : statuses.optJSONObject(metric);
                if (item == null) item = new JSONObject();
                String status = item.optString("status", "UNAVAILABLE");
                String sampledAt = latestSampledAt(records, metric);
                JSONObject metricOut = new JSONObject();
                metricOut.put("available", "PASS".equals(status));
                metricOut.put("source", item.optString("source", "health_connect"));
                metricOut.put("sampledAt", sampledAt == null ? JSONObject.NULL : sampledAt);
                metricOut.put("stale", isStale(sampledAt));
                metricOut.put("status", status);
                metrics.put(metric, metricOut);
            }
            result.put("metrics", metrics);
            result.put("uploadStatus", state.optString("uploadStatus", "never"));
            result.put("uploadError", state.optString("uploadError", ""));
            return result.toString();
        } catch (Exception ignored) {
            return defaultState().toString();
        }
    }

    public synchronized boolean saveCollection(String payload) {
        try {
            JSONObject incoming = new JSONObject(payload);
            if (incoming.optInt("schemaVersion", -1) != SCHEMA_VERSION) return false;
            JSONArray records = incoming.optJSONArray("records");
            JSONObject statuses = incoming.optJSONObject("metricStatuses");
            if (records == null || statuses == null || records.length() > MAX_RECORDS) return false;
            if (count(records, "heart_rate") > HEART_RATE_LIMIT
                    || count(records, "resting_heart_rate") > RESTING_HEART_RATE_LIMIT
                    || count(records, "steps") > STEPS_LIMIT
                    || count(records, "sleep") > SLEEP_LIMIT) {
                return false;
            }

            JSONObject state = readState();
            state.put("schemaVersion", SCHEMA_VERSION);
            state.put("available", incoming.optBoolean("available", false));
            state.put("permission", incoming.optString("permission", "unknown"));
            state.put("providerStatus", incoming.optString("providerStatus", "UNAVAILABLE"));
            state.put("lastCollectedAt", incoming.optString("collectedAt", ""));
            state.put("backgroundSync", "scheduled");
            JSONObject permissionState = incoming.optJSONObject("permissionState");
            if (permissionState != null) state.put("permissionState", permissionState);
            state.put("metricStatuses", statuses);
            if (records.length() > 0 || !state.has("records")) state.put("records", records);
            prefs.edit().putString(KEY_STATE, state.toString()).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized void markUpload(String uploadedAt, boolean ok, String error) {
        JSONObject state = readState();
        try {
            if (ok) {
                state.put("lastUploadAt", uploadedAt);
                state.put("uploadStatus", "PASS");
                state.put("uploadError", "");
            } else {
                state.put("uploadStatus", "FAIL");
                state.put("uploadError", safeError(error));
            }
            prefs.edit().putString(KEY_STATE, state.toString()).apply();
        } catch (Exception ignored) {}
    }

    private JSONObject readState() {
        try {
            String raw = prefs.getString(KEY_STATE, null);
            if (raw != null) {
                JSONObject parsed = new JSONObject(raw);
                if (parsed.optInt("schemaVersion", SCHEMA_VERSION) == SCHEMA_VERSION) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {}
        return defaultState();
    }

    private static JSONObject defaultState() {
        JSONObject state = new JSONObject();
        try {
            state.put("schemaVersion", SCHEMA_VERSION);
            state.put("available", false);
            state.put("permission", "unknown");
            state.put("permissionState", new JSONObject()
                    .put("metrics", "unknown")
                    .put("backgroundRead", "unknown"));
            state.put("providerStatus", "UNAVAILABLE");
            state.put("backgroundSync", "scheduled");
            state.put("lastCollectedAt", JSONObject.NULL);
            state.put("lastUploadAt", JSONObject.NULL);
            state.put("uploadStatus", "never");
            state.put("uploadError", "");
            state.put("records", new JSONArray());
            JSONObject statuses = new JSONObject();
            for (String metric : new String[]{"heart_rate", "resting_heart_rate", "steps", "sleep"}) {
                statuses.put(metric, new JSONObject()
                        .put("status", "UNAVAILABLE")
                        .put("source", "health_connect"));
            }
            state.put("metricStatuses", statuses);
        } catch (Exception ignored) {}
        return state;
    }

    private static String nullable(JSONObject object, String key) {
        Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? null : String.valueOf(value);
    }

    private static String latestSampledAt(JSONArray records, String metric) {
        String latest = null;
        if (records == null) return null;
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.optJSONObject(i);
            if (row == null || !metric.equals(row.optString("metric"))) continue;
            String sampledAt = row.optString("sampledAt", "");
            if (!sampledAt.isEmpty() && (latest == null || sampledAt.compareTo(latest) > 0)) {
                latest = sampledAt;
            }
        }
        return latest;
    }

    private static int count(JSONArray records, String metric) {
        int count = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.optJSONObject(i);
            if (row != null && metric.equals(row.optString("metric"))) count++;
        }
        return count;
    }

    private static boolean isStale(String sampledAt) {
        if (sampledAt == null || sampledAt.isEmpty()) return true;
        try {
            return Instant.parse(sampledAt).isBefore(Instant.now().minus(48, ChronoUnit.HOURS));
        } catch (Exception ignored) {
            return true;
        }
    }

    private static String safeError(String error) {
        if (error == null) return "";
        String value = error.replaceAll("[^A-Za-z0-9_.-]", "_");
        return value.length() > 80 ? value.substring(0, 80) : value;
    }
}

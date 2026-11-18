package xyz.lovestyle.home.canary;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import org.json.JSONException;
import org.json.JSONObject;

public final class MotionRecognition {
    private static final int REQUEST = 5194;
    private static final String PREFS = "motion";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SNAPSHOT = "snapshot";

    private final MainActivity activity;
    private final SharedPreferences settings;
    private final PendingIntent callback;
    private boolean registered;

    MotionRecognition(MainActivity activity) {
        this.activity = activity;
        settings = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Intent intent = new Intent(activity, Receiver.class);
        callback = PendingIntent.getBroadcast(activity, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        if (!settings.contains(KEY_SNAPSHOT)) publish(settings, "已关闭", null);
    }

    @JavascriptInterface
    public String getActivityState() {
        return settings.getString(KEY_SNAPSHOT, "{\"schemaVersion\":1,\"source\":\"google\",\"registration\":\"已关闭\"}");
    }

    @JavascriptInterface
    public void setMotionEnabled(boolean enabled) {
        activity.runOnUiThread(() -> {
            settings.edit().putBoolean(KEY_ENABLED, enabled).apply();
            if (!enabled) {
                stop();
                publish(settings, "已关闭", null);
            } else if (!permitted()) {
                activity.requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQUEST);
            } else {
                resume();
            }
        });
    }

    private boolean permitted() {
        return Build.VERSION.SDK_INT < 29 || activity.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            == PackageManager.PERMISSION_GRANTED;
    }

    void permissionResult(int code) {
        if (code == REQUEST) resume();
    }

    void resume() {
        if (!settings.getBoolean(KEY_ENABLED, false)) {
            publish(settings, "已关闭", null);
            return;
        }
        if (!permitted()) {
            publish(settings, "需要身体活动权限", null);
            return;
        }
        int available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity);
        if (available != ConnectionResult.SUCCESS) {
            publish(settings, "Google Play 服务不可用 (" + available + ")", null);
            return;
        }
        if (registered) return;

        registered = true;
        publish(settings, "等待手机识别", null);
        ActivityRecognition.getClient(activity).requestActivityUpdates(15000, callback)
            .addOnFailureListener(error -> {
                if (registered && settings.getBoolean(KEY_ENABLED, false)) {
                    registered = false;
                    publish(settings, "识别启动失败：" + error.getLocalizedMessage(), null);
                }
            });
    }

    private void stop() {
        // removeActivityUpdates is safe with the same PendingIntent even after an Activity/process restart.
        ActivityRecognition.getClient(activity).removeActivityUpdates(callback);
        registered = false;
    }

    private static DetectedActivity selectRelevantActivity(ActivityRecognitionResult result) {
        DetectedActivity best = result.getMostProbableActivity();
        if (best.getType() != DetectedActivity.ON_FOOT) return best;

        // ON_FOOT is only a broad parent. Refine it to WALKING when Google supplied that child;
        // do not replace it with an unrelated candidate such as STILL/UNKNOWN.
        for (DetectedActivity candidate : result.getProbableActivities()) {
            if (candidate.getType() == DetectedActivity.WALKING) return candidate;
        }
        return best;
    }

    private static void publish(
            SharedPreferences settings, String status, ActivityRecognitionResult result) {
        JSONObject data = new JSONObject();
        try {
            data.put("schemaVersion", 1).put("source", "google").put("registration", status);
            if (result != null) {
                DetectedActivity best = selectRelevantActivity(result);
                int type = best.getType();
                String label = type == DetectedActivity.WALKING ? "walking"
                    : type == DetectedActivity.IN_VEHICLE ? "in_vehicle" : "unknown";
                data.put("userActivity", label).put("activityPossibility", best.getConfidence())
                    .put("activitySampledAt", result.getTime()).put("rawCandidate", type)
                    .put("callbackReceived", true).put("responsePresent", true)
                    .put("activityDataCount", result.getProbableActivities().size());
            }
            settings.edit().putString(KEY_SNAPSHOT, data.toString()).apply();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            SharedPreferences settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!settings.getBoolean(KEY_ENABLED, false)) {
                PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    REQUEST,
                    new Intent(context, Receiver.class),
                    PendingIntent.FLAG_NO_CREATE
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)
                );
                if (pending != null) ActivityRecognition.getClient(context).removeActivityUpdates(pending);
                return;
            }

            ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
            if (result != null) publish(settings, "已收到识别结果", result);
        }
    }
}

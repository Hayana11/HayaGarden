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
    private static MotionRecognition foreground;
    private final MainActivity activity;
    private final SharedPreferences settings;
    private final PendingIntent callback;
    private volatile String snapshot;
    private boolean registered;

    MotionRecognition(MainActivity activity) {
        this.activity = activity;
        settings = activity.getSharedPreferences("motion", Context.MODE_PRIVATE);
        Intent intent = new Intent(activity, Receiver.class);
        callback = PendingIntent.getBroadcast(activity, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        publish("已关闭", null);
    }

    @JavascriptInterface public String getActivityState() { return snapshot; }

    @JavascriptInterface public void setMotionEnabled(boolean enabled) {
        activity.runOnUiThread(() -> {
            if (foreground != this) return;
            settings.edit().putBoolean("enabled", enabled).apply();
            if (!enabled) {
                stop();
                publish("已关闭", null);
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
        if (code == REQUEST && foreground == this) resume();
    }

    void resume() {
        foreground = this;
        if (!settings.getBoolean("enabled", false)) { publish("已关闭", null); return; }
        if (!permitted()) { publish("需要身体活动权限", null); return; }
        int available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity);
        if (available != ConnectionResult.SUCCESS) {
            publish("Google Play 服务不可用 (" + available + ")", null);
            return;
        }
        if (registered) return;
        registered = true;
        publish("等待手机识别", null);
        ActivityRecognition.getClient(activity).requestActivityUpdates(15000, callback)
            .addOnFailureListener(error -> {
                if (foreground == this && registered) {
                    registered = false;
                    publish("识别启动失败：" + error.getLocalizedMessage(), null);
                }
            });
    }

    void pause() {
        if (foreground == this) foreground = null;
        stop();
        publish("已暂停", null);
    }

    private void stop() {
        if (registered) ActivityRecognition.getClient(activity).removeActivityUpdates(callback);
        registered = false;
    }

    private void publish(String status, ActivityRecognitionResult result) {
        JSONObject data = new JSONObject();
        try {
            data.put("schemaVersion", 1).put("source", "google").put("registration", status);
            if (result != null) {
                DetectedActivity best = result.getMostProbableActivity();
                int type = best.getType();
                if (type == DetectedActivity.ON_FOOT) {
                    for (DetectedActivity candidate : result.getProbableActivities()) {
                        if (candidate.getType() != DetectedActivity.ON_FOOT) {
                            best = candidate;
                            break;
                        }
                    }
                    type = best.getType();
                }
                String label = type == DetectedActivity.WALKING ? "walking"
                    : type == DetectedActivity.IN_VEHICLE ? "in_vehicle" : "unknown";
                data.put("userActivity", label).put("activityPossibility", best.getConfidence())
                    .put("activitySampledAt", result.getTime()).put("rawCandidate", type)
                    .put("callbackReceived", true).put("responsePresent", true)
                    .put("activityDataCount", result.getProbableActivities().size());
            }
            snapshot = data.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            MotionRecognition owner = foreground;
            if (owner == null || !owner.registered) {
                if (owner == null) {
                    PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST,
                        new Intent(context, Receiver.class), PendingIntent.FLAG_NO_CREATE
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                    if (pending != null) ActivityRecognition.getClient(context).removeActivityUpdates(pending);
                }
                return;
            }
            ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
            if (result != null) owner.publish("已收到识别结果", result);
        }
    }
}

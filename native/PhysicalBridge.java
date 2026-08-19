package xyz.lovestyle.home.canary;

import android.hardware.Sensor;
import android.webkit.JavascriptInterface;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * P2C.1: narrow JS read access to the foreground-live physical cache.
 */
public final class PhysicalBridge {
    private final PhysicalStateStore store;

    public PhysicalBridge(PhysicalStateStore store) {
        this.store = store;
    }

    @JavascriptInterface
    public String getPhysicalState() {
        PhysicalStateStore.Snapshot snapshot = store.snapshot();
        try {
            JSONObject state = new JSONObject();
            state.put("schemaVersion", 1);
            state.put("monitoring", snapshot.monitoring);
            state.put("battery", batteryState(snapshot.battery));
            state.put("accelerometer", sensorState(snapshot.accelerometer));
            state.put("gyroscope", sensorState(snapshot.gyroscope));
            state.put("proximity", sensorState(snapshot.proximity));
            state.put("light", sensorState(snapshot.light));
            state.put("sampledAt", snapshot.sampledAt);
            state.put("updatedAt", snapshot.updatedAt);
            return state.toString();
        } catch (JSONException impossible) {
            return "{\"schemaVersion\":1,\"monitoring\":false,\"sampledAt\":0,\"updatedAt\":0}";
        }
    }

    private JSONObject batteryState(PhysicalStateStore.BatteryState battery) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("available", battery.available);
        if (battery.available) {
            result.put("level", battery.level);
            result.put("charging", battery.charging);
        }
        return result;
    }

    private JSONObject sensorState(PhysicalStateStore.SensorSnapshot sensor)
            throws JSONException {
        JSONObject result = new JSONObject();
        result.put("available", sensor.available);
        result.put("ready", sensor.ready);
        if (!sensor.ready) {
            return result;
        }

        if (sensor.type == Sensor.TYPE_ACCELEROMETER
                || sensor.type == Sensor.TYPE_GYROSCOPE) {
            result.put("x", sensor.x);
            result.put("y", sensor.y);
            result.put("z", sensor.z);
        } else if (sensor.type == Sensor.TYPE_PROXIMITY) {
            result.put("value", sensor.value);
            result.put("maxRange", sensor.maxRange);
        } else if (sensor.type == Sensor.TYPE_LIGHT) {
            result.put("lux", sensor.lux);
        }
        result.put("sampledAt", sensor.sampledAt);
        return result;
    }
}

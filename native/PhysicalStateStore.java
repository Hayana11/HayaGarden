package xyz.lovestyle.home.canary;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import org.json.JSONObject;

/**
 * P2C.1 foreground-live physical state cache.
 *
 * MainActivity owns the foreground boundary by calling start() and stop().
 * Sensor callbacks update RAM only; snapshot() returns an immutable copy.
 */
public final class PhysicalStateStore {
    private final Object lock = new Object();
    private final Context context;
    private final SensorManager sensorManager;
    private final SensorEventListener listener;

    private final SensorState accelerometer = new SensorState(Sensor.TYPE_ACCELEROMETER);
    private final SensorState gyroscope = new SensorState(Sensor.TYPE_GYROSCOPE);
    private final SensorState proximity = new SensorState(Sensor.TYPE_PROXIMITY);
    private final SensorState light = new SensorState(Sensor.TYPE_LIGHT);

    private Sensor accelerometerSensor;
    private Sensor gyroscopeSensor;
    private Sensor proximitySensor;
    private Sensor lightSensor;
    private BatteryState battery = new BatteryState();
    private boolean monitoring;
    private long latestSampledAt;
    private long updatedAt;

    public PhysicalStateStore(Context context) {
        this.context = context.getApplicationContext();
        sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(android.hardware.SensorEvent event) {
                handleSensorEvent(event);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Accuracy is intentionally outside the raw P2C.1 contract.
            }
        };

        synchronized (lock) {
            refreshAvailabilityLocked();
            updatedAt = System.currentTimeMillis();
        }
    }

    /**
     * Starts all available foreground sensor listeners. Safe to call repeatedly.
     */
    public void start() {
        synchronized (lock) {
            if (monitoring) {
                return;
            }

            refreshAvailabilityLocked();
            refreshBatteryLocked();
            monitoring = true;
            updatedAt = System.currentTimeMillis();

            if (sensorManager == null) {
                return;
            }

            registerLocked(accelerometerSensor);
            registerLocked(gyroscopeSensor);
            registerLocked(proximitySensor);
            registerLocked(lightSensor);
        }
    }

    /**
     * Stops every listener owned by this store. Values remain in RAM for
     * inspection after pause, with their original sampledAt timestamps.
     */
    public void stop() {
        synchronized (lock) {
            try {
                if (sensorManager != null) {
                    sensorManager.unregisterListener(listener);
                }
            } finally {
                monitoring = false;
                updatedAt = System.currentTimeMillis();
            }
        }
    }

    /**
     * Returns a coherent immutable copy. No listener or sampling work occurs.
     */
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    monitoring,
                    battery.copy(),
                    accelerometer.copy(),
                    gyroscope.copy(),
                    proximity.copy(),
                    light.copy(),
                    latestSampledAt,
                    updatedAt
            );
        }
    }

    private void refreshAvailabilityLocked() {
        accelerometerSensor = defaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscopeSensor = defaultSensor(Sensor.TYPE_GYROSCOPE);
        proximitySensor = defaultSensor(Sensor.TYPE_PROXIMITY);
        lightSensor = defaultSensor(Sensor.TYPE_LIGHT);

        accelerometer.available = accelerometerSensor != null;
        gyroscope.available = gyroscopeSensor != null;
        proximity.available = proximitySensor != null;
        light.available = lightSensor != null;

        if (!accelerometer.available) {
            accelerometer.ready = false;
        }
        if (!gyroscope.available) {
            gyroscope.ready = false;
        }
        if (!proximity.available) {
            proximity.ready = false;
        }
        if (!light.available) {
            light.ready = false;
        }
    }

    private Sensor defaultSensor(int type) {
        return sensorManager == null ? null : sensorManager.getDefaultSensor(type);
    }

    private void registerLocked(Sensor sensor) {
        if (sensor == null) {
            return;
        }
        try {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (RuntimeException ignored) {
            // Hardware remains available; ready stays false until a real sample.
        }
    }

    private void handleSensorEvent(android.hardware.SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null) {
            return;
        }

        synchronized (lock) {
            if (!monitoring) {
                return;
            }

            long sampledAt = System.currentTimeMillis();
            switch (event.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    if (event.values.length >= 3) {
                        accelerometer.captureVector(event.values, sampledAt);
                    }
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    if (event.values.length >= 3) {
                        gyroscope.captureVector(event.values, sampledAt);
                    }
                    break;
                case Sensor.TYPE_PROXIMITY:
                    if (event.values.length >= 1) {
                        proximity.captureScalar(
                                event.values[0],
                                proximitySensor == null ? 0.0f : proximitySensor.getMaximumRange(),
                                sampledAt
                        );
                    }
                    break;
                case Sensor.TYPE_LIGHT:
                    if (event.values.length >= 1) {
                        light.captureScalar(event.values[0], 0.0f, sampledAt);
                    }
                    break;
                default:
                    return;
            }
            latestSampledAt = Math.max(latestSampledAt, sampledAt);
            updatedAt = sampledAt;
        }
    }

    private void refreshBatteryLocked() {
        BatteryState next = new BatteryState();
        try {
            JSONObject current = new JSONObject(new NativeBridge(context).getBattery());
            int level = current.optInt("percent", -1);
            if (level >= 0) {
                next.available = true;
                next.level = level;
                next.charging = current.optBoolean("charging", false);
            }
        } catch (Exception ignored) {
            // Battery absence is represented by available=false.
        }
        battery = next;
    }

    static final class BatteryState {
        boolean available;
        int level;
        boolean charging;

        BatteryState copy() {
            BatteryState copy = new BatteryState();
            copy.available = available;
            copy.level = level;
            copy.charging = charging;
            return copy;
        }
    }

    static final class SensorState {
        final int type;
        boolean available;
        boolean ready;
        float x;
        float y;
        float z;
        float value;
        float maxRange;
        float lux;
        long sampledAt;

        SensorState(int type) {
            this.type = type;
        }

        void captureVector(float[] values, long sampledAt) {
            available = true;
            ready = true;
            x = values[0];
            y = values[1];
            z = values[2];
            this.sampledAt = sampledAt;
        }

        void captureScalar(float value, float maxRange, long sampledAt) {
            available = true;
            ready = true;
            this.value = value;
            this.maxRange = maxRange;
            lux = value;
            this.sampledAt = sampledAt;
        }

        SensorSnapshot copy() {
            return new SensorSnapshot(
                    type, available, ready, x, y, z, value, maxRange, lux, sampledAt
            );
        }
    }

    static final class SensorSnapshot {
        final int type;
        final boolean available;
        final boolean ready;
        final float x;
        final float y;
        final float z;
        final float value;
        final float maxRange;
        final float lux;
        final long sampledAt;

        SensorSnapshot(
                int type,
                boolean available,
                boolean ready,
                float x,
                float y,
                float z,
                float value,
                float maxRange,
                float lux,
                long sampledAt
        ) {
            this.type = type;
            this.available = available;
            this.ready = ready;
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
            this.maxRange = maxRange;
            this.lux = lux;
            this.sampledAt = sampledAt;
        }
    }

    public static final class Snapshot {
        final boolean monitoring;
        final BatteryState battery;
        final SensorSnapshot accelerometer;
        final SensorSnapshot gyroscope;
        final SensorSnapshot proximity;
        final SensorSnapshot light;
        final long sampledAt;
        final long updatedAt;

        Snapshot(
                boolean monitoring,
                BatteryState battery,
                SensorSnapshot accelerometer,
                SensorSnapshot gyroscope,
                SensorSnapshot proximity,
                SensorSnapshot light,
                long sampledAt,
                long updatedAt
        ) {
            this.monitoring = monitoring;
            this.battery = battery;
            this.accelerometer = accelerometer;
            this.gyroscope = gyroscope;
            this.proximity = proximity;
            this.light = light;
            this.sampledAt = sampledAt;
            this.updatedAt = updatedAt;
        }
    }
}

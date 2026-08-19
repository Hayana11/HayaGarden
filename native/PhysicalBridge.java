package xyz.lovestyle.home.canary;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.webkit.JavascriptInterface;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * P2C.1: one-shot, read-only physical snapshot.
 *
 * Each available sensor is sampled in one bounded batch and the listener is
 * always removed before the call returns. No persistence, network, worker, or
 * service is used here.
 */
public final class PhysicalBridge {
    static final long SNAPSHOT_TIMEOUT_MS = 1000L;

    private final Context ctx;

    public PhysicalBridge(Context ctx) {
        this.ctx = ctx;
    }

    @JavascriptInterface
    public String getPhysicalState() {
        JSONObject state = emptyState();
        try {
            state.put("battery", readBattery());

            SensorSample[] samples = new SensorSample[] {
                    new SensorSample(Sensor.TYPE_ACCELEROMETER),
                    new SensorSample(Sensor.TYPE_GYROSCOPE),
                    new SensorSample(Sensor.TYPE_PROXIMITY),
                    new SensorSample(Sensor.TYPE_LIGHT)
            };
            sampleSensors(samples);

            state.put("accelerometer", vectorState(samples[0], 3));
            state.put("gyroscope", vectorState(samples[1], 3));
            state.put("proximity", vectorState(samples[2], 1));
            state.put("light", vectorState(samples[3], 1));
            state.put("sampledAt", System.currentTimeMillis());
            return state.toString();
        } catch (Exception ignored) {
            try {
                state.put("sampledAt", System.currentTimeMillis());
                return state.toString();
            } catch (JSONException impossible) {
                return "{\"schemaVersion\":1,\"sampledAt\":0}";
            }
        }
    }

    private JSONObject emptyState() {
        JSONObject state = new JSONObject();
        try {
            state.put("schemaVersion", 1);
            state.put("battery", unavailable());
            state.put("accelerometer", unavailable());
            state.put("gyroscope", unavailable());
            state.put("proximity", unavailable());
            state.put("light", unavailable());
            state.put("sampledAt", System.currentTimeMillis());
        } catch (JSONException ignored) {
            // JSONObject construction with literal keys is not expected to fail.
        }
        return state;
    }

    private JSONObject readBattery() {
        JSONObject battery = unavailable();
        try {
            JSONObject current = new JSONObject(
                    new NativeBridge(ctx).getBattery());
            int level = current.optInt("percent", -1);
            if (level >= 0) {
                battery.put("available", true);
                battery.put("level", level);
                battery.put("charging", current.optBoolean("charging", false));
            }
        } catch (Exception ignored) {
            // Battery absence is represented by available=false.
        }
        return battery;
    }

    private void sampleSensors(final SensorSample[] samples) {
        SensorManager manager = (SensorManager) ctx.getSystemService(
                Context.SENSOR_SERVICE);
        if (manager == null) {
            return;
        }

        int availableSensors = 0;
        for (SensorSample sample : samples) {
            sample.sensor = manager.getDefaultSensor(sample.type);
            if (sample.sensor != null) {
                availableSensors++;
            }
        }
        if (availableSensors == 0) {
            return;
        }

        final CountDownLatch complete = new CountDownLatch(availableSensors);
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                for (SensorSample sample : samples) {
                    if (sample.sensor != null
                            && sample.sensor.getType() == event.sensor.getType()
                            && sample.capture(event.values)) {
                        complete.countDown();
                        return;
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Accuracy is intentionally not part of the P2C.1 contract.
            }
        };

        try {
            for (SensorSample sample : samples) {
                if (sample.sensor == null) {
                    continue;
                }
                boolean registered = false;
                try {
                    registered = manager.registerListener(
                            listener, sample.sensor, SensorManager.SENSOR_DELAY_NORMAL);
                } catch (RuntimeException ignored) {
                    // One sensor registration failure must not affect the others.
                }
                if (!registered) {
                    sample.fail();
                    complete.countDown();
                }
            }
            complete.await(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            manager.unregisterListener(listener);
        }
    }

    private JSONObject vectorState(SensorSample sample, int dimensions) {
        JSONObject result = unavailable();
        float[] values = sample.values();
        if (values == null || values.length < dimensions) {
            return result;
        }

        try {
            result.put("available", true);
            if (dimensions == 3) {
                result.put("x", values[0]);
                result.put("y", values[1]);
                result.put("z", values[2]);
            } else if (sample.type == Sensor.TYPE_PROXIMITY) {
                result.put("value", values[0]);
                if (sample.sensor != null) {
                    result.put("maxRange", sample.sensor.getMaximumRange());
                }
            } else if (sample.type == Sensor.TYPE_LIGHT) {
                result.put("lux", values[0]);
            }
        } catch (JSONException ignored) {
            // Keep the per-sensor unavailable shape on an unexpected JSON error.
        }
        return result;
    }

    private JSONObject unavailable() {
        JSONObject result = new JSONObject();
        try {
            result.put("available", false);
        } catch (JSONException ignored) {
            // Literal key insertion is not expected to fail.
        }
        return result;
    }

    private static final class SensorSample {
        final int type;
        Sensor sensor;
        private float[] captured;
        private boolean complete;

        SensorSample(int type) {
            this.type = type;
        }

        synchronized boolean capture(float[] values) {
            if (complete || values == null) {
                return false;
            }
            captured = new float[values.length];
            System.arraycopy(values, 0, captured, 0, values.length);
            complete = true;
            return true;
        }

        synchronized void fail() {
            complete = true;
        }

        synchronized float[] values() {
            return captured;
        }
    }
}

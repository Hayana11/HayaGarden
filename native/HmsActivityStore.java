package xyz.lovestyle.home.canary;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.huawei.hmf.tasks.OnFailureListener;
import com.huawei.hmf.tasks.OnSuccessListener;
import com.huawei.hms.location.ActivityIdentification;
import com.huawei.hms.location.ActivityIdentificationData;
import com.huawei.hms.location.ActivityIdentificationResponse;
import com.huawei.hms.location.ActivityIdentificationService;

import java.util.List;

/**
 * Independent HMS activity-identification state.
 * PhysicalStateStore remains responsible for phone motion only.
 */
public final class HmsActivityStore {
    public static final String ACTION_ACTIVITY_IDENTIFICATION =
            "xyz.lovestyle.home.canary.HMS_ACTIVITY_IDENTIFICATION";
    public static final long UPDATE_INTERVAL_MS = 60_000L;
    public static final long MAX_AGE_MS = 3L * UPDATE_INTERVAL_MS;

    private static final String PREFS = "hms_activity_state";
    private static final String KEY_ACTIVITY = "activity";
    private static final String KEY_RAW_ACTIVITY = "raw_activity";
    private static final String KEY_POSSIBILITY = "possibility";
    private static final String KEY_SAMPLED_AT = "sampled_at";
    private static final int REQUEST_CODE = 19041;
    private static final String LEGACY_PERMISSION =
            "com.huawei.hms.permission.ACTIVITY_RECOGNITION";

    private final Context context;
    private final SharedPreferences prefs;
    private ActivityIdentificationService service;
    private PendingIntent pendingIntent;
    private boolean registering;
    private boolean registered;

    public HmsActivityStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Registers persistent HMS updates. This is deliberately not called from
     * onPause shutdown; PendingIntent delivery is the background path.
     */
    public synchronized void startIfPermitted() {
        if (registered || registering || !hasPermission()) {
            return;
        }
        try {
            service = ActivityIdentification.getService(context);
            pendingIntent = createPendingIntent(context);
            registering = true;
            service.createActivityIdentificationUpdates(UPDATE_INTERVAL_MS, pendingIntent)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override
                        public void onSuccess(Void ignored) {
                            synchronized (HmsActivityStore.this) {
                                registering = false;
                                registered = true;
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(Exception ignored) {
                            synchronized (HmsActivityStore.this) {
                                registering = false;
                                registered = false;
                            }
                        }
                    });
        } catch (RuntimeException ignored) {
            registering = false;
            registered = false;
        }
    }

    /** Explicit shutdown only; lifecycle pause must not call this. */
    public synchronized void stopUpdates() {
        if (service != null && pendingIntent != null) {
            try {
                service.deleteActivityIdentificationUpdates(pendingIntent);
            } catch (RuntimeException ignored) {
                // The next foreground start reconciles registration.
            }
        }
        registering = false;
        registered = false;
    }

    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? Manifest.permission.ACTIVITY_RECOGNITION
                : LEGACY_PERMISSION;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent createPendingIntent(Context context) {
        Intent intent = new Intent(context, HmsActivityReceiver.class);
        intent.setAction(ACTION_ACTIVITY_IDENTIFICATION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    public State snapshot() {
        return readState(prefs);
    }

    /**
     * Called only by the native HMS callback. ActivityIdentificationData
     * exposes numeric activity and possibility; when no event timestamp exists, the
     * native callback receipt time is the authoritative observation time.
     */
    static void handleIntent(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        long callbackAt = System.currentTimeMillis();
        String bestActivity = "unknown";
        String bestRaw = "UNKNOWN";
        int bestPossibility = -1;

        try {
            ActivityIdentificationResponse response =
                    ActivityIdentificationResponse.getDataFromIntent(intent);
            List<ActivityIdentificationData> data =
                    response == null ? null : response.getActivityIdentificationDatas();
            if (data != null) {
                for (ActivityIdentificationData item : data) {
                    if (item == null) {
                        continue;
                    }
                    int raw = item.getIdentificationActivity();
                    String mapped = mapActivity(raw);
                    if (mapped == null) {
                        continue;
                    }
                    int possibility = item.getPossibility();
                    if (possibility > bestPossibility) {
                        bestActivity = mapped;
                        bestRaw = Integer.toString(raw);
                        bestPossibility = possibility;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            bestActivity = "unknown";
            bestRaw = "UNKNOWN";
            bestPossibility = -1;
        }

        persistState(context.getApplicationContext(), new State(
                bestActivity, bestRaw, bestPossibility, callbackAt
        ));
    }

    private static String mapActivity(int raw) {
        switch (raw) {
            case ActivityIdentificationData.STILL:
                return "still";
            case ActivityIdentificationData.WALKING:
                return "walking";
            case ActivityIdentificationData.RUNNING:
                return "running";
            case ActivityIdentificationData.BIKE:
                return "cycling";
            case ActivityIdentificationData.VEHICLE:
                return "in_vehicle";
            case ActivityIdentificationData.FOOT:
                return "walking";
            default:
                return null;
        }
    }

    private static void persistState(Context context, State state) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVITY, state.userActivity)
                .putString(KEY_RAW_ACTIVITY, state.rawActivity)
                .putInt(KEY_POSSIBILITY, state.possibility)
                .putLong(KEY_SAMPLED_AT, state.sampledAt)
                .commit();
    }

    private static State readState(SharedPreferences prefs) {
        return new State(
                prefs.getString(KEY_ACTIVITY, "unknown"),
                prefs.getString(KEY_RAW_ACTIVITY, "UNKNOWN"),
                prefs.getInt(KEY_POSSIBILITY, -1),
                prefs.getLong(KEY_SAMPLED_AT, 0L)
        );
    }

    static final class State {
        final String userActivity;
        final String rawActivity;
        final int possibility;
        final long sampledAt;

        State(String userActivity, String rawActivity, int possibility, long sampledAt) {
            this.userActivity = userActivity;
            this.rawActivity = rawActivity;
            this.possibility = possibility;
            this.sampledAt = sampledAt;
        }
    }
}

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
import com.huawei.hms.common.ApiException;
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
    private static final String KEY_CALLBACK_RECEIVED = "callback_received";
    private static final String KEY_INTENT_HAS_EXTRAS = "intent_has_extras";
    private static final String KEY_RESPONSE_PRESENT = "response_present";
    private static final String KEY_ACTIVITY_DATA_COUNT = "activity_data_count";
    private static final int REQUEST_CODE = 19041;
    private static final String LEGACY_PERMISSION =
            "com.huawei.hms.permission.ACTIVITY_RECOGNITION";

    private final Context context;
    private final SharedPreferences prefs;
    private ActivityIdentificationService service;
    private PendingIntent pendingIntent;
    private boolean registering;
    private boolean registered;
    private String registrationState = "pending";
    private String lastErrorCode;

    public HmsActivityStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Registers persistent HMS updates. This is deliberately not called from
     * onPause shutdown; PendingIntent delivery is the background path.
     */
    public synchronized void startIfPermitted() {
        if (registered || registering) {
            return;
        }
        if (!hasPermission()) {
            registrationState = "failed";
            lastErrorCode = "PERMISSION_DENIED";
            return;
        }
        registrationState = "pending";
        lastErrorCode = null;
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
                                registrationState = "registered";
                                lastErrorCode = null;
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(Exception ignored) {
                            synchronized (HmsActivityStore.this) {
                                registering = false;
                                registered = false;
                                registrationState = "failed";
                                lastErrorCode = safeErrorCode(ignored);
                            }
                        }
                    });
        } catch (RuntimeException ignored) {
            registering = false;
            registered = false;
            registrationState = "failed";
            lastErrorCode = "HMS_SERVICE_UNAVAILABLE";
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
        registrationState = "pending";
        lastErrorCode = null;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    public synchronized State snapshot() {
        State persisted = readState(prefs);
        return new State(
                persisted.userActivity,
                persisted.rawActivity,
                persisted.possibility,
                persisted.sampledAt,
                registrationState,
                lastErrorCode,
                persisted.callbackReceived,
                persisted.intentHasExtras,
                persisted.responsePresent,
                persisted.activityDataCount
        );
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
        boolean callbackReceived = true;
        boolean intentHasExtras = intent.getExtras() != null
                && !intent.getExtras().isEmpty();
        boolean responsePresent = false;
        int activityDataCount = 0;

        try {
            ActivityIdentificationResponse response =
                    ActivityIdentificationResponse.getDataFromIntent(intent);
            responsePresent = response != null;
            List<ActivityIdentificationData> data =
                    response == null ? null : response.getActivityIdentificationDatas();
            activityDataCount = data == null ? 0 : data.size();
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
                bestActivity,
                bestRaw,
                bestPossibility,
                callbackAt,
                "pending",
                null,
                callbackReceived,
                intentHasExtras,
                responsePresent,
                activityDataCount
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
                return "unknown";
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
                .putBoolean(KEY_CALLBACK_RECEIVED, state.callbackReceived)
                .putBoolean(KEY_INTENT_HAS_EXTRAS, state.intentHasExtras)
                .putBoolean(KEY_RESPONSE_PRESENT, state.responsePresent)
                .putInt(KEY_ACTIVITY_DATA_COUNT, state.activityDataCount)
                .commit();
    }

    private static String safeErrorCode(Exception error) {
        if (error instanceof ApiException) {
            return "HMS_STATUS_" + ((ApiException) error).getStatusCode();
        }
        return "HMS_REGISTRATION_FAILED";
    }

    private static State readState(SharedPreferences prefs) {
        return new State(
                prefs.getString(KEY_ACTIVITY, "unknown"),
                prefs.getString(KEY_RAW_ACTIVITY, "UNKNOWN"),
                prefs.getInt(KEY_POSSIBILITY, -1),
                prefs.getLong(KEY_SAMPLED_AT, 0L),
                "pending",
                null,
                prefs.getBoolean(KEY_CALLBACK_RECEIVED, false),
                prefs.getBoolean(KEY_INTENT_HAS_EXTRAS, false),
                prefs.getBoolean(KEY_RESPONSE_PRESENT, false),
                prefs.getInt(KEY_ACTIVITY_DATA_COUNT, 0)
        );
    }

    static final class State {
        final String userActivity;
        final String rawActivity;
        final int possibility;
        final long sampledAt;
        final String registrationState;
        final String lastErrorCode;
        final boolean callbackReceived;
        final boolean intentHasExtras;
        final boolean responsePresent;
        final int activityDataCount;

        State(
                String userActivity,
                String rawActivity,
                int possibility,
                long sampledAt,
                String registrationState,
                String lastErrorCode,
                boolean callbackReceived,
                boolean intentHasExtras,
                boolean responsePresent,
                int activityDataCount
        ) {
            this.userActivity = userActivity;
            this.rawActivity = rawActivity;
            this.possibility = possibility;
            this.sampledAt = sampledAt;
            this.registrationState = registrationState;
            this.lastErrorCode = lastErrorCode;
            this.callbackReceived = callbackReceived;
            this.intentHasExtras = intentHasExtras;
            this.responsePresent = responsePresent;
            this.activityDataCount = activityDataCount;
        }
    }
}

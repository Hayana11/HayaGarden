package xyz.lovestyle.home.canary;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public final class HealthSupport {
    private static final String PERIODIC_NAME = "health_bridge_sync";
    private static final String NOW_NAME = "health_bridge_sync_now";

    private HealthSupport() {}

    public static void ensureScheduled(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                HealthSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void enqueueNow(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(HealthSyncWorker.class)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                NOW_NAME, ExistingWorkPolicy.KEEP, request);
    }
}

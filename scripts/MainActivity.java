package xyz.lovestyle.home;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.getcapacitor.BridgeActivity;
import java.util.concurrent.TimeUnit;

public class MainActivity extends BridgeActivity {

    private AppTracker tracker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // UsageStats 应用追踪
        tracker = new AppTracker(getApplicationContext());
        if (!tracker.hasPermission()) {
            tracker.openPermissionSettings();
        } else {
            tracker.start();
        }

        // WorkManager 轮询通知
        scheduleNotificationWorker();

        // Android 13+ 通知权限申请
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            }
        }
    }

    private void scheduleNotificationWorker() {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
            NotificationWorker.class, 15, TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(getApplicationContext())
            .enqueueUniquePeriodicWork(
                "poll_fyodor",
                ExistingPeriodicWorkPolicy.KEEP,
                work);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tracker != null) tracker.stop();
    }
}

package xyz.lovestyle.home;

import android.Manifest;
import android.content.Intent;
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

        // 关掉 WebView 跟随系统字体缩放（否则手机字号设大后 app 内文字/图标全部被放大）
        android.webkit.WebView wv = getBridge().getWebView();
        if (wv != null) {
            wv.getSettings().setTextZoom(100);
        }

        // UsageStats 应用追踪
        tracker = new AppTracker(getApplicationContext());
        if (!tracker.hasPermission()) {
            tracker.openPermissionSettings();
        } else {
            tracker.start();
        }

        // 前台服务保活（防止系统后台杀进程）
        startService(new Intent(this, ForegroundService.class));

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
    public void onBackPressed() {
        // 返回键先在 WebView 历史里后退（页面间导航），到头了才退出 app
        android.webkit.WebView wv = getBridge().getWebView();
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tracker != null) tracker.stop();
    }
}

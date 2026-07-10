package xyz.lovestyle.home;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
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
        // 90 = 在网页原始字号基础上再缩一档，缓解 app 内字体偏挤（只影响 app，浏览器不变）
        android.webkit.WebView wv = getBridge().getWebView();
        if (wv != null) {
            wv.getSettings().setTextZoom(90);
            wv.addJavascriptInterface(new NativeBridge(getApplicationContext()), "ElpisNative");
        }

        // UsageStats 应用追踪（屏幕时间也靠它）——没授权就显式拉起设置页
        tracker = new AppTracker(getApplicationContext());
        if (!tracker.hasPermission()) {
            tracker.openPermissionSettings();
        } else {
            tracker.start();
        }

        // 前台服务保活（防止系统后台杀进程）
        startForegroundServiceCompat();

        // 请求电池优化白名单（Doze 豁免），进一步防止后台被清
        requestIgnoreBatteryOptimizations();

        // 屏幕镜像授权：拿到 token 后 ScreenCaptureService 常驻，之后可随时静默截屏
        maybeRequestScreenCapture();

        // WorkManager 轮询通知
        scheduleNotificationWorker();

        // Android 13+ 通知权限申请
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
            }
        }

        // 前台位置权限申请（精确 + 粗略）
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1003);
        } else {
            requestBackgroundLocationIfNeeded();
        }
    }

    private void startForegroundServiceCompat() {
        Intent svc = new Intent(this, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    // 首次/未就绪时拉起投屏授权，用 pref 记录用户是否禁用了自动授权
    private void maybeRequestScreenCapture() {
        SharedPreferences sp = getSharedPreferences("elpis_push", MODE_PRIVATE);
        if (!sp.getBoolean("screencap_enabled", true)) return;
        if (ScreenCaptureService.isReady()) return;
        startActivity(new Intent(this, ScreenCapturePermissionActivity.class));
    }

    private void requestBackgroundLocationIfNeeded() {
        // Android 10+：后台位置需要单独申请，且必须在前台权限授予后再申请
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }, 1004);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1003) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) requestBackgroundLocationIfNeeded();
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
    public void onResume() {
        super.onResume();
        // 用户可能刚从设置页授予了 UsageStats，回到 app 时补启动追踪
        if (tracker != null && tracker.hasPermission()) {
            tracker.start();
        }
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

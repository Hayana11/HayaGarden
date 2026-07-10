package xyz.lovestyle.home;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class NativeBridge {
    private final Context ctx;

    public NativeBridge(Context ctx) {
        this.ctx = ctx;
    }

    @JavascriptInterface
    public String getBattery() {
        try {
            Intent b = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "{\"error\":\"unavailable\"}";

            int level   = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale   = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status  = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int temp    = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;

            String chargeType = "none";
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC)           chargeType = "ac";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_USB)     chargeType = "usb";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) chargeType = "wireless";

            JSONObject json = new JSONObject();
            json.put("percent",    scale > 0 ? (level * 100 / scale) : -1);
            json.put("charging",   charging);
            json.put("chargeType", chargeType);
            json.put("tempC",      temp >= 0 ? (temp / 10.0) : -1);
            return json.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public String getScreenTime() {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "{\"error\":\"unavailable\"}";

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                cal.getTimeInMillis(),
                System.currentTimeMillis());

            if (stats == null || stats.isEmpty()) {
                return "{\"error\":\"no_permission\"}";
            }

            PackageManager pm = ctx.getPackageManager();
            List<JSONObject> appList = new ArrayList<>();
            long totalMs = 0;

            for (UsageStats us : stats) {
                long ms = us.getTotalTimeInForeground();
                if (ms < 60000) continue; // 忽略不足1分钟的
                totalMs += ms;

                String label = us.getPackageName();
                try {
                    label = pm.getApplicationLabel(
                        pm.getApplicationInfo(us.getPackageName(), 0)).toString();
                } catch (Exception ignored) {}

                JSONObject obj = new JSONObject();
                obj.put("name", label);
                obj.put("pkg", us.getPackageName());
                obj.put("minutes", ms / 60000);
                appList.add(obj);
            }

            Collections.sort(appList, (a, b) -> {
                try { return b.getInt("minutes") - a.getInt("minutes"); }
                catch (Exception e) { return 0; }
            });

            JSONArray top = new JSONArray();
            for (int i = 0; i < Math.min(10, appList.size()); i++) {
                top.put(appList.get(i));
            }

            JSONObject result = new JSONObject();
            result.put("totalMinutes", totalMs / 60000);
            result.put("apps", top);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── 截屏 ──────────────────────────────────────────────────
    /** 前端主动触发一次截屏（需已授权投屏，否则会拉起授权框） */
    @JavascriptInterface
    public void takeScreenshot() {
        if (ScreenCaptureService.isReady()) {
            ScreenCaptureService.requestCapture(ctx);
        } else {
            requestScreenCapturePermission();
        }
    }

    /** 拉起系统"允许录屏/投屏"授权框（透明活动完成后常驻截屏服务） */
    @JavascriptInterface
    public void requestScreenCapturePermission() {
        Intent i = new Intent(ctx, ScreenCapturePermissionActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @JavascriptInterface
    public boolean isScreenCaptureReady() {
        return ScreenCaptureService.isReady();
    }

    /** 停止屏幕共享（释放投屏，撤掉常驻通知） */
    @JavascriptInterface
    public void stopScreenCapture() {
        ScreenCaptureService.stopSharing(ctx);
    }

    /** 是否在每次 app 启动时自动拉起投屏授权（默认开）。关掉后只能手动截屏时再授权。 */
    @JavascriptInterface
    public void setScreenCaptureAuto(boolean enabled) {
        ctx.getSharedPreferences("elpis_push", Context.MODE_PRIVATE)
                .edit().putBoolean("screencap_enabled", enabled).apply();
    }

    @JavascriptInterface
    public boolean isScreenCaptureAuto() {
        return ctx.getSharedPreferences("elpis_push", Context.MODE_PRIVATE)
                .getBoolean("screencap_enabled", true);
    }

    // ── 屏幕使用权限（UsageStats）─────────────────────────────
    @JavascriptInterface
    public boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            if (mode == AppOpsManager.MODE_ERRORED) return false;
            long now = System.currentTimeMillis();
            UsageStatsManager usm = (UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            List<UsageStats> probe = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now);
            return probe != null && !probe.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 打开系统"有权查看使用情况"设置页，让用户显式授予 UsageStats */
    @JavascriptInterface
    public void openUsageAccessSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }
    }

    // ── 后台保活：电池优化白名单 ──────────────────────────────
    @JavascriptInterface
    public boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    /** 请求把 app 加入电池优化白名单（Doze 豁免），防止后台被清 */
    @JavascriptInterface
    @SuppressWarnings("BatteryLife")
    public void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        }
    }
}

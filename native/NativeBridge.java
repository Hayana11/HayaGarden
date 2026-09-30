package xyz.lovestyle.home.canary;

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

/**
 * Phase 2A NativeBridge Lite — only six JS-callable methods.
 * No legacy shell extras, services, trackers, or background side effects.
 * All settings Intents fire only when JS explicitly invokes the matching method.
 */
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
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC)            chargeType = "ac";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_USB)      chargeType = "usb";
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
            if (!hasUsageAccess()) {
                return "{\"error\":\"no_permission\"}";
            }

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

            if (stats == null) {
                return "{\"error\":\"unavailable\"}";
            }
            if (stats.isEmpty()) {
                return "{\"totalMinutes\":0,\"apps\":[]}";
            }

            PackageManager pm = ctx.getPackageManager();
            List<JSONObject> appList = new ArrayList<>();
            long totalMs = 0;

            for (UsageStats us : stats) {
                long ms = us.getTotalTimeInForeground();
                if (ms < 60000) continue; // ignore under 1 minute
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

    @JavascriptInterface
    public boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens Usage Access settings only when JS explicitly calls this. Never auto-prompt. */
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

    @JavascriptInterface
    public boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    /** Requests Doze whitelist only when JS explicitly calls this. Never auto-prompt. */
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
    @JavascriptInterface
    public String getBuildInfo() {
        try {
            JSONObject result = new JSONObject();
            result.put("applicationId", ctx.getPackageName());
            android.content.pm.PackageInfo info = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            result.put("versionName", info.versionName);
            result.put("versionCode", Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode);
            result.put("sourceSha", BuildInfo.SOURCE_SHA);
            result.put("branch", BuildInfo.BRANCH);
            return result.toString();
        } catch (Exception ignored) {
            return "{\"error\":\"unavailable\"}";
        }
    }

}

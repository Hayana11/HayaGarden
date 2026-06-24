package xyz.lovestyle.home;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.webkit.JavascriptInterface;
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

            int level  = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale  = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int temp   = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;

            String chargeType = "none";
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC)       chargeType = "ac";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) chargeType = "usb";
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
}

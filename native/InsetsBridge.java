package xyz.lovestyle.home.canary;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.webkit.JavascriptInterface;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONException;
import org.json.JSONObject;

public final class InsetsBridge {
    private final Context context;
    private final View rootView;

    public InsetsBridge(Context context, View rootView) {
        this.context = context;
        this.rootView = rootView;
    }

    @JavascriptInterface
    public String getTopInset() {
        try {
            float density = context.getResources().getDisplayMetrics().density;
            if (!Float.isFinite(density) || density <= 0f) {
                return unavailable();
            }

            int topInsetPx = readLiveTopInsetPx();
            if (topInsetPx <= 0) {
                topInsetPx = readStatusBarResourcePx();
            }
            if (topInsetPx <= 0) {
                return unavailable();
            }

            JSONObject value = new JSONObject();
            value.put("schemaVersion", 1);
            value.put("available", true);
            value.put("edgeToEdgeTop", true);
            value.put("topInsetPx", topInsetPx);
            value.put("density", density);
            value.put("topInsetCssPx", topInsetPx / density);
            return value.toString();
        } catch (Exception ignored) {
            return unavailable();
        }
    }

    private int readLiveTopInsetPx() {
        if (rootView == null) return 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(rootView);
        if (insets == null) return 0;
        Insets statusAndCutout = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
                        | WindowInsetsCompat.Type.displayCutout()
        );
        return statusAndCutout.top;
    }

    private int readStatusBarResourcePx() {
        try {
            Resources resources = context.getResources();
            int resourceId = resources.getIdentifier(
                    "status_bar_height",
                    "dimen",
                    "android"
            );
            return resourceId == 0 ? 0 : resources.getDimensionPixelSize(resourceId);
        } catch (Resources.NotFoundException ignored) {
            return 0;
        }
    }

    private static String unavailable() {
        try {
            JSONObject value = new JSONObject();
            value.put("schemaVersion", 1);
            value.put("available", false);
            value.put("edgeToEdgeTop", false);
            return value.toString();
        } catch (JSONException impossible) {
            return "{\"schemaVersion\":1,\"available\":false,\"edgeToEdgeTop\":false}";
        }
    }
}

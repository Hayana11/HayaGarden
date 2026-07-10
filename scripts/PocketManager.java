package xyz.lovestyle.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * 前台服务持有的 Pocket 生命周期：WS 连接不随 Activity 销毁而断。
 * Activity 在时优先用可见 WebView；划掉后 fallback WebView 继续执行指令。
 */
public final class PocketManager {

    private static PocketManager instance;
    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private PocketClient client;
    private WebView activityWebView;
    private WebView fallbackWebView;

    private PocketManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public static synchronized PocketManager get(Context ctx) {
        if (instance == null) {
            instance = new PocketManager(ctx);
        }
        return instance;
    }

    /** ForegroundService / 开机自启时调用 */
    public void start() {
        main.post(this::ensureConnected);
    }

    public void attachActivityWebView(WebView wv) {
        main.post(() -> {
            activityWebView = wv;
            if (client != null) {
                client.setWebView(activeWebView());
            }
        });
    }

    public void detachActivityWebView() {
        main.post(() -> {
            activityWebView = null;
            if (client != null) {
                client.setWebView(activeWebView());
            }
        });
    }

    public void reloadConfig() {
        main.post(() -> {
            if (client != null) {
                client.disconnect();
                client = null;
            }
            ensureConnected();
        });
    }

    public boolean isConnected() {
        return PocketClient.isConnected();
    }

    public String getStatusJson() {
        SharedPreferences sp = appContext.getSharedPreferences("elpis_pocket", Context.MODE_PRIVATE);
        String token = sp.getString("pocket_token", "");
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("connected", PocketClient.isConnected());
            j.put("has_token", token != null && !token.isEmpty());
            j.put("using_activity_webview", activityWebView != null);
            j.put("ws", sp.getString("pocket_ws", "wss://love-style.xyz/pocket/ws"));
            return j.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void ensureFallbackWebView() {
        if (fallbackWebView != null) return;
        fallbackWebView = new WebView(appContext);
        WebSettings s = fallbackWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        fallbackWebView.loadUrl("about:blank");
    }

    private WebView activeWebView() {
        if (activityWebView != null) return activityWebView;
        ensureFallbackWebView();
        return fallbackWebView;
    }

    private void ensureConnected() {
        SharedPreferences sp = appContext.getSharedPreferences("elpis_pocket", Context.MODE_PRIVATE);
        String token = sp.getString("pocket_token", "");
        if (token == null || token.isEmpty()) return;

        String ws = sp.getString("pocket_ws", "wss://love-style.xyz/pocket/ws");
        ensureFallbackWebView();
        WebView wv = activeWebView();
        if (client == null) {
            client = new PocketClient(wv, ws, token);
            client.connect();
        } else {
            client.setWebView(wv);
        }
    }
}

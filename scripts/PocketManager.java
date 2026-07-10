package xyz.lovestyle.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * 前台服务持有的 Pocket 生命周期：WS 连接不随 Activity 销毁而断。
 * 永远使用专用 WebView（不注入 ElpisNative），与主 App WebView 隔离——
 * 外站页面不可触达 setPocketConfig / 截屏 / 权限等原生桥。
 */
public final class PocketManager {

    private static PocketManager instance;
    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private PocketClient client;
    private WebView pocketWebView;

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
            j.put("dedicated_webview", pocketWebView != null);
            j.put("ws", sp.getString("pocket_ws", "wss://love-style.xyz/pocket/ws"));
            return j.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void ensurePocketWebView() {
        if (pocketWebView != null) return;
        pocketWebView = new WebView(appContext);
        WebSettings s = pocketWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 故意不 addJavascriptInterface —— 外站 cookie/登录态在此 WebView，但碰不到原生桥
        pocketWebView.loadUrl("about:blank");
    }

    private void ensureConnected() {
        SharedPreferences sp = appContext.getSharedPreferences("elpis_pocket", Context.MODE_PRIVATE);
        String token = sp.getString("pocket_token", "");
        if (token == null || token.isEmpty()) return;

        String ws = sp.getString("pocket_ws", "wss://love-style.xyz/pocket/ws");
        ensurePocketWebView();
        if (client == null) {
            client = new PocketClient(pocketWebView, ws, token);
            client.connect();
        } else {
            client.setWebView(pocketWebView);
        }
    }
}

package xyz.lovestyle.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * pocket-browser 安卓侧客户端：OkHttp WebSocket + 专用 WebView 执行 5 个指令。
 * WS 鉴权走 Authorization: Bearer。WebView 由 PocketManager 提供，不含原生桥。
 */
public class PocketClient {

    private static final String DEFAULT_WS = "wss://love-style.xyz/pocket/ws";
    private static volatile boolean connected;
    private static ConnectionListener connectionListener;

    private volatile WebView webView;
    private final String serverWs;
    private final String token;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .build();

    private WebSocket socket;
    private boolean shouldReconnect = true;
    private long retryDelayMs = 1000L;

    public interface ConnectionListener {
        void onConnectionChanged(boolean connected);
    }

    public static void setConnectionListener(ConnectionListener listener) {
        connectionListener = listener;
    }

    public PocketClient(WebView webView, String serverWs, String token) {
        this.webView = webView;
        this.serverWs = (serverWs == null || serverWs.isEmpty()) ? DEFAULT_WS : serverWs;
        this.token = token;
    }

    public void setWebView(WebView wv) {
        if (wv != null) this.webView = wv;
    }

    public static boolean isConnected() {
        return connected;
    }

    public void connect() {
        if (token == null || token.isEmpty()) return;
        if (socket != null) return;
        shouldReconnect = true;
        retryDelayMs = 1000L;
        openSocket();
    }

    public void disconnect() {
        shouldReconnect = false;
        connected = false;
        notifyConnection(false);
        if (socket != null) {
            socket.close(1000, "bye");
            socket = null;
        }
    }

    private void openSocket() {
        Request req = new Request.Builder()
                .url(serverWs)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        socket = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                retryDelayMs = 1000L;
                notifyConnection(true);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleCommand(ws, text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                connected = false;
                socket = null;
                notifyConnection(false);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected = false;
                socket = null;
                notifyConnection(false);
                scheduleReconnect();
            }
        });
    }

    private static void notifyConnection(boolean on) {
        ConnectionListener l = connectionListener;
        if (l != null) l.onConnectionChanged(on);
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;
        long delay = retryDelayMs;
        retryDelayMs = Math.min(retryDelayMs * 2, 30000L);
        main.postDelayed(() -> {
            if (shouldReconnect && socket == null) openSocket();
        }, delay);
    }

    private void handleCommand(WebSocket ws, String text) {
        try {
            JSONObject cmd = new JSONObject(text);
            String id = cmd.optString("id", "");
            String action = cmd.optString("action", "");
            if (id.isEmpty() || action.isEmpty()) return;

            main.post(() -> dispatch(ws, id, action, cmd));
        } catch (Exception ignored) {}
    }

    private void dispatch(WebSocket ws, String id, String action, JSONObject cmd) {
        switch (action) {
            case "ping":
                reply(ws, id, true, "pong", null);
                break;
            case "goto":
                String url = cmd.optString("url", "");
                if (url.isEmpty()) {
                    reply(ws, id, false, null, "bad url");
                } else {
                    webView.loadUrl(url);
                    reply(ws, id, true, "loading", null);
                }
                break;
            case "js":
                String js = cmd.has("js") ? cmd.optString("js") : cmd.optString("code", "");
                if (js.isEmpty()) {
                    reply(ws, id, false, null, "no js");
                } else {
                    webView.evaluateJavascript(js, value -> reply(ws, id, true, unwrapJs(value), null));
                }
                break;
            case "html":
                webView.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})()",
                        value -> reply(ws, id, true, unwrapJs(value), null));
                break;
            case "screenshot":
                captureAndReply(ws, id);
                break;
            default:
                reply(ws, id, false, null, "unknown action");
        }
    }

    private void captureAndReply(WebSocket ws, String id) {
        try {
            int w = webView.getWidth();
            int h = webView.getHeight();
            if (w <= 0 || h <= 0) {
                reply(ws, id, false, null, "webview not laid out");
                return;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            webView.draw(canvas);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            reply(ws, id, true, "data:image/png;base64," + b64, null);
        } catch (Exception e) {
            reply(ws, id, false, null, e.getMessage() != null ? e.getMessage() : "snapshot failed");
        }
    }

    private static String unwrapJs(String value) {
        // evaluateJavascript 的返回值是一个合法 JSON 值（带引号字符串/数字/对象…），
        // 手工替换转义序列会漏 \\、\t、\uXXXX——直接按 JSON 解析。
        if (value == null || "null".equals(value)) return "";
        try {
            Object v = new JSONTokener(value).nextValue();
            if (v == null || JSONObject.NULL.equals(v)) return "";
            return v.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private void reply(WebSocket ws, String id, boolean ok, String result, String error) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("ok", ok);
            if (result != null) obj.put("result", result);
            if (error != null) obj.put("error", error);
            ws.send(obj.toString());
        } catch (Exception ignored) {}
    }
}

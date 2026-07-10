package xyz.lovestyle.home;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.URLDecoder;
import java.util.Locale;

/** Shared WebView rules for Pocket browsing: keep http(s) in-app and block app-scheme escapes. */
public final class PocketWebViewPolicy {
    private PocketWebViewPolicy() {}

    public static void configure(WebView webView) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldKeepOutOfExternalApps(view, request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return shouldKeepOutOfExternalApps(view, url);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                return false;
            }
        });
    }

    public static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return "https://m.taobao.com/";
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        return url;
    }

    private static boolean shouldKeepOutOfExternalApps(WebView view, String rawUrl) {
        if (rawUrl == null) return false;
        String url = rawUrl.trim();
        if (url.isEmpty()) return false;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return true;
        }
        String scheme = uri.getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.US);
        if ("http".equals(scheme) || "https".equals(scheme) || "about".equals(scheme)) {
            return false;
        }
        if ("intent".equals(scheme)) {
            String fallback = intentFallback(url);
            if (fallback != null && fallback.matches("(?i)^https?://.*")) {
                view.loadUrl(fallback);
            }
            return true;
        }
        // taobao://, tbopen://, tmall://, alipays:// and other app schemes must not escape
        // to another app/browser, otherwise cookies land outside the Pocket WebView storage.
        return true;
    }

    private static String intentFallback(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (fallback != null && !fallback.isEmpty()) return fallback;
        } catch (Exception ignored) {}
        try {
            String marker = "S.browser_fallback_url=";
            int start = url.indexOf(marker);
            if (start < 0) return null;
            start += marker.length();
            int end = url.indexOf(';', start);
            String encoded = end >= 0 ? url.substring(start, end) : url.substring(start);
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception ignored) {
            return null;
        }
    }
}

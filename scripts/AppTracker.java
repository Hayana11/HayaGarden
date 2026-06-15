package xyz.lovestyle.home;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AppTracker {
    private static final String TAG = "AppTracker";
    private static final String API_HOST = "https://love-style.xyz";
    private static final long INTERVAL = 60;

    private final Context ctx;
    private final UsageStatsManager usm;
    private String lastPkg = "";
    private ScheduledExecutorService sched;

    private static final Map<String, String> NAMES = new HashMap<String, String>() {{
        put("com.tencent.mm",               "微信");
        put("com.tencent.mobileqq",         "QQ");
        put("com.xingin.share",             "小红书");
        put("com.ss.android.ugc.aweme",     "抖音");
        put("com.zhiliaoapp.musically",     "抖音");
        put("tv.danmaku.bili",              "B站");
        put("com.bilibili.app.in",          "B站");
        put("com.netease.cloudmusic",       "网易云音乐");
        put("com.tencent.qqmusic",          "QQ音乐");
        put("com.kugou.android",            "酷狗音乐");
        put("com.kuwo.player",              "酷我音乐");
        put("com.taobao.taobao",            "淘宝");
        put("com.jingdong.app.mall",        "京东");
        put("com.pinduoduo.android",        "拼多多");
        put("com.smile.gifmaker",           "快手");
        put("com.weibo.android",            "微博");
        put("com.sina.weibo",               "微博");
        put("com.zhihu.android",            "知乎");
        put("com.douban.app.activity",      "豆瓣");
        put("com.twitter.android",          "Twitter");
        put("com.instagram.android",        "Instagram");
        put("com.google.android.youtube",   "YouTube");
        put("com.spotify.music",            "Spotify");
        put("com.discord",                  "Discord");
        put("org.telegram.messenger",       "Telegram");
        put("com.netflix.mediaclient",      "Netflix");
        put("com.google.android.gm",        "Gmail");
        put("com.google.android.apps.maps", "地图");
        put("com.meituan.android.pt",       "美团");
        put("com.dianping.v1",              "大众点评");
        put("ctrip.android.view",           "携程");
        put("com.ele.me",                   "饿了么");
        put("com.sankuai.meituan.takeoutnew","美团外卖");
    }};

    public AppTracker(Context ctx) {
        this.ctx = ctx;
        this.usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public boolean hasPermission() {
        AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public void openPermissionSettings() {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    public void start() {
        if (sched != null && !sched.isShutdown()) return;
        sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleAtFixedRate(this::check, 0, INTERVAL, TimeUnit.SECONDS);
        Log.i(TAG, "tracker started");
    }

    public void stop() {
        if (sched != null) { sched.shutdownNow(); sched = null; }
    }

    private String foreground() {
        long now = System.currentTimeMillis();
        List<UsageStats> list = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 300_000L, now);
        if (list == null || list.isEmpty()) return null;
        SortedMap<Long, UsageStats> map = new TreeMap<>();
        for (UsageStats s : list) map.put(s.getLastTimeUsed(), s);
        return map.get(map.lastKey()).getPackageName();
    }

    private void check() {
        String pkg = foreground();
        if (pkg == null || pkg.equals(lastPkg)) return;
        lastPkg = pkg;
        // 跳过 VII 自身和系统 UI
        if (pkg.equals(ctx.getPackageName()) || pkg.equals("com.android.systemui")) return;
        String name  = NAMES.getOrDefault(pkg, pkg);
        String short_ = pkg.contains(".") ? pkg.substring(pkg.lastIndexOf('.') + 1) : pkg;
        report(short_, name);
    }

    private void report(String type, String name) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String val = URLEncoder.encode("在用" + name, "UTF-8");
                URL url = new URL(API_HOST + "/api/dream/events?type=app." + type + "&value=" + val);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                Log.i(TAG, "reported " + name + " → " + c.getResponseCode());
                c.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "report err: " + e);
            }
        });
    }
}

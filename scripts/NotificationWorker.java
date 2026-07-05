package xyz.lovestyle.home;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * WorkManager 15min 轮询，作为持久连接（ForegroundService.pushLoop）被系统杀掉时的兜底。
 * 主推送通道是 ForegroundService 里常驻的长轮询，秒级到达；这里只是保命的备份网。
 * 两条通道共用 {@link #showMessage} 里的 SharedPreferences 去重，避免同一条消息弹两次。
 */
public class NotificationWorker extends Worker {
    static final String CHANNEL_ID = "fyodor_msg";
    static final String API = "https://love-style.xyz/api/wake_log/pending_notification";
    private static final String PREFS = "elpis_push";
    private static final String KEY_LAST_ID = "last_notif_id";

    public NotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            String lastId = getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_ID, "");
            String url = API + "?since=" + java.net.URLEncoder.encode(lastId, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            c.disconnect();
            JSONObject j = new JSONObject(sb.toString());
            showMessage(getApplicationContext(), j);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    /**
     * 解析后端返回并（在去重后）弹通知。供 WorkManager 与 ForegroundService 长轮询共用。
     * 返回后端字段里的 command（如 "screenshot"），无则空串。
     */
    static String showMessage(Context ctx, JSONObject j) {
        if (j == null) return "";
        if (j.optBoolean("has_message", false)) {
            String id = j.optString("id", "");
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean fresh = true;
            if (!id.isEmpty()) {
                fresh = !id.equals(sp.getString(KEY_LAST_ID, ""));
                if (fresh) sp.edit().putString(KEY_LAST_ID, id).apply();
            }
            if (fresh) {
                showNotification(ctx,
                        j.optString("title", "费奥多尔"),
                        j.optString("content", ""),
                        id);
            }
        }
        return j.optString("command", "");
    }

    private static void showNotification(Context ctx, String title, String body, String id) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "费奥多尔的消息", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent i = new Intent(ctx, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // 不同消息用不同 notify id 让通知堆叠而不是互相覆盖
        int notifId = id.isEmpty() ? 1001 : (1001 + Math.abs(id.hashCode() % 100000));
        nm.notify(notifId, new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pi)
            .build());
    }
}

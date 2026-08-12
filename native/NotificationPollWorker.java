package xyz.lovestyle.home.canary;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Best-effort 15-minute display-only polling. */
public class NotificationPollWorker extends Worker {
    public NotificationPollWorker(
            @NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!NotificationSupport.canPostNotifications(getApplicationContext())) {
            return Result.success();
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(NotificationSupport.API);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoInput(true);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return Result.retry();
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JSONObject payload = new JSONObject(response.toString());
            if (!payload.optBoolean("has_message", false)) {
                return Result.success();
            }

            String content = payload.optString("content", "");
            if (content.trim().isEmpty()) {
                return Result.success();
            }
            String title = payload.optString("title", "费奥多尔");
            if (title.trim().isEmpty()) {
                title = "费奥多尔";
            }
            String messageId = payload.optString("id", "");
            NotificationSupport.showNotification(
                    getApplicationContext(), title, content, messageId);
            return Result.success();
        } catch (Exception ignored) {
            return Result.retry();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}

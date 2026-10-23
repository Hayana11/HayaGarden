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

/** Best-effort 15-minute display-only polling (ack protocol; shown once per id). */
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
        // Bound to this request: its response may only apply a call delta against it.
        CallState.Snapshot call = CallState.snapshot();
        try {
            URL url = new URL(NotificationSupport.pollUrl(getApplicationContext(), 0, call));
            connection = (HttpURLConnection) url.openConnection();
            if (!NotificationSupport.authorize(getApplicationContext(), connection)) {
                // Not enrolled as a Health Bridge device: the ack protocol cannot authenticate.
                return Result.success();
            }
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
            NotificationSupport.handlePayload(getApplicationContext(), payload, call);
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

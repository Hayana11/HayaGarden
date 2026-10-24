package xyz.lovestyle.home.canary;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.io.OutputStream;

/** Background collection and HTTPS upload; WebView never waits for this work. */
public final class HealthSyncWorker extends Worker {
    private final HealthStateStore store;

    public HealthSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        store = new HealthStateStore(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean manual = getInputData().getBoolean("manual", false);
        if (!manual && !HealthConnectReader.canReadInBackground(getApplicationContext())) {
            return Result.success();
        }

        final String payload;
        try {
            payload = HealthConnectReader.collectBlocking(getApplicationContext());
        } catch (Exception ignored) {
            return Result.success();
        }
        if (!store.saveCollection(payload)) return Result.success();

        HealthCredentialStore credentialStore =
                new HealthCredentialStore(getApplicationContext());
        String deviceId = credentialStore.getDeviceId();
        String credential = credentialStore.getCredential();
        if (deviceId.isEmpty() || credential == null || credential.isEmpty()) {
            store.markUpload(Instant.now().toString(), false, "auth_not_configured");
            return Result.success();
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(HealthConfig.INGEST_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + credential);
            connection.setRequestProperty("X-Health-Device-ID", deviceId);
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                store.markUpload(Instant.now().toString(), true, "");
                return Result.success();
            }
            String error = (code == 401 || code == 403) ? "auth_invalid" : "http_" + code;
            store.markUpload(Instant.now().toString(), false, error);
            if (code == 408 || code == 429 || code >= 500) {
                return Result.retry();
            }
            return Result.success();
        } catch (Exception ignored) {
            store.markUpload(Instant.now().toString(), false, "network_error");
            return Result.retry();
        } finally {
            if (connection != null) {
                try {
                    InputStream stream = connection.getErrorStream();
                    if (stream != null) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                            while (reader.readLine() != null) {}
                        }
                    }
                } catch (Exception ignored) {}
                connection.disconnect();
            }
        }
    }
}

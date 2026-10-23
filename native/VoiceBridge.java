package xyz.lovestyle.home.canary;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.ArrayDeque;
import org.json.JSONObject;

/**
 * P2F native voice bridge. It never calls SpeechRecognizer and never puts audio
 * in an event: takeUtterance(seq) removes one bounded WAV from the native queue.
 */
public final class VoiceBridge implements AutoCloseable, VoiceCapture.Listener {
    public static final int REQUEST_RECORD_AUDIO = 2006;
    private static final int MAX_QUEUED_UTTERANCES = 4;
    private static final int MAX_QUEUED_BYTES = 4 * 1024 * 1024;
    /**
     * The server orders one call's speech by these numbers, so they only grow,
     * persisted across process restarts: a mic-off cancel(), a pause, an
     * Activity rebuild or a killed process must never hand out a number the
     * current call already used. A new call needs no reset; the server skips
     * numbers that never began there in one step.
     */
    private static final String SEQ_PREFS = "elpis_voice_seq";
    private static final String SEQ_KEY = "last_seq";
    private static final Object SEQ_LOCK = new Object();

    private static final class QueuedUtterance {
        final int seq;
        final byte[] wav;
        final int durationMs;

        QueuedUtterance(int seq, byte[] wav, int durationMs) {
            this.seq = seq;
            this.wav = wav;
            this.durationMs = durationMs;
        }
    }

    private final Activity activity;
    private final WebView webView;
    private final VoiceCapture capture;
    private final Object queueLock = new Object();
    private final ArrayDeque<QueuedUtterance> queue = new ArrayDeque<>();
    private int queuedBytes;
    private int activeSeq;
    private boolean closed;

    public VoiceBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.capture = new VoiceCapture(activity, this);
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return !closed;
    }

    @JavascriptInterface
    public boolean hasPermission() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public void requestPermission() {
        if (hasPermission()) {
            emitPermission(true);
            return;
        }
        activity.runOnUiThread(() -> activity.requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO));
    }

    /** Called from MainActivity.onRequestPermissionsResult. */
    public void onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        emitPermission(grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED);
    }

    @JavascriptInterface
    public void start() {
        if (closed) {
            emitError("capture_closed");
            return;
        }
        if (!hasPermission()) {
            emitError("permission_denied");
            return;
        }
        capture.start();
    }

    @JavascriptInterface
    public void stop() {
        stopCapture("paused");
    }

    @JavascriptInterface
    public void cancel() {
        stopCapture("canceled");
        synchronized (queueLock) {
            queue.clear();
            queuedBytes = 0;
            activeSeq = 0;
        }
    }

    /** Called by MainActivity.onPause; it stops capture but keeps no background thread. */
    public void release() {
        stopCapture("paused");
    }

    /** Called by MainActivity.onResume; the page decides whether to start again. */
    public void resumed() {
        emitType("resumed");
    }

    /** Returns and removes exactly one WAV; no base64 is retained after this call. */
    @JavascriptInterface
    public String takeUtterance(int seq) {
        synchronized (queueLock) {
            for (QueuedUtterance item : queue) {
                if (item.seq == seq) {
                    queue.remove(item);
                    queuedBytes -= item.wav.length;
                    return Base64.encodeToString(item.wav, Base64.NO_WRAP);
                }
            }
        }
        return "";
    }

    @Override
    public void close() {
        synchronized (queueLock) {
            if (closed) return;
            closed = true;
        }
        stopCapture("closed");
        synchronized (queueLock) {
            queue.clear();
            queuedBytes = 0;
            activeSeq = 0;
        }
        capture.close();
    }

    @Override
    public void onReady(boolean aecAvailable, boolean aecEnabled) {
        try {
            JSONObject event = new JSONObject()
                    .put("type", "ready")
                    .put("aec_available", aecAvailable)
                    .put("aec_enabled", aecEnabled)
                    .put("vad_ready", true);
            emitJson(event);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSpeechStart() {
        int seq = allocateSeq();
        synchronized (queueLock) {
            activeSeq = seq;
        }
        try {
            emitJson(new JSONObject().put("type", "speech_start").put("seq", seq));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUtterance(short[] samples) {
        int seq;
        synchronized (queueLock) {
            seq = activeSeq;
            activeSeq = 0;
        }
        if (seq == 0) {
            emitError("capture_failed");
            return;
        }
        byte[] wav;
        try {
            wav = WavEncoder.encode(samples, VoiceSegmenter.SAMPLE_RATE);
        } catch (RuntimeException failed) {
            emitError("wav_encode");
            return;
        }
        ArrayDeque<Integer> droppedSeqs = new ArrayDeque<>();
        boolean queueDropped = false;
        synchronized (queueLock) {
            if (wav.length > MAX_QUEUED_BYTES) {
                droppedSeqs.add(seq);
                queueDropped = true;
            } else {
                while (!queue.isEmpty()
                        && (queue.size() >= MAX_QUEUED_UTTERANCES
                        || queuedBytes + wav.length > MAX_QUEUED_BYTES)) {
                    QueuedUtterance old = queue.removeFirst();
                    queuedBytes -= old.wav.length;
                    droppedSeqs.add(old.seq);
                    queueDropped = true;
                }
                queue.addLast(new QueuedUtterance(
                        seq, wav, samples.length * 1000 / VoiceSegmenter.SAMPLE_RATE));
                queuedBytes += wav.length;
            }
        }
        while (!droppedSeqs.isEmpty()) {
            emitDiscarded(droppedSeqs.removeFirst(), "queue_full");
        }
        if (wav.length > MAX_QUEUED_BYTES) {
            emitError("queue_full");
            return;
        }
        if (queueDropped) emitError("queue_full");
        try {
            emitJson(new JSONObject()
                    .put("type", "utterance_ready")
                    .put("seq", seq)
                    .put("duration_ms", samples.length * 1000 / VoiceSegmenter.SAMPLE_RATE)
                    .put("bytes", wav.length));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDiscarded(String reason) {
        int seq;
        synchronized (queueLock) {
            seq = activeSeq;
            activeSeq = 0;
        }
        if (seq != 0) emitDiscarded(seq, reason);
    }

    private void emitDiscarded(int seq, String reason) {
        try {
            emitJson(new JSONObject()
                    .put("type", "utterance_discarded")
                    .put("seq", seq)
                    .put("reason", reason == null ? "discarded" : reason));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onError(String code) {
        emitError(code);
    }

    /** Runs on the capture thread; the number is on disk before any event names it. */
    private int allocateSeq() {
        synchronized (SEQ_LOCK) {
            SharedPreferences prefs = activity.getApplicationContext()
                    .getSharedPreferences(SEQ_PREFS, Context.MODE_PRIVATE);
            int seq = prefs.getInt(SEQ_KEY, 0) + 1;
            prefs.edit().putInt(SEQ_KEY, seq).commit();
            return seq;
        }
    }

    private void stopCapture(String reason) {
        int abandoned;
        synchronized (queueLock) {
            abandoned = activeSeq;
            activeSeq = 0;
        }
        capture.stop();
        if (abandoned != 0) emitDiscarded(abandoned, reason);
        if ("paused".equals(reason)) emitType("paused");
    }

    private void emitPermission(boolean granted) {
        try {
            emitJson(new JSONObject().put("type", "permission").put("granted", granted));
        } catch (Exception ignored) {
        }
    }

    private void emitError(String code) {
        try {
            emitJson(new JSONObject().put("type", "error").put("code", code));
        } catch (Exception ignored) {
        }
    }

    private void emitType(String type) {
        try {
            emitJson(new JSONObject().put("type", type));
        } catch (Exception ignored) {
        }
    }

    private void emitJson(JSONObject event) {
        final String script = "window.__elpisVoice && window.__elpisVoice("
                + JSONObject.quote(event.toString()) + ")";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }
}

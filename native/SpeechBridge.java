package xyz.lovestyle.home.canary;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.ArrayList;
import org.json.JSONObject;

/**
 * P2E: speech-to-text for the call page via android.speech.SpeechRecognizer.
 *
 * JS: window.ElpisSpeech.{isAvailable, hasPermission, requestPermission, start(lang), stop, cancel}.
 * Results go back as window.__elpisSpeech(json) with type
 * ready | speech_start | partial | final | end | error | permission | resumed.
 *
 * One utterance per start(); the page restarts listening when it wants more.
 * Never forces on-device recognition (a phone with dictation disabled would
 * otherwise fail silently). RECORD_AUDIO is requested only when the page asks
 * (a tap on the mic), never automatically.
 */
public class SpeechBridge {
    public static final int REQUEST_RECORD_AUDIO = 2005;
    private static final long END_OF_UTTERANCE_SILENCE_MS = 2000L;

    private final Activity activity;
    private final WebView webView;
    private SpeechRecognizer recognizer;

    public SpeechBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(activity.getApplicationContext());
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
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        emitPermission(grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED);
    }

    @JavascriptInterface
    public void start(final String language) {
        activity.runOnUiThread(() -> {
            if (!hasPermission()) {
                emit("error", null, "no_permission");
                return;
            }
            if (!isAvailable()) {
                emit("error", null, "unavailable");
                return;
            }
            destroyRecognizer();
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new Listener());
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                            language == null || language.isEmpty() ? "zh-CN" : language)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                            END_OF_UTTERANCE_SILENCE_MS)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                            END_OF_UTTERANCE_SILENCE_MS);
            recognizer.startListening(intent);
        });
    }

    @JavascriptInterface
    public void stop() {
        activity.runOnUiThread(() -> {
            if (recognizer != null) {
                recognizer.stopListening();
            }
        });
    }

    @JavascriptInterface
    public void cancel() {
        activity.runOnUiThread(this::destroyRecognizer);
    }

    /**
     * Called from MainActivity.onPause: never keep the microphone open in the background.
     * A live recognizer is reported as error "paused" so the page stops claiming to listen.
     */
    public void release() {
        activity.runOnUiThread(() -> {
            if (recognizer != null) {
                destroyRecognizer();
                emit("error", null, "paused");
            }
        });
    }

    /** Called from MainActivity.onResume: the page decides whether to listen again. */
    public void resumed() {
        emit("resumed", null, null);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
            recognizer = null;
        }
    }

    private void emitPermission(boolean granted) {
        try {
            emitJson(new JSONObject().put("type", "permission").put("granted", granted));
        } catch (Exception ignored) {
        }
    }

    private void emit(String type, String text, String code) {
        try {
            JSONObject event = new JSONObject().put("type", type);
            if (text != null) {
                event.put("text", text);
            }
            if (code != null) {
                event.put("code", code);
            }
            emitJson(event);
        } catch (Exception ignored) {
        }
    }

    private void emitJson(JSONObject event) {
        final String script = "window.__elpisSpeech && window.__elpisSpeech("
                + JSONObject.quote(event.toString()) + ")";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private static String firstResult(Bundle results) {
        if (results == null) {
            return "";
        }
        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return texts == null || texts.isEmpty() || texts.get(0) == null ? "" : texts.get(0);
    }

    private static String errorCode(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "no_match";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "speech_timeout";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "no_permission";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network";
            case SpeechRecognizer.ERROR_AUDIO: return "audio";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "busy";
            case SpeechRecognizer.ERROR_CLIENT: return "client";
            default: return "server_" + error;
        }
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { emit("ready", null, null); }
        @Override public void onBeginningOfSpeech() { emit("speech_start", null, null); }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { emit("end", null, null); }
        @Override public void onError(int error) { emit("error", null, errorCode(error)); }
        @Override public void onResults(Bundle results) { emit("final", firstResult(results), null); }
        @Override public void onPartialResults(Bundle partial) { emit("partial", firstResult(partial), null); }
        @Override public void onEvent(int eventType, Bundle params) { }
    }
}

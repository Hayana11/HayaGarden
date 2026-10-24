package xyz.lovestyle.home.canary;

import android.app.Activity;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * P2H-1 call output: his voice plus earpiece/speaker routing. VoiceBridge keeps
 * the microphone; this bridge only plays.
 *
 * The page passes play(callId, seq), never a URL: the address is built by
 * CallAudioUrls on the fixed host, and the WebView's own session cookie is
 * attached here as a request header, so nothing secret reaches JS and the page
 * cannot make the app fetch anything else.
 *
 * Playback and capture both use the Android communication audio path
 * (MODE_IN_COMMUNICATION, USAGE_VOICE_COMMUNICATION), which keeps routing and
 * the platform's AEC/preprocessing consistent. ExoPlayer must not manage audio
 * focus for USAGE_VOICE_COMMUNICATION, so handleAudioFocus is false (focus is P2H-2).
 *
 * Events reach the page through window.__elpisCallAudio(json):
 * started(seq), ended(seq), error(seq, code), route(earpiece|speaker|other).
 * All player and AudioManager work happens on the main thread.
 */
public final class CallAudioBridge implements AutoCloseable {
    private static final String TAG = "ElpisCallAudio";
    /** How long the platform gets to apply a device choice before the fallback is tried. */
    private static final long ROUTE_CHECK_MS = 700;
    private final Activity activity;
    private final WebView webView;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int routeRequest;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory httpFactory;
    private Object deviceListener;
    private boolean active;
    private boolean closed;
    private int currentSeq;
    private boolean currentStarted;
    private boolean wantSpeaker;

    public CallAudioBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return !closed && audioManager != null;
    }

    /** Enter call audio: communication mode, earpiece by default. */
    @JavascriptInterface
    public void begin() {
        activity.runOnUiThread(() -> {
            if (closed) return;
            beginOnMain();
        });
    }

    /** Play one of his lines; replaces whatever line was playing. */
    @JavascriptInterface
    public void play(String callId, double seq) {
        final String url = seq == Math.rint(seq) ? CallAudioUrls.audioUrl(callId, (long) seq) : null;
        final int lineSeq = url == null ? 0 : (int) seq;
        activity.runOnUiThread(() -> {
            if (closed) return;
            if (url == null) {
                emitSeq("error", lineSeq, "invalid_line");
                return;
            }
            beginOnMain();
            ExoPlayer current = player();
            String cookie = CookieManager.getInstance().getCookie(CallAudioUrls.ORIGIN);
            httpFactory.setDefaultRequestProperties(cookie == null || cookie.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.singletonMap("Cookie", cookie));
            currentSeq = lineSeq;
            currentStarted = false;
            current.setMediaItem(MediaItem.fromUri(url));
            current.prepare();
            current.setPlayWhenReady(true);
        });
    }

    /** Barge-in: silence now. No ended event; the page drops its queue. */
    @JavascriptInterface
    public void stop() {
        activity.runOnUiThread(this::stopOnMain);
    }

    @JavascriptInterface
    public void setSpeaker(boolean on) {
        activity.runOnUiThread(() -> {
            if (closed) return;
            wantSpeaker = on;
            if (active) {
                applyRoute();
            }
            emitRoute();
        });
    }

    /** Leave call audio: stop, give the device back, normal media routing. */
    @JavascriptInterface
    public void end() {
        activity.runOnUiThread(this::endOnMain);
    }

    @Override
    public void close() {
        activity.runOnUiThread(() -> {
            endOnMain();
            closed = true;
            if (player != null) {
                player.release();
                player = null;
            }
        });
    }

    private void beginOnMain() {
        if (active || audioManager == null) return;
        active = true;
        wantSpeaker = false;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        listenForDeviceChanges();
        applyRoute();
        emitRoute();
    }

    private void stopOnMain() {
        currentSeq = 0;
        currentStarted = false;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }

    private void endOnMain() {
        stopOnMain();
        if (!active || audioManager == null) return;
        active = false;
        wantSpeaker = false;
        routeRequest++;  // a pending route check must not act after the call
        stopListeningForDeviceChanges();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        }
        audioManager.setSpeakerphoneOn(false);  // undoes a fallback too
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    private ExoPlayer player() {
        if (player != null) return player;
        httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(20000);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_VOICE_COMMUNICATION)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build();
        player = new ExoPlayer.Builder(activity)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .setAudioAttributes(attributes, /* handleAudioFocus= */ false)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying && currentSeq > 0 && !currentStarted) {
                    currentStarted = true;
                    emitSeq("started", currentSeq, null);
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && currentSeq > 0) {
                    int seq = currentSeq;
                    currentSeq = 0;
                    emitSeq("ended", seq, null);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (currentSeq <= 0) return;
                int seq = currentSeq;
                currentSeq = 0;
                emitSeq("error", seq, error.getErrorCodeName());
            }
        });
        return player;
    }

    /**
     * Asks for the wanted device. Some builds refuse setCommunicationDevice or
     * accept it without switching, so the result is checked once the platform
     * had time to apply it, and setSpeakerphoneOn is tried as a fallback.
     */
    private void applyRoute() {
        final boolean speaker = wantSpeaker;
        final int request = ++routeRequest;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            audioManager.setSpeakerphoneOn(speaker);
            return;
        }
        int wanted = speaker
                ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
        StringBuilder types = new StringBuilder();
        boolean accepted = false;
        for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
            types.append(device.getType()).append(' ');
            if (!accepted && device.getType() == wanted) {
                accepted = audioManager.setCommunicationDevice(device);
            }
        }
        Log.i(TAG, "route speaker=" + speaker + " accepted=" + accepted + " available=[" + types.toString().trim() + "]");
        if (!accepted) {
            fallBackToSpeakerphone(speaker, request);
            return;
        }
        main.postDelayed(() -> {
            if (closed || !active || request != routeRequest) return;
            String want = speaker ? "speaker" : "earpiece";
            String now = currentRoute();
            if (!want.equals(now)) {
                Log.w(TAG, "route still " + now + " after setCommunicationDevice; wanted " + want);
                fallBackToSpeakerphone(speaker, request);
            }
        }, ROUTE_CHECK_MS);
    }

    /**
     * A device chosen with setCommunicationDevice stays in force until it is
     * cleared, so it is cleared before the legacy switch gets its turn. The
     * outcome is read (and reported) after the platform had time to apply it;
     * the device-change listener reports any later change too.
     */
    @SuppressWarnings("deprecation")
    private void fallBackToSpeakerphone(boolean speaker, int request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        }
        audioManager.setSpeakerphoneOn(speaker);
        Log.i(TAG, "fallback: cleared communication device, setSpeakerphoneOn(" + speaker + ")");
        main.postDelayed(() -> {
            if (closed || !active || request != routeRequest) return;
            Log.i(TAG, "after fallback route is " + currentRoute() + "; wanted " + (speaker ? "speaker" : "earpiece"));
            emitRoute();
        }, ROUTE_CHECK_MS);
    }

    /** What the platform says is in use, so the page's button follows the real route. */
    private String currentRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo device = audioManager.getCommunicationDevice();
            if (device == null) return "other";
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "speaker";
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return "earpiece";
            return "other";
        }
        return audioManager.isSpeakerphoneOn() ? "speaker" : "earpiece";
    }

    private void listenForDeviceChanges() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || deviceListener != null) return;
        AudioManager.OnCommunicationDeviceChangedListener listener = device -> {
            if (active && !closed) emitRoute();
        };
        audioManager.addOnCommunicationDeviceChangedListener(activity.getMainExecutor(), listener);
        deviceListener = listener;
    }

    private void stopListeningForDeviceChanges() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || deviceListener == null) return;
        audioManager.removeOnCommunicationDeviceChangedListener(
                (AudioManager.OnCommunicationDeviceChangedListener) deviceListener);
        deviceListener = null;
    }

    private void emitRoute() {
        if (audioManager == null) return;
        try {
            JSONObject event = new JSONObject();
            event.put("type", "route");
            event.put("route", active ? currentRoute() : (wantSpeaker ? "speaker" : "earpiece"));
            emitJson(event);
        } catch (Exception ignored) {
        }
    }

    private void emitSeq(String type, int seq, String code) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", type);
            event.put("seq", seq);
            if (code != null) event.put("code", code);
            emitJson(event);
        } catch (Exception ignored) {
        }
    }

    private void emitJson(JSONObject event) {
        final String script = "window.__elpisCallAudio && window.__elpisCallAudio("
                + JSONObject.quote(event.toString()) + ")";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }
}

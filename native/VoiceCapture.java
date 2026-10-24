package xyz.lovestyle.home.canary;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Background AudioRecord + AEC + Silero frame pump. */
public final class VoiceCapture implements AutoCloseable {
    public interface Listener {
        void onReady(boolean aecAvailable, boolean aecEnabled);
        void onSpeechStart();
        void onUtterance(short[] samples);
        void onDiscarded(String reason);
        void onError(String code);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean running;
    private boolean closed;
    private Thread worker;
    private AudioRecord audioRecord;

    public VoiceCapture(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        synchronized (lock) {
            if (closed || running) return;
            running = true;
            worker = new Thread(this::runCapture, "elpis-voice-capture");
            worker.start();
        }
    }

    public void stop() {
        Thread thread;
        AudioRecord record;
        synchronized (lock) {
            running = false;
            thread = worker;
            record = audioRecord;
        }
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        stop();
    }

    private void runCapture() {
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        SileroVad vad = null;
        boolean aecAvailable = false;
        boolean aecEnabled = false;
        try {
            byte[] model = readAsset("silero_vad.onnx");
            vad = new SileroVad(model);
            int minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_MASK, ENCODING);
            if (minimum <= 0) throw new IllegalStateException("AudioRecord buffer unavailable");
            int bufferBytes = Math.max(minimum, VoiceSegmenter.FRAME_SAMPLES * 2 * 4);
            record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_MASK, ENCODING, bufferBytes);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord is not initialized");
            }
            synchronized (lock) {
                audioRecord = record;
            }

            try {
                aecAvailable = AcousticEchoCanceler.isAvailable();
                if (aecAvailable) {
                    aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (aec != null) {
                        aecEnabled = aec.setEnabled(true) == AcousticEchoCanceler.SUCCESS;
                    }
                }
            } catch (RuntimeException ignored) {
                aecEnabled = false;
            }

            record.startRecording();
            listener.onReady(aecAvailable, aecEnabled);
            streamFrames(record, vad);
        } catch (SecurityException denied) {
            if (running) listener.onError("permission_denied");
        } catch (IllegalStateException failed) {
            if (running) listener.onError(vad == null ? "onnx_init" : "audio_init");
        } catch (Exception failed) {
            if (running) listener.onError(vad == null ? "onnx_init" : "capture_failed");
        } finally {
            running = false;
            if (aec != null) {
                try {
                    aec.setEnabled(false);
                } catch (RuntimeException ignored) {
                }
                aec.release();
            }
            if (record != null) record.release();
            if (vad != null) vad.close();
            synchronized (lock) {
                audioRecord = null;
                worker = null;
            }
        }
    }

    private void streamFrames(AudioRecord record, SileroVad vad) throws Exception {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        short[] readBuffer = new short[VoiceSegmenter.FRAME_SAMPLES * 2];
        short[] frame = new short[VoiceSegmenter.FRAME_SAMPLES];
        int frameSize = 0;
        while (running) {
            int read = record.read(readBuffer, 0, readBuffer.length);
            if (read < 0) throw new IllegalStateException("AudioRecord.read=" + read);
            if (read == 0) continue;
            int offset = 0;
            while (offset < read && running) {
                int count = Math.min(frame.length - frameSize, read - offset);
                System.arraycopy(readBuffer, offset, frame, frameSize, count);
                frameSize += count;
                offset += count;
                if (frameSize == frame.length) {
                    float probability = vad.predict(frame);
                    VoiceSegmenter.Result result = segmenter.accept(frame, probability);
                    if (result.speechStarted) listener.onSpeechStart();
                    if (result.utterance != null) listener.onUtterance(result.utterance);
                    if (result.discarded) listener.onDiscarded(result.discardReason);
                    frameSize = 0;
                }
            }
        }
        segmenter.reset();
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}

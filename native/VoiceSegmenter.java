package xyz.lovestyle.home.canary;

import java.util.Arrays;

/** Pure-Java hysteresis segmenter for 16 kHz, 512-sample Silero frames. */
public final class VoiceSegmenter {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 512;
    public static final float START_THRESHOLD = 0.50f;
    public static final float CONTINUE_THRESHOLD = 0.35f;
    public static final int PRE_ROLL_SAMPLES = SAMPLE_RATE / 2;
    public static final int END_SILENCE_SAMPLES = (SAMPLE_RATE * 700) / 1000;
    public static final int MIN_UTTERANCE_SAMPLES = (SAMPLE_RATE * 250) / 1000;
    public static final int MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 30;

    public static final class Result {
        public final boolean speechStarted;
        public final short[] utterance;
        public final boolean discarded;
        public final String discardReason;

        private Result(boolean speechStarted, short[] utterance,
                       boolean discarded, String discardReason) {
            this.speechStarted = speechStarted;
            this.utterance = utterance;
            this.discarded = discarded;
            this.discardReason = discardReason;
        }

        static Result none() {
            return new Result(false, null, false, null);
        }

        static Result started() {
            return new Result(true, null, false, null);
        }

        static Result utterance(short[] samples) {
            return new Result(false, samples, false, null);
        }

        static Result discarded(String reason) {
            return new Result(false, null, true, reason);
        }
    }

    private final short[] preRoll = new short[PRE_ROLL_SAMPLES];
    private short[] active = new short[FRAME_SAMPLES];
    private int preRollSize;
    private int preRollWrite;
    private int activeSize;
    private int voicedSamples;
    private int quietSamples;
    private boolean speaking;

    public synchronized Result accept(short[] frame, float probability) {
        if (frame == null || frame.length != FRAME_SAMPLES) {
            throw new IllegalArgumentException("expected exactly 512 PCM samples");
        }
        if (!speaking) {
            appendPreRoll(frame);
            if (probability >= START_THRESHOLD) {
                speaking = true;
                activeSize = 0;
                voicedSamples = 0;
                quietSamples = 0;
                copyPreRollToActive();
                if (probability >= CONTINUE_THRESHOLD) voicedSamples += frame.length;
                return Result.started();
            }
            return Result.none();
        }

        appendActive(frame);
        if (probability >= CONTINUE_THRESHOLD) {
            quietSamples = 0;
            voicedSamples += frame.length;
        } else {
            quietSamples += frame.length;
        }
        if (activeSize >= MAX_UTTERANCE_SAMPLES) {
            return finish();
        }
        if (quietSamples >= END_SILENCE_SAMPLES) {
            return finish();
        }
        return Result.none();
    }

    /** Flushes a live utterance; normal pause/stop paths call reset instead. */
    public synchronized Result flush() {
        return speaking ? finish() : Result.none();
    }

    public synchronized void reset() {
        preRollSize = 0;
        preRollWrite = 0;
        activeSize = 0;
        voicedSamples = 0;
        quietSamples = 0;
        speaking = false;
    }

    public synchronized boolean isSpeaking() {
        return speaking;
    }

    private void appendPreRoll(short[] frame) {
        for (short sample : frame) {
            preRoll[preRollWrite] = sample;
            preRollWrite = (preRollWrite + 1) % preRoll.length;
            if (preRollSize < preRoll.length) preRollSize++;
        }
    }

    private void copyPreRollToActive() {
        ensureCapacity(preRollSize);
        int start = (preRollWrite - preRollSize + preRoll.length) % preRoll.length;
        for (int i = 0; i < preRollSize; i++) {
            active[i] = preRoll[(start + i) % preRoll.length];
        }
        activeSize = preRollSize;
    }

    private void appendActive(short[] frame) {
        int count = Math.min(frame.length, MAX_UTTERANCE_SAMPLES - activeSize);
        ensureCapacity(activeSize + count);
        System.arraycopy(frame, 0, active, activeSize, count);
        activeSize += count;
    }

    private void ensureCapacity(int needed) {
        if (needed <= active.length) return;
        int capacity = active.length;
        while (capacity < needed && capacity < MAX_UTTERANCE_SAMPLES) {
            capacity = Math.min(MAX_UTTERANCE_SAMPLES, capacity * 2);
        }
        active = Arrays.copyOf(active, capacity);
    }

    private Result finish() {
        short[] result = Arrays.copyOf(active, activeSize);
        boolean valid = voicedSamples >= MIN_UTTERANCE_SAMPLES;
        reset();
        return valid ? Result.utterance(result) : Result.discarded("too_short");
    }
}

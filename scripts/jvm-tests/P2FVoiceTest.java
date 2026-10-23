package xyz.lovestyle.home.canary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** JVM-only deterministic coverage for the P2F segmenter and WAV contract. */
public final class P2FVoiceTest {
    private static final short[] LOW = new short[VoiceSegmenter.FRAME_SAMPLES];

    public static void main(String[] args) {
        testSilence();
        testTooShortNoise();
        testSpeechAndSilence();
        testTwoUtterancesAndPreroll();
        testContinueHysteresis();
        testMaxFlush();
        testReset();
        testWav();
        System.out.println("P2F voice JVM tests passed");
    }

    private static void testSilence() {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        for (int i = 0; i < 40; i++) {
            check(segmenter.accept(LOW, 0.0f).utterance == null, "silence emitted audio");
        }
    }

    private static void testTooShortNoise() {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        segmenter.accept(LOW, 0.8f);
        VoiceSegmenter.Result result = null;
        for (int i = 0; i < 22; i++) result = segmenter.accept(LOW, 0.0f);
        check(result != null && result.discarded, "short noise was not discarded");
        check("too_short".equals(result.discardReason), "wrong short-noise reason");
    }

    private static void testSpeechAndSilence() {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        for (int i = 0; i < 16; i++) segmenter.accept(LOW, 0.0f);
        segmenter.accept(LOW, 0.8f);
        for (int i = 0; i < 10; i++) segmenter.accept(LOW, 0.8f);
        VoiceSegmenter.Result result = null;
        for (int i = 0; i < 22; i++) result = segmenter.accept(LOW, 0.0f);
        check(result != null && result.utterance != null, "speech did not finish");
        check(result.utterance.length >= 8000, "speech lost pre-roll or silence");
    }

    private static void testTwoUtterancesAndPreroll() {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        short[] first = new short[VoiceSegmenter.FRAME_SAMPLES];
        Arrays.fill(first, (short) 111);
        for (int i = 0; i < 16; i++) segmenter.accept(first, 0.0f);
        segmenter.accept(first, 0.8f);
        for (int i = 0; i < 10; i++) segmenter.accept(first, 0.8f);
        VoiceSegmenter.Result one = null;
        for (int i = 0; i < 22; i++) one = segmenter.accept(first, 0.0f);
        check(one != null && one.utterance != null, "first utterance missing");
        check(one.utterance[0] == 111, "pre-roll was not included");

        short[] second = new short[VoiceSegmenter.FRAME_SAMPLES];
        Arrays.fill(second, (short) 222);
        segmenter.accept(second, 0.8f);
        for (int i = 0; i < 10; i++) segmenter.accept(second, 0.8f);
        VoiceSegmenter.Result two = null;
        for (int i = 0; i < 22; i++) two = segmenter.accept(second, 0.0f);
        check(two != null && two.utterance != null, "second utterance missing");
        check(two.utterance[0] == 222, "second utterance retained old pre-roll");
    }

    private static void testContinueHysteresis() {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        segmenter.accept(LOW, 0.8f);
        for (int i = 0; i < 10; i++) {
            check(!segmenter.accept(LOW, 0.40f).discarded, "continue threshold ended early");
        }
        VoiceSegmenter.Result result = null;
        for (int i = 0; i < 22; i++) result = segmenter.accept(LOW, 0.0f);
        check(result != null && result.discarded, "hysteresis silence did not end");
    }

    private static void testMaxFlush() {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        segmenter.accept(LOW, 0.8f);
        VoiceSegmenter.Result result = null;
        for (int i = 0; i < 1000 && result == null; i++) {
            result = segmenter.accept(LOW, 0.8f);
        }
        check(result != null && result.utterance != null, "max utterance did not flush");
        check(result.utterance.length <= VoiceSegmenter.MAX_UTTERANCE_SAMPLES,
                "max utterance exceeded 30 seconds");
    }

    private static void testReset() {
        VoiceSegmenter segmenter = new VoiceSegmenter();
        segmenter.accept(LOW, 0.8f);
        segmenter.reset();
        check(!segmenter.isSpeaking(), "reset left speaking state");
        for (int i = 0; i < 22; i++) {
            check(!segmenter.accept(LOW, 0.0f).discarded, "reset retained old samples");
        }
    }

    private static void testWav() {
        byte[] wav = WavEncoder.encode(new short[]{(short) 0x1234, (short) 0xFEDC}, 16000);
        check(wav.length == 48, "wrong WAV length");
        check(wav[0] == 'R' && wav[1] == 'I' && wav[8] == 'W' && wav[9] == 'A',
                "missing RIFF/WAVE");
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        header.position(24);
        check(header.getInt() == 16000, "wrong sample rate");
        header.position(40);
        check(header.getInt() == 4, "wrong data size");
        check((wav[44] & 0xff) == 0x34 && (wav[45] & 0xff) == 0x12,
                "PCM is not little endian");
        check((wav[46] & 0xff) == 0xdc && (wav[47] & 0xff) == 0xfe,
                "second PCM sample is wrong");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

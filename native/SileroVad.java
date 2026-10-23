package xyz.lovestyle.home.canary;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Silero VAD 5.1.2 ONNX wrapper.
 *
 * The vendored model was loaded and inspected before this wrapper was written:
 * input=[batch, samples] float32, state=[2,batch,128] float32, sr=int64 scalar;
 * output=[batch,1] float32 and stateN=[batch,?,?] float32. For 16 kHz each
 * input is 64 samples of previous context plus the 512-sample current frame.
 */
public final class SileroVad implements AutoCloseable {
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 512;
    private static final int CONTEXT_SAMPLES = 64;
    private static final int STATE_LAYERS = 2;
    private static final int STATE_WIDTH = 128;

    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions options;
    private final OrtSession session;
    private final float[] context = new float[CONTEXT_SAMPLES];
    private final float[][][] state = new float[STATE_LAYERS][1][STATE_WIDTH];
    private boolean closed;

    public SileroVad(byte[] modelBytes) throws OrtException {
        if (modelBytes == null || modelBytes.length == 0) {
            throw new IllegalArgumentException("Silero model is empty");
        }
        environment = OrtEnvironment.getEnvironment();
        options = new OrtSession.SessionOptions();
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(1);
        session = environment.createSession(modelBytes, options);
        validateModelContract();
    }

    public synchronized float predict(short[] frame) throws OrtException {
        if (closed) throw new IllegalStateException("SileroVad is closed");
        if (frame == null || frame.length != FRAME_SAMPLES) {
            throw new IllegalArgumentException("expected exactly 512 PCM samples");
        }
        float[][] input = new float[1][CONTEXT_SAMPLES + FRAME_SAMPLES];
        System.arraycopy(context, 0, input[0], 0, CONTEXT_SAMPLES);
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            input[0][CONTEXT_SAMPLES + i] = frame[i] / 32768.0f;
        }

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(environment, input);
             OnnxTensor stateTensor = OnnxTensor.createTensor(environment, state);
             OnnxTensor srTensor = OnnxTensor.createTensor(
                     environment, LongBuffer.wrap(new long[]{SAMPLE_RATE}), new long[0])) {
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                Object probabilityValue = result.get(0).getValue();
                Object stateValue = result.get(1).getValue();
                if (!(probabilityValue instanceof float[][])
                        || !(stateValue instanceof float[][][])) {
                    throw new IllegalStateException("unexpected Silero output types");
                }
                float[][] probability = (float[][]) probabilityValue;
                float[][][] nextState = (float[][][]) stateValue;
                if (probability.length != 1 || probability[0].length < 1
                        || nextState.length != STATE_LAYERS
                        || nextState[0].length != 1
                        || nextState[0][0].length != STATE_WIDTH) {
                    throw new IllegalStateException("unexpected Silero output shapes");
                }
                copyState(nextState);
                System.arraycopy(input[0], FRAME_SAMPLES, context, 0, CONTEXT_SAMPLES);
                return probability[0][0];
            }
        }
    }

    public synchronized void reset() {
        for (int i = 0; i < context.length; i++) context[i] = 0.0f;
        for (int layer = 0; layer < state.length; layer++) {
            for (int batch = 0; batch < state[layer].length; batch++) {
                for (int value = 0; value < state[layer][batch].length; value++) {
                    state[layer][batch][value] = 0.0f;
                }
            }
        }
    }

    private void copyState(float[][][] nextState) {
        for (int layer = 0; layer < STATE_LAYERS; layer++) {
            System.arraycopy(nextState[layer][0], 0, state[layer][0], 0, STATE_WIDTH);
        }
    }

    private void validateModelContract() {
        Map<String, NodeInfo> inputs = session.getInputInfo();
        Map<String, NodeInfo> outputs = session.getOutputInfo();
        requireRank(inputs, "input", 2);
        requireRank(inputs, "state", 3);
        requireRank(inputs, "sr", 0);
        requireRank(outputs, "output", 2);
        requireRank(outputs, "stateN", 3);
    }

    private static void requireRank(Map<String, NodeInfo> nodes, String name, int rank) {
        NodeInfo node = nodes.get(name);
        if (node == null || !(node.getInfo() instanceof TensorInfo)) {
            throw new IllegalStateException("Silero tensor missing: " + name);
        }
        long[] shape = ((TensorInfo) node.getInfo()).getShape();
        if (shape == null || shape.length != rank) {
            throw new IllegalStateException(
                    "Silero tensor " + name + " rank mismatch: expected " + rank);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        session.close();
        options.close();
    }
}

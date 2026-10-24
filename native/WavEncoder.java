package xyz.lovestyle.home.canary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal RIFF/WAVE encoder for mono PCM16 utterances. */
public final class WavEncoder {
    private WavEncoder() {}

    public static byte[] encode(short[] samples, int sampleRate) {
        if (samples == null || sampleRate <= 0) {
            throw new IllegalArgumentException("samples and sample rate are required");
        }
        int dataSize = samples.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        header.putInt(36 + dataSize);
        header.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        header.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        header.putInt(dataSize);

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        try {
            out.write(header.array());
            for (short sample : samples) {
                out.write(sample & 0xff);
                out.write((sample >>> 8) & 0xff);
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return out.toByteArray();
    }
}

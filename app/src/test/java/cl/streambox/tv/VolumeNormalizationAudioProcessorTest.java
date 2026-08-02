package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VolumeNormalizationAudioProcessorTest {
    @Test
    public void raisesQuietSignalWithoutExceedingLimiter() throws Exception {
        VolumeNormalizationAudioProcessor processor = new VolumeNormalizationAudioProcessor();
        AudioProcessor.AudioFormat format = new AudioProcessor.AudioFormat(
                48_000,
                2,
                C.ENCODING_PCM_16BIT
        );
        assertEquals(format, processor.configure(format));
        processor.flush();

        ByteBuffer input = ByteBuffer.allocateDirect(48_000 * 2 * 2)
                .order(ByteOrder.nativeOrder());
        for (int frame = 0; frame < 48_000; frame++) {
            short sample = (short) (0.04f * 32767f);
            input.putShort(sample);
            input.putShort(sample);
        }
        input.flip();
        processor.queueInput(input);
        ByteBuffer output = processor.getOutput().order(ByteOrder.nativeOrder());

        int first = Math.abs(output.getShort(0));
        int last = Math.abs(output.getShort(output.limit() - 4));
        assertTrue(last > first);
        assertTrue(last <= 29_214);
    }

    @Test
    public void resetsGainWhenFlushed() throws Exception {
        VolumeNormalizationAudioProcessor processor = new VolumeNormalizationAudioProcessor();
        AudioProcessor.AudioFormat format = new AudioProcessor.AudioFormat(
                48_000,
                1,
                C.ENCODING_PCM_FLOAT
        );
        processor.configure(format);
        processor.flush();

        ByteBuffer input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        input.putFloat(0.04f).flip();
        processor.queueInput(input);
        float processed = processor.getOutput().order(ByteOrder.nativeOrder()).getFloat();

        processor.flush();
        ByteBuffer afterReset = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        afterReset.putFloat(0.04f).flip();
        processor.queueInput(afterReset);
        float resetValue = processor.getOutput().order(ByteOrder.nativeOrder()).getFloat();

        assertTrue(processed > resetValue);
        assertTrue(resetValue > 0f);
    }
}

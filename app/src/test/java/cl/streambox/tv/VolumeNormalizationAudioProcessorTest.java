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

    /** Canal bajo (~−31 dBFS, como Studio Universal): con +15 dB sube más que con el tope anterior. */
    @Test
    public void quietChannelReachesTargetLevel() throws Exception {
        float[] out = runSine(0.028f, 20);
        double rms = rms(out, out.length - 48_000, out.length);
        // Con el tope anterior (+9 dB) quedaba en 0.028 × 2.82 ≈ 0.079.
        assertTrue("rms " + rms, rms > 0.09);
    }

    /** Señal fuerte: el limitador suave nunca pasa de −1 dBFS. */
    @Test
    public void softLimiterNeverExceedsCeiling() throws Exception {
        float[] out = runSine(0.95f, 3);
        float peak = 0f;
        for (float value : out) peak = Math.max(peak, Math.abs(value));
        assertTrue("pico " + peak, peak <= VolumeNormalizationAudioProcessor.LIMIT + 1e-6f);
        assertEquals(0.5f, VolumeNormalizationAudioProcessor.softLimit(0.5f), 0f);
        assertTrue(VolumeNormalizationAudioProcessor.softLimit(3f) <= VolumeNormalizationAudioProcessor.LIMIT);
    }

    /** Ruido de fondo (~−48 dBFS) después del programa: la ganancia no sube a +15 dB. */
    @Test
    public void silenceDoesNotPumpBackgroundNoise() throws Exception {
        VolumeNormalizationAudioProcessor processor = floatProcessor();
        process(processor, sine(0.126f, 5));
        float[] quiet = process(processor, sine(0.004f, 10));
        double rms = rms(quiet, quiet.length - 48_000, quiet.length);
        assertTrue("rms " + rms, rms < 0.004 * 1.5);
    }

    private static VolumeNormalizationAudioProcessor floatProcessor() throws Exception {
        VolumeNormalizationAudioProcessor processor = new VolumeNormalizationAudioProcessor();
        processor.configure(new AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT));
        processor.flush();
        return processor;
    }

    private static float[] runSine(float amplitude, int seconds) throws Exception {
        return process(floatProcessor(), sine(amplitude, seconds));
    }

    private static float[] sine(float rmsLevel, int seconds) {
        float[] samples = new float[48_000 * seconds];
        float amplitude = rmsLevel * (float) Math.sqrt(2d);
        for (int index = 0; index < samples.length; index++) {
            samples[index] = amplitude * (float) Math.sin(2d * Math.PI * 440d * index / 48_000d);
        }
        return samples;
    }

    private static float[] process(VolumeNormalizationAudioProcessor processor, float[] samples) {
        ByteBuffer input = ByteBuffer.allocateDirect(samples.length * 4).order(ByteOrder.nativeOrder());
        for (float sample : samples) input.putFloat(sample);
        input.flip();
        processor.queueInput(input);
        ByteBuffer output = processor.getOutput().order(ByteOrder.nativeOrder());
        float[] result = new float[output.remaining() / 4];
        for (int index = 0; index < result.length; index++) result[index] = output.getFloat();
        return result;
    }

    private static double rms(float[] samples, int from, int to) {
        double sum = 0d;
        for (int index = from; index < to; index++) sum += samples[index] * samples[index];
        return Math.sqrt(sum / Math.max(1, to - from));
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

        ByteBuffer input = ByteBuffer.allocateDirect(4 * 48_000).order(ByteOrder.nativeOrder());
        for (int index = 0; index < 48_000; index++) input.putFloat(0.04f);
        input.flip();
        processor.queueInput(input);
        ByteBuffer processedOutput = processor.getOutput().order(ByteOrder.nativeOrder());
        float processed = processedOutput.getFloat(processedOutput.limit() - 4);

        processor.flush();
        ByteBuffer afterReset = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        afterReset.putFloat(0.04f).flip();
        processor.queueInput(afterReset);
        float resetValue = processor.getOutput().order(ByteOrder.nativeOrder()).getFloat();

        assertTrue(processed > resetValue);
        assertTrue(resetValue > 0f);
    }
}

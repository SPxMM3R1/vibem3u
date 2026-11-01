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
            short sample = (short) (0.04f * 32767f * Math.sin(2d * Math.PI * 440d * frame / 48_000d));
            input.putShort(sample);
            input.putShort(sample);
        }
        input.flip();
        processor.queueInput(input);
        ByteBuffer output = processor.getOutput().order(ByteOrder.nativeOrder());

        int first = 0;
        int last = 0;
        for (int index = 0; index < 4_800; index++) {
            first = Math.max(first, Math.abs(output.getShort(index * 4)));
            last = Math.max(last, Math.abs(output.getShort(output.limit() - 4 - index * 4)));
        }
        assertTrue(last > first);
        assertTrue(last <= 29_214);
    }

    /** Canal muy bajo (−38 dBFS): con +20 dB (0.6.47) llega cerca del objetivo. */
    @Test
    public void veryQuietChannelReachesTargetLevel() throws Exception {
        float[] out = runSine(0.0126f, 20);
        double rms = rms(out, out.length - 48_000, out.length);
        assertTrue("rms " + rms, rms > VolumeNormalizationAudioProcessor.TARGET_RMS * 0.8);
    }

    /** Canal fuerte (−6 dBFS): baja hasta cerca del objetivo en pocos segundos. */
    @Test
    public void loudChannelIsLowered() throws Exception {
        float[] out = runSine(0.5f, 5);
        double rms = rms(out, out.length - 48_000, out.length);
        assertTrue("rms " + rms, rms < VolumeNormalizationAudioProcessor.TARGET_RMS * 1.3);
    }

    /** Ponderación K: a igual nivel eléctrico, graves (60 Hz) cuentan menos que agudos (3 kHz). */
    @Test
    public void bassHeavyChannelIsNotTreatedAsLouder() throws Exception {
        float[] bass = process(floatProcessor(), tone(0.126f, 60d, 10));
        float[] treble = process(floatProcessor(), tone(0.126f, 3_000d, 10));
        double bassOut = rms(bass, bass.length - 48_000, bass.length);
        double trebleOut = rms(treble, treble.length - 48_000, treble.length);
        assertTrue("graves " + bassOut + " agudos " + trebleOut, bassOut > trebleOut * 1.2);
    }

    /** Canal bajo (−35 dBFS): necesita ~+13 dB, más que el tope de +9 dB de antes de la 0.6.38. */
    @Test
    public void quietChannelReachesTargetLevel() throws Exception {
        float[] out = runSine(0.018f, 20);
        double rms = rms(out, out.length - 48_000, out.length);
        // Con el tope de +9 dB quedaba en 0.018 × 2.82 ≈ 0.051.
        assertTrue("rms " + rms, rms > VolumeNormalizationAudioProcessor.TARGET_RMS * 0.8);
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
        return tone(rmsLevel, 440d, seconds);
    }

    private static float[] tone(float rmsLevel, double frequency, int seconds) {
        float[] samples = new float[48_000 * seconds];
        float amplitude = rmsLevel * (float) Math.sqrt(2d);
        for (int index = 0; index < samples.length; index++) {
            samples[index] = amplitude * (float) Math.sin(2d * Math.PI * frequency * index / 48_000d);
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

        float[] processed = process(processor, sine(0.04f, 2));
        double raised = rms(processed, processed.length - 4_800, processed.length);

        processor.flush();
        float[] afterReset = process(processor, sine(0.04f, 1));
        double reset = rms(afterReset, 0, 480);

        assertTrue(raised > reset);
        assertTrue(reset > 0d);
    }
}

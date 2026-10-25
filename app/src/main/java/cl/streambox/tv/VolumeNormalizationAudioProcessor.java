package cl.streambox.tv;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Applies slow automatic gain control to decoded PCM while keeping a conservative peak limit.
 * The slow response avoids changing the volume for every speech transient.
 *
 * <p>0.6.38: también sube los canales bajos (Alcatel ~−25 LUFS, Studio Universal ~−31 LUFS):
 * hasta +15 dB en vez de +9 dB, con un limitador suave en lugar de recortar (sin distorsión
 * rasposa) y sin subir la ganancia en los silencios (no amplifica el ruido de fondo).
 */
@UnstableApi
final class VolumeNormalizationAudioProcessor extends BaseAudioProcessor {
    private static final float TARGET_RMS = 0.12589254f;
    private static final float MIN_MEASURABLE_RMS = 0.004f;
    private static final float MIN_GAIN = 0.25f;
    /** +15 dB. */
    static final float MAX_GAIN = 5.623413f;
    /** −1 dBFS: techo del limitador suave. */
    static final float LIMIT = 0.8912509f;
    /** Desde aquí (≈ −4 dBFS) el limitador empieza a redondear los picos. */
    private static final float KNEE = 0.63f;
    /** Bajo ≈ −40 dBFS es silencio o ruido de fondo: la ganancia no sube. */
    private static final float GATE_RMS = 0.01f;

    private int encoding;
    private int sampleRate;
    private float levelSquared;
    /** Nivel de los últimos ~50 ms: decide si es silencio sin esperar al nivel lento. */
    private float shortSquared;
    private float shortStep;
    private float gain = 1f;
    private float attackStep;
    private float releaseStep;

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new AudioProcessor.UnhandledAudioFormatException(inputAudioFormat);
        }
        encoding = inputAudioFormat.encoding;
        sampleRate = Math.max(1, inputAudioFormat.sampleRate);
        attackStep = 1f - (float) Math.exp(-1d / (sampleRate * 0.08d));
        releaseStep = 1f - (float) Math.exp(-1d / (sampleRate * 1.8d));
        shortStep = 1f - (float) Math.exp(-1d / (sampleRate * 0.05d));
        resetLevels();
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int inputBytes = inputBuffer.remaining();
        ByteBuffer outputBuffer = replaceOutputBuffer(inputBytes);
        outputBuffer.order(ByteOrder.nativeOrder());
        inputBuffer.order(ByteOrder.nativeOrder());

        if (encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2) {
                outputBuffer.putShort((short) Math.round(
                        processSample(inputBuffer.getShort() / 32768f) * 32767f
                ));
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                outputBuffer.putFloat(processSample(inputBuffer.getFloat()));
            }
        }
        inputBuffer.position(inputBuffer.limit());
        outputBuffer.flip();
    }

    @Override
    protected void onFlush(AudioProcessor.StreamMetadata streamMetadata) {
        resetLevels();
    }

    @Override
    protected void onReset() {
        resetLevels();
        encoding = FormatEncoding.UNKNOWN;
        sampleRate = 0;
    }

    private float processSample(float input) {
        float sample = clamp(input, -1f, 1f);
        float squared = sample * sample;
        float envelopeStep = squared > levelSquared ? attackStep : releaseStep;
        levelSquared += (squared - levelSquared) * envelopeStep;
        float rms = (float) Math.sqrt(levelSquared);
        shortSquared += (squared - shortSquared) * shortStep;
        boolean silent = shortSquared < GATE_RMS * GATE_RMS;

        if (rms >= MIN_MEASURABLE_RMS) {
            float targetGain = clamp(TARGET_RMS / rms, MIN_GAIN, MAX_GAIN);
            // En silencio solo puede bajar: así no se amplifica el ruido entre diálogos.
            if (targetGain < gain || !silent) {
                float gainStep = targetGain < gain ? attackStep : releaseStep;
                gain += (targetGain - gain) * gainStep;
            }
        }

        return softLimit(sample * gain);
    }

    /** Lineal hasta {@link #KNEE}; encima, curva suave que nunca pasa de {@link #LIMIT}. */
    static float softLimit(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= KNEE) return value;
        float room = LIMIT - KNEE;
        float shaped = KNEE + room * (float) Math.tanh((magnitude - KNEE) / room);
        return value < 0f ? -shaped : shaped;
    }

    private void resetLevels() {
        levelSquared = 0f;
        shortSquared = 0f;
        gain = 1f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class FormatEncoding {
        static final int UNKNOWN = 0;

        private FormatEncoding() {}
    }
}

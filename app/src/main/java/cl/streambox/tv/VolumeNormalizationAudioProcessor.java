package cl.streambox.tv;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Applies automatic gain control to decoded PCM while keeping a conservative peak limit.
 *
 * <p>0.6.38: también sube los canales bajos (Alcatel ~−25 LUFS, Studio Universal ~−31 LUFS),
 * con un limitador suave en lugar de recortar (sin distorsión rasposa) y sin subir la ganancia
 * en los silencios (no amplifica el ruido de fondo).
 *
 * <p>0.6.47 (pedido del usuario «puede ser más agresivo para que todos los canales tengan el
 * mismo volumen»): el nivel se mide con la ponderación K de la norma de sonoridad (ITU-R
 * BS.1770, la de los LUFS), así un canal con muchos graves ya no cuenta como fuerte; el
 * rango sube a ±20 dB (antes −12/+15) y la ganancia sigue más rápido al nivel: baja en
 * ~0,2 s y sube en ~1 s (antes 1,8 s). Todos los canales de un cuadro llevan la misma
 * ganancia (no se mueve la imagen estéreo).
 */
@UnstableApi
final class VolumeNormalizationAudioProcessor extends BaseAudioProcessor {
    /**
     * −22 dB RMS ponderado (≈ −20 LUFS momentáneos en estéreo; un programa queda en ~−22,5
     * LUFS integrados). 0.6.49, pedido del usuario («siento que hay canales que están haciendo
     * clipping… reducirlo a 20»): antes −18 dB (≈ −16 LUFS), con más trabajo del limitador.
     */
    static final float TARGET_RMS = 0.07943282f;
    private static final float MIN_MEASURABLE_RMS = 0.002f;
    /** −20 dB. */
    static final float MIN_GAIN = 0.1f;
    /** +20 dB. */
    static final float MAX_GAIN = 10f;
    /** −1 dBFS: techo del limitador suave. */
    static final float LIMIT = 0.8912509f;
    /** Desde aquí (≈ −4 dBFS) el limitador empieza a redondear los picos. */
    private static final float KNEE = 0.63f;
    /** Bajo ≈ −40 dBFS es silencio o ruido de fondo: la ganancia no sube. */
    private static final float GATE_RMS = 0.01f;

    private int encoding;
    private int channelCount;
    /** Sonoridad ponderada: sube en ~0,1 s y baja en ~0,4 s (ventana «momentánea» de LUFS). */
    private float levelSquared;
    /** Nivel de los últimos ~50 ms sin ponderar: decide si es silencio. */
    private float shortSquared;
    private float gain = 1f;
    private float levelAttack;
    private float levelRelease;
    private float shortStep;
    private float gainDown;
    private float gainUp;
    private float[] frame = new float[0];
    private KWeighting[] weighting = new KWeighting[0];

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new AudioProcessor.UnhandledAudioFormatException(inputAudioFormat);
        }
        encoding = inputAudioFormat.encoding;
        int sampleRate = Math.max(1, inputAudioFormat.sampleRate);
        channelCount = Math.max(1, inputAudioFormat.channelCount);
        levelAttack = step(sampleRate, 0.1d);
        levelRelease = step(sampleRate, 0.4d);
        shortStep = step(sampleRate, 0.05d);
        gainDown = step(sampleRate, 0.2d);
        gainUp = step(sampleRate, 1.0d);
        frame = new float[channelCount];
        weighting = new KWeighting[channelCount];
        for (int channel = 0; channel < channelCount; channel++) {
            weighting[channel] = new KWeighting(sampleRate);
        }
        resetLevels();
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int inputBytes = inputBuffer.remaining();
        ByteBuffer outputBuffer = replaceOutputBuffer(inputBytes);
        outputBuffer.order(ByteOrder.nativeOrder());
        inputBuffer.order(ByteOrder.nativeOrder());

        boolean pcm16 = encoding == C.ENCODING_PCM_16BIT;
        int frameBytes = channelCount * (pcm16 ? 2 : 4);
        while (inputBuffer.remaining() >= frameBytes) {
            for (int channel = 0; channel < channelCount; channel++) {
                frame[channel] = pcm16 ? inputBuffer.getShort() / 32768f : inputBuffer.getFloat();
            }
            float frameGain = updateGain();
            for (int channel = 0; channel < channelCount; channel++) {
                float value = softLimit(frame[channel] * frameGain);
                if (pcm16) {
                    outputBuffer.putShort((short) Math.round(value * 32767f));
                } else {
                    outputBuffer.putFloat(value);
                }
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
        channelCount = 0;
    }

    /** Mide el cuadro en {@link #frame} y devuelve la ganancia que le corresponde. */
    private float updateGain() {
        float weightedSquared = 0f;
        float plainSquared = 0f;
        for (int channel = 0; channel < channelCount; channel++) {
            float sample = clamp(frame[channel], -1f, 1f);
            frame[channel] = sample;
            float weighted = weighting[channel].process(sample);
            weightedSquared += weighted * weighted;
            plainSquared += sample * sample;
        }
        weightedSquared /= channelCount;
        plainSquared /= channelCount;

        levelSquared += (weightedSquared - levelSquared)
                * (weightedSquared > levelSquared ? levelAttack : levelRelease);
        shortSquared += (plainSquared - shortSquared) * shortStep;
        boolean silent = shortSquared < GATE_RMS * GATE_RMS;
        float rms = (float) Math.sqrt(levelSquared);

        if (rms >= MIN_MEASURABLE_RMS) {
            float targetGain = clamp(TARGET_RMS / rms, MIN_GAIN, MAX_GAIN);
            // En silencio solo puede bajar: así no se amplifica el ruido entre diálogos.
            if (targetGain < gain) {
                gain += (targetGain - gain) * gainDown;
            } else if (!silent) {
                gain += (targetGain - gain) * gainUp;
            }
        }
        return gain;
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
        for (KWeighting filter : weighting) filter.reset();
    }

    private static float step(int sampleRate, double seconds) {
        return 1f - (float) Math.exp(-1d / (sampleRate * seconds));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * Ponderación K de ITU-R BS.1770 (realce de agudos + filtro de graves), con los
     * coeficientes calculados para la frecuencia de muestreo (mismas fórmulas que libebur128).
     */
    static final class KWeighting {
        private final double shelfB0, shelfB1, shelfB2, shelfA1, shelfA2;
        private final double passA1, passA2;
        private double shelfX1, shelfX2, shelfY1, shelfY2;
        private double passX1, passX2, passY1, passY2;

        KWeighting(int sampleRate) {
            double f0 = 1681.974450955533d;
            double gainDb = 3.999843853973347d;
            double q = 0.7071752369554196d;
            double k = Math.tan(Math.PI * f0 / sampleRate);
            double vh = Math.pow(10d, gainDb / 20d);
            double vb = Math.pow(vh, 0.4996667741545416d);
            double a0 = 1d + k / q + k * k;
            shelfB0 = (vh + vb * k / q + k * k) / a0;
            shelfB1 = 2d * (k * k - vh) / a0;
            shelfB2 = (vh - vb * k / q + k * k) / a0;
            shelfA1 = 2d * (k * k - 1d) / a0;
            shelfA2 = (1d - k / q + k * k) / a0;

            f0 = 38.13547087602444d;
            q = 0.5003270373238773d;
            k = Math.tan(Math.PI * f0 / sampleRate);
            double passA0 = 1d + k / q + k * k;
            passA1 = 2d * (k * k - 1d) / passA0;
            passA2 = (1d - k / q + k * k) / passA0;
        }

        float process(float input) {
            double shelf = shelfB0 * input + shelfB1 * shelfX1 + shelfB2 * shelfX2
                    - shelfA1 * shelfY1 - shelfA2 * shelfY2;
            shelfX2 = shelfX1;
            shelfX1 = input;
            shelfY2 = shelfY1;
            shelfY1 = shelf;
            double pass = shelf - 2d * passX1 + passX2 - passA1 * passY1 - passA2 * passY2;
            passX2 = passX1;
            passX1 = shelf;
            passY2 = passY1;
            passY1 = pass;
            return (float) pass;
        }

        void reset() {
            shelfX1 = shelfX2 = shelfY1 = shelfY2 = 0d;
            passX1 = passX2 = passY1 = passY2 = 0d;
        }
    }

    private static final class FormatEncoding {
        static final int UNKNOWN = 0;

        private FormatEncoding() {}
    }
}

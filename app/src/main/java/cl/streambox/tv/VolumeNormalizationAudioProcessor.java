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
 */
@UnstableApi
final class VolumeNormalizationAudioProcessor extends BaseAudioProcessor {
    private static final float TARGET_RMS = 0.12589254f;
    private static final float MIN_MEASURABLE_RMS = 0.004f;
    private static final float MIN_GAIN = 0.25f;
    private static final float MAX_GAIN = 2.818383f;
    private static final float LIMIT = 0.8912509f;

    private int encoding;
    private int sampleRate;
    private float levelSquared;
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

        if (rms >= MIN_MEASURABLE_RMS) {
            float targetGain = clamp(TARGET_RMS / rms, MIN_GAIN, MAX_GAIN);
            float gainStep = targetGain < gain ? attackStep : releaseStep;
            gain += (targetGain - gain) * gainStep;
        }

        return clamp(sample * gain, -LIMIT, LIMIT);
    }

    private void resetLevels() {
        levelSquared = 0f;
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

package cl.streambox.tv;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Decodificador Media3 de MP2 en software (0.6.06): cada búfer de entrada trae uno o más cuadros
 * Layer II y sale PCM de 16 bits con los canales de la pista.
 */
@UnstableApi
final class Mp2Decoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
    private final int channels;
    private final int sampleRate;
    private final Mp2FrameDecoder frames = new Mp2FrameDecoder();
    private byte[] input = new byte[Mp2FrameDecoder.MAX_FRAME_BYTES * 2];
    private final short[] framePcm = new short[Mp2FrameDecoder.SAMPLES_PER_FRAME * 2];
    private final short[] outputPcm = new short[Mp2FrameDecoder.SAMPLES_PER_FRAME * 2];

    Mp2Decoder(int numBuffers, int initialInputBufferSize, int channels, int sampleRate) {
        super(new DecoderInputBuffer[numBuffers], new SimpleDecoderOutputBuffer[numBuffers]);
        this.channels = channels;
        this.sampleRate = sampleRate;
        setInitialInputBufferSize(initialInputBufferSize);
    }

    int getChannels() {
        return channels;
    }

    int getSampleRate() {
        return sampleRate;
    }

    @Override
    public String getName() {
        return "vibe.mp2";
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected SimpleDecoderOutputBuffer createOutputBuffer() {
        return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
        return new DecoderException("Error inesperado al decodificar MP2", error);
    }

    @Override
    protected DecoderException decode(
            DecoderInputBuffer inputBuffer,
            SimpleDecoderOutputBuffer outputBuffer,
            boolean reset
    ) {
        if (reset) frames.reset();
        ByteBuffer data = inputBuffer.data;
        int size = data == null ? 0 : data.remaining();
        if (size > input.length) input = new byte[size];
        if (size > 0) data.get(input, 0, size);

        int frameCount = 0;
        for (int offset = 0; offset < size; ) {
            Mp2FrameDecoder.Header header = Mp2FrameDecoder.parseHeader(input, offset, size);
            if (header == null || offset + header.frameBytes > size) {
                offset++;
                continue;
            }
            frameCount++;
            offset += header.frameBytes;
        }
        if (frameCount == 0) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        }

        int samplesPerFrame = Mp2FrameDecoder.SAMPLES_PER_FRAME * channels;
        ByteBuffer output = outputBuffer.init(inputBuffer.timeUs, frameCount * samplesPerFrame * 2);
        ShortBuffer pcm = output.asShortBuffer();
        for (int offset = 0; offset < size; ) {
            Mp2FrameDecoder.Header header = Mp2FrameDecoder.parseHeader(input, offset, size);
            if (header == null || offset + header.frameBytes > size) {
                offset++;
                continue;
            }
            frames.decodeFrame(input, offset, header, framePcm, 0);
            pcm.put(matchChannels(header.channels), 0, samplesPerFrame);
            offset += header.frameBytes;
        }
        output.position(0);
        output.limit(frameCount * samplesPerFrame * 2);
        return null;
    }

    /** La pista se anunció con {@link #channels}: un cuadro con otra cantidad se adapta. */
    private short[] matchChannels(int frameChannels) {
        if (frameChannels == channels) return framePcm;
        int count = Mp2FrameDecoder.SAMPLES_PER_FRAME;
        if (channels == 2) {
            for (int i = count - 1; i >= 0; i--) {
                outputPcm[2 * i] = framePcm[i];
                outputPcm[2 * i + 1] = framePcm[i];
            }
        } else {
            for (int i = 0; i < count; i++) {
                outputPcm[i] = (short) ((framePcm[2 * i] + framePcm[2 * i + 1]) / 2);
            }
        }
        return outputPcm;
    }
}

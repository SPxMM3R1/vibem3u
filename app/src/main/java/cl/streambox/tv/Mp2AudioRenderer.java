package cl.streambox.tv;

import android.os.Handler;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;

/**
 * Audio MP2 en software (0.6.06). Va después del renderer de MediaCodec: Media3 elige el primero
 * que soporta la pista, así que solo se usa en equipos sin decodificador MP2 propio (Chromecast
 * con Google TV); un televisor que sí lo trae sigue con el suyo.
 */
@UnstableApi
final class Mp2AudioRenderer extends DecoderAudioRenderer<Mp2Decoder> {
    static final String TAG = "VibeM3U-Audio";
    private static final int NUM_BUFFERS = 16;

    Mp2AudioRenderer(Handler eventHandler, AudioRendererEventListener eventListener,
            AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "Mp2AudioRenderer";
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        if (!MimeTypes.AUDIO_MPEG_L2.equals(format.sampleMimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM;
        if (!sinkSupportsFormat(Util.getPcmFormat(
                C.ENCODING_PCM_16BIT, channelsOf(format), sampleRateOf(format)))) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        return C.FORMAT_HANDLED;
    }

    @Override
    protected Mp2Decoder createDecoder(Format format, CryptoConfig cryptoConfig) {
        int inputSize = format.maxInputSize != Format.NO_VALUE
                ? format.maxInputSize
                : Mp2FrameDecoder.MAX_FRAME_BYTES;
        Log.i(TAG, "decodificador MP2 en software · " + sampleRateOf(format) + " Hz · "
                + channelsOf(format) + " canales");
        return new Mp2Decoder(NUM_BUFFERS, inputSize, channelsOf(format), sampleRateOf(format));
    }

    @Override
    protected Format getOutputFormat(Mp2Decoder decoder) {
        return Util.getPcmFormat(
                C.ENCODING_PCM_16BIT, decoder.getChannels(), decoder.getSampleRate());
    }

    private static int channelsOf(Format format) {
        return format.channelCount == 1 ? 1 : 2;
    }

    private static int sampleRateOf(Format format) {
        return format.sampleRate == Format.NO_VALUE ? 48_000 : format.sampleRate;
    }
}

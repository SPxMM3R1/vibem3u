package cl.streambox.tv;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

@UnstableApi
final class VibeRenderersFactory extends DefaultRenderersFactory {
    private final boolean normalizeVolume;
    /** Sistema › Audio MP2 por software (0.6.29); activado por omisión. */
    private final boolean softwareMp2;
    private MediaCodecVideoRenderer videoRenderer;

    VibeRenderersFactory(Context context, boolean normalizeVolume, boolean softwareMp2) {
        super(context);
        this.normalizeVolume = normalizeVolume;
        this.softwareMp2 = softwareMp2;
    }

    @Override
    protected void buildVideoRenderers(
            Context context,
            int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            ArrayList<Renderer> out
    ) {
        int firstRendererIndex = out.size();
        super.buildVideoRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out
        );
        videoRenderer = null;
        for (int i = firstRendererIndex; i < out.size(); i++) {
            Renderer renderer = out.get(i);
            if (renderer instanceof MediaCodecVideoRenderer) {
                videoRenderer = (MediaCodecVideoRenderer) renderer;
                return;
            }
        }
    }

    @Override
    protected void buildAudioRenderers(
            Context context,
            int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            AudioSink audioSink,
            Handler eventHandler,
            AudioRendererEventListener eventListener,
            ArrayList<Renderer> out
    ) {
        super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out
        );
        // Al final: solo toma el MP2 cuando el equipo no tiene decodificador propio (0.6.06).
        if (softwareMp2) out.add(new Mp2AudioRenderer(eventHandler, eventListener, audioSink));
    }

    MediaCodecVideoRenderer getVideoRenderer() {
        return videoRenderer;
    }

    @Override
    protected AudioSink buildAudioSink(
            Context context,
            boolean enableFloatOutput,
            boolean enableAudioOutputPlaybackParams
    ) {
        // Nivelación con Dolby (0.6.38): el AC3/E-AC3 que va directo a la tele no pasa por la
        // nivelación, y el decodificador de la tele lo baja según su «dialnorm» (canales Alcatel:
        // −24 → unos 7 dB menos). Si el equipo puede decodificarlo dentro de la app, se decodifica
        // aquí y se nivela como el resto. Sin decodificador, sigue como antes (con sonido).
        boolean decodeDolby = normalizeVolume && DolbyDecoders.available();
        DefaultAudioSink.Builder builder = (decodeDolby
                ? new DefaultAudioSink.Builder()
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                : new DefaultAudioSink.Builder(context))
                // Media3 disables audio processing when float output is forced.
                .setEnableFloatOutput(normalizeVolume ? false : enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (normalizeVolume) {
            builder.setAudioProcessors(new AudioProcessor[]{
                    new VolumeNormalizationAudioProcessor()
            });
        }
        // Sincronía del audio por canal (0.6.29): la usan todos los renderers de audio.
        return new AudioSyncOffset.Sink(builder.build());
    }
}

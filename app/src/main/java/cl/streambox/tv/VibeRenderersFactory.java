package cl.streambox.tv;

import android.content.Context;

import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

@UnstableApi
final class VibeRenderersFactory extends DefaultRenderersFactory {
    private final boolean normalizeVolume;

    VibeRenderersFactory(Context context, boolean normalizeVolume) {
        super(context);
        this.normalizeVolume = normalizeVolume;
    }

    @Override
    protected AudioSink buildAudioSink(
            Context context,
            boolean enableFloatOutput,
            boolean enableAudioOutputPlaybackParams
    ) {
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context)
                // Media3 disables audio processing when float output is forced.
                .setEnableFloatOutput(normalizeVolume ? false : enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (normalizeVolume) {
            builder.setAudioProcessors(new AudioProcessor[]{
                    new VolumeNormalizationAudioProcessor()
            });
        }
        return builder.build();
    }
}

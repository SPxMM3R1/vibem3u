package cl.streambox.tv;

import android.os.SystemClock;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

/**
 * El control de carga habitual más un búfer mínimo de arranque por fuente (0.5.89). Las
 * fuentes lentas (Nauta) esperan unos segundos de video antes de mostrar imagen; el resto
 * arranca igual que antes. Si ese mínimo no se alcanza en {@link #START_WAIT_CAP_MS}, arranca
 * con lo que haya para no dejar el canal en negro.
 */
@UnstableApi
final class StartThresholdLoadControl implements LoadControl {
    static final long START_WAIT_CAP_MS = 15_000L;

    private final DefaultLoadControl delegate;
    private volatile long minimumStartBufferUs;
    private volatile long armedAtElapsedMs = -1L;

    StartThresholdLoadControl(DefaultLoadControl delegate) {
        this.delegate = delegate;
    }

    /** Búfer mínimo para el próximo arranque; 0 vuelve al comportamiento normal. */
    void setMinimumStartBufferMs(long milliseconds) {
        minimumStartBufferUs = Math.max(0L, milliseconds) * 1000L;
        armedAtElapsedMs = SystemClock.elapsedRealtime();
    }

    static boolean meetsStartThreshold(long bufferedUs, long minimumUs, boolean rebuffering,
            long waitedMs) {
        return minimumUs <= 0L || rebuffering || bufferedUs >= minimumUs
                || waitedMs >= START_WAIT_CAP_MS;
    }

    @Override public boolean shouldStartPlayback(Parameters parameters) {
        if (!delegate.shouldStartPlayback(parameters)) return false;
        long armed = armedAtElapsedMs;
        long waited = armed < 0L ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - armed;
        return meetsStartThreshold(parameters.bufferedDurationUs, minimumStartBufferUs,
                parameters.rebuffering, waited);
    }

    @Override public void onPrepared(PlayerId playerId) {
        delegate.onPrepared(playerId);
    }

    @Override public void onTracksSelected(Parameters parameters, TrackGroupArray trackGroups,
            ExoTrackSelection[] trackSelections) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override public void onStopped(PlayerId playerId) {
        delegate.onStopped(playerId);
    }

    @Override public void onReleased(PlayerId playerId) {
        delegate.onReleased(playerId);
    }

    @Override public Allocator getAllocator(PlayerId playerId) {
        return delegate.getAllocator(playerId);
    }

    @Override public long getBackBufferDurationUs(PlayerId playerId) {
        return delegate.getBackBufferDurationUs(playerId);
    }

    @Override public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return delegate.retainBackBufferFromKeyframe(playerId);
    }

    @Override public boolean shouldContinueLoading(Parameters parameters) {
        return delegate.shouldContinueLoading(parameters);
    }

    @Override public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline,
            MediaSource.MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        return delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }
}

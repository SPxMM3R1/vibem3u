package cl.streambox.tv;

import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

/** Attributes callbacks to the immutable attempt tag, including late callbacks after zapping. */
@UnstableApi
final class PlaybackStartupAnalytics implements AnalyticsListener {
    static final String LOG_TAG = "VibeM3U-Startup";
    private final PlaybackStartupMetrics metrics;
    private final Timeline.Window window = new Timeline.Window();
    PlaybackStartupAnalytics(PlaybackStartupMetrics metrics) { this.metrics = metrics; }

    private long attempt(EventTime time) {
        if (time == null || time.windowIndex < 0 || time.windowIndex >= time.timeline.getWindowCount()) return -1L;
        MediaItem item = time.timeline.getWindow(time.windowIndex, window).mediaItem;
        Object tag = item.localConfiguration == null ? null : item.localConfiguration.tag;
        return tag instanceof Long ? (Long) tag : -1L;
    }
    @Override public void onLoadCompleted(EventTime time, LoadEventInfo info, MediaLoadData data) {
        if (data.dataType == C.DATA_TYPE_MANIFEST) metrics.manifest(attempt(time));
        if (data.dataType == C.DATA_TYPE_MEDIA) metrics.segment(attempt(time));
    }
    @Override public void onRenderedFirstFrame(EventTime time, Object output, long renderTimeMs) {
        if (metrics.firstFrame(attempt(time))) logCurrent();
    }
    @Override public void onPlaybackStateChanged(EventTime time, int state) {
        metrics.buffering(attempt(time), state == Player.STATE_BUFFERING);
    }
    @Override public void onPlayerError(EventTime time, PlaybackException error) {
        long id = attempt(time);
        if (id != metrics.currentId()) return;
        metrics.failed(id);
        logCurrent();
    }
    private void logCurrent() {
        PlaybackStartupMetrics.Sample sample = metrics.snapshot();
        if (sample == null) return;
        Log.i(LOG_TAG, sample.safeSummary());
        Log.i(LOG_TAG, metrics.summary(sample.provider));
    }
}

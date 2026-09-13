package cl.streambox.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class PlaybackRecoveryEpisodeTest {
    @Test
    public void reportsStablePlaybackOnlyAfterTheFullWindow() {
        PlaybackRecoveryEpisode episode = new PlaybackRecoveryEpisode();
        long start = 1_000_000_000L;

        assertFalse(episode.onPlayingChanged(true, start));
        assertFalse(episode.onPlayingChanged(
                true,
                start + TimeUnit.SECONDS.toNanos(PlaybackRecoveryEpisode.STABLE_PLAYBACK_MS / 1000L - 1L)
        ));
        assertTrue(episode.onPlayingChanged(
                true,
                start + TimeUnit.MILLISECONDS.toNanos(PlaybackRecoveryEpisode.STABLE_PLAYBACK_MS)
        ));
        assertFalse(episode.onPlayingChanged(
                true,
                start + TimeUnit.MILLISECONDS.toNanos(PlaybackRecoveryEpisode.STABLE_PLAYBACK_MS + 1L)
        ));
    }

    @Test
    public void aPauseStartsASeparateStabilityWindow() {
        PlaybackRecoveryEpisode episode = new PlaybackRecoveryEpisode();
        long start = 2_000_000_000L;

        episode.onPlayingChanged(true, start);
        assertFalse(episode.onPlayingChanged(
                false,
                start + TimeUnit.SECONDS.toNanos(5)
        ));
        assertFalse(episode.onPlayingChanged(
                true,
                start + TimeUnit.SECONDS.toNanos(5)
        ));
        assertTrue(episode.onPlayingChanged(
                true,
                start + TimeUnit.MILLISECONDS.toNanos(5_000L + PlaybackRecoveryEpisode.STABLE_PLAYBACK_MS)
        ));
    }
}

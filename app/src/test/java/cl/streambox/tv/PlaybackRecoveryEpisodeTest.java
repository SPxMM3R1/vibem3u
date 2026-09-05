package cl.streambox.tv;

import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class PlaybackRecoveryEpisodeTest {
    @Test public void aSecondExpiryCanRenewAfterSustainedPlayback() {
        PlaybackRecoveryEpisode episode = new PlaybackRecoveryEpisode();
        assertTrue(episode.tryRefresh());
        assertFalse(episode.tryRefresh());
        episode.onPlayingChanged(true, 0L);
        assertTrue(episode.onPlayingChanged(false, TimeUnit.SECONDS.toNanos(16)));
        assertTrue(episode.tryRefresh());
        assertFalse(episode.tryRefresh());
    }

    @Test public void briefSuccessfulStartsCannotCreateAnInfiniteRefreshLoop() {
        PlaybackRecoveryEpisode episode = new PlaybackRecoveryEpisode();
        assertTrue(episode.tryRefresh());
        for (int i = 0; i < 30; i++) {
            episode.onPlayingChanged(true, TimeUnit.SECONDS.toNanos(i * 2L));
            episode.onPlayingChanged(false, TimeUnit.SECONDS.toNanos(i * 2L + 1L));
            assertFalse(episode.tryRefresh());
        }
        assertTrue(episode.tryFallback());
        assertFalse(episode.tryFallback());
    }

    @Test public void failedResolutionHasOnlyOneFallbackUntilNewChannel() {
        PlaybackRecoveryEpisode episode = new PlaybackRecoveryEpisode();
        episode.resolutionFailed();
        assertFalse(episode.tryRefresh());
        assertTrue(episode.tryFallback());
        assertFalse(episode.tryFallback());
        episode.reset();
        assertTrue(episode.tryRefresh());
    }
}

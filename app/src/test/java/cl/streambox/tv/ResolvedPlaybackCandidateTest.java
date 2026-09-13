package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ResolvedPlaybackCandidateTest {
    @Test
    public void matchesFreshUrlWhenLogicalTvVooOptionIsTheSame() {
        ResolvedPlaybackSource listed = ResolvedPlaybackSource.dynamic(
                "tvvoo",
                "SkySportsF1.uk@TvVoo",
                "tvvoo:vavoo_SKY%20SPORTS%20F1%20FHD%7Cgroup%3Auk",
                URI.create("https://cdn.example/old-session.m3u8"),
                Collections.emptyMap(),
                TvVooStreamResolver.PLAYBACK_USER_AGENT,
                System.currentTimeMillis() + 10_000L
        );
        ResolvedPlaybackSource active = ResolvedPlaybackSource.dynamic(
                "tvvoo",
                "SkySportsF1.uk@TvVoo",
                "tvvoo:vavoo_SKY%20SPORTS%20F1%20FHD%7Cgroup%3Auk",
                URI.create("https://cdn.example/new-session.m3u8"),
                Collections.emptyMap(),
                TvVooStreamResolver.PLAYBACK_USER_AGENT,
                System.currentTimeMillis() + 10_000L
        );

        assertTrue(new ResolvedPlaybackCandidate("Fuente", "HLS", listed).matches(active));
    }

    @Test
    public void doesNotMarkAnotherLogicalOption() {
        ResolvedPlaybackSource listed = ResolvedPlaybackSource.dynamic(
                "tvvoo",
                "SkySportsF1.uk@TvVoo",
                "tvvoo:vavoo_SKY%20SPORTS%20F1%20HD%7Cgroup%3Auk",
                URI.create("https://cdn.example/one.m3u8"),
                Collections.emptyMap(),
                TvVooStreamResolver.PLAYBACK_USER_AGENT,
                System.currentTimeMillis() + 10_000L
        );
        ResolvedPlaybackSource active = ResolvedPlaybackSource.dynamic(
                "tvvoo",
                "SkySportsF1.uk@TvVoo",
                "tvvoo:vavoo_SKY%20SPORTS%20F1%20FHD%7Cgroup%3Auk",
                URI.create("https://cdn.example/two.m3u8"),
                Collections.emptyMap(),
                TvVooStreamResolver.PLAYBACK_USER_AGENT,
                System.currentTimeMillis() + 10_000L
        );

        assertFalse(new ResolvedPlaybackCandidate("Fuente", "HLS", listed).matches(active));
    }
}

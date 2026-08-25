package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ResolvedSourceRefreshPolicyTest {
    @Test
    public void authorizationAlwaysRefreshesTheResolvedSource() {
        URI segment = URI.create("https://cdn.example/live/segment-42.ts");

        assertTrue(ResolvedSourceRefreshPolicy.shouldRefresh(401, segment));
        assertTrue(ResolvedSourceRefreshPolicy.shouldRefresh(403, segment));
    }

    @Test
    public void missingOrGoneManifestRefreshesTheResolvedSource() {
        assertTrue(ResolvedSourceRefreshPolicy.shouldRefresh(
                404,
                URI.create("https://cdn.example/live/master.m3u8?session=redacted")
        ));
        assertTrue(ResolvedSourceRefreshPolicy.shouldRefresh(
                410,
                URI.create("https://cdn.example/live/manifest.mpd")
        ));
    }

    @Test
    public void segmentFailuresNeverRefreshTheProviderResolver() {
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(
                404,
                URI.create("https://cdn.example/live/segment-42.ts")
        ));
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(
                410,
                URI.create("https://cdn.example/live/chunk-42.m4s")
        ));
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(
                404,
                URI.create("https://cdn.example/live/audio-42.aac")
        ));
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(
                404,
                URI.create("https://cdn.example/live/encryption.key")
        ));
    }

    @Test
    public void networkAndServerFailuresKeepTheCurrentSource() {
        URI manifest = URI.create("https://cdn.example/live/master.m3u8");

        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(0, manifest));
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(408, manifest));
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(429, manifest));
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(500, manifest));
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(503, manifest));
    }

    @Test
    public void unknown404IsConservativeAndDoesNotDiscardAToken() {
        assertFalse(ResolvedSourceRefreshPolicy.shouldRefresh(404, null));
    }
}

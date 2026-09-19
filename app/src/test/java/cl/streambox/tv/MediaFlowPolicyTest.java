package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MediaFlowPolicyTest {
    @Test
    public void acceptsExplicitLanOriginAndEquivalentDefaultPort() throws Exception {
        URI configured = MediaFlowOriginPolicy.parseOrigin("https://MEDIAFLOW.local:443/");
        assertTrue(MediaFlowOriginPolicy.sameOrigin(
                URI.create("https://mediaflow.local/proxy/hls/manifest.m3u8"), configured
        ));
        assertEquals("https://mediaflow.local:443", configured.toString());
    }

    @Test
    public void rejectsCrossOriginRedirectBeforePlayback() throws Exception {
        URI origin = URI.create("http://192.168.1.20:8888");
        assertFalse(MediaFlowOriginPolicy.sameOrigin(
                URI.create("http://192.168.1.21:8888/proxy/stream"), origin
        ));
        try {
            MediaFlowRequestPolicy.resolveRedirect(
                    URI.create("http://192.168.1.20:8888/proxy/stream"),
                    "http://192.168.1.21:8888/secret",
                    origin
            );
            org.junit.Assert.fail("cross-origin redirect should fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("origen"));
        }
    }

    @Test
    public void sourceCarriesMimeAndTrustedOrigin() throws Exception {
        ResolvedPlaybackSource source = ResolvedPlaybackSource.dynamic(
                "mediaflow-tvvoo",
                "uk|bbc",
                URI.create("http://192.168.1.20:8888/proxy/stream?d=x"),
                Collections.emptyMap(),
                "VAVOO/2.6",
                0L,
                "vavoo_BBC%20TWO.vmu%7Cgroup%3Auk",
                MediaFlowExtractor.TS_MIME,
                URI.create("http://192.168.1.20:8888")
        );
        assertTrue(source.isMediaFlow());
        assertEquals(MediaFlowExtractor.TS_MIME, source.getMimeType());
    }
}

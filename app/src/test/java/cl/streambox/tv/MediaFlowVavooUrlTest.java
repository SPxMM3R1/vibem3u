package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class MediaFlowVavooUrlTest {
    @Test
    public void extractsOnlyKnownVavooDestinationFromExtractorRoute() throws Exception {
        URI proxy = URI.create(
                "https://proxy.invalid/extractor/video?host=Vavoo"
                        + "&d=https%3A%2F%2Fvavoo.to%2Fvavoo-iptv%2FBBC%2520TWO"
        );
        URI original = MediaFlowVavooUrl.fromTvVooProxy(
                proxy,
                MediaFlowTvVooEndpoint.discoveryOrigin()
        );
        assertEquals("https://vavoo.to/vavoo-iptv/BBC%20TWO", original.toString());
    }

    @Test
    public void rejectsWrongExtractorPathAndDestinationHost() throws Exception {
        try {
            MediaFlowVavooUrl.fromTvVooProxy(
                    URI.create("https://proxy.invalid/proxy/stream?d=https%3A%2F%2Fvavoo.to%2Fvavoo-iptv%2Fx"),
                    MediaFlowTvVooEndpoint.discoveryOrigin()
            );
            org.junit.Assert.fail("wrong path should fail");
        } catch (java.io.IOException expected) {
            // expected
        }
        try {
            MediaFlowVavooUrl.fromTvVooProxy(
                    URI.create("https://proxy.invalid/extractor/video?d=https%3A%2F%2Fevil.invalid%2Fvavoo-iptv%2Fx"),
                    MediaFlowTvVooEndpoint.discoveryOrigin()
            );
            org.junit.Assert.fail("wrong host should fail");
        } catch (java.io.IOException expected) {
            // expected
        }
    }

    @Test
    public void mediaFlowDiscoveryParsesExtractorStreamsAndReturnsOriginalVavoo() throws Exception {
        String json = "{\"streams\":["
                + "{\"url\":\"https://proxy.invalid/extractor/video"
                + "?d=https%3A%2F%2Fvavoo.to%2Fvavoo-iptv%2FBBC%2520TWO\"},"
                + "{\"url\":\"https://evil.invalid/extractor/video"
                + "?d=https%3A%2F%2Fvavoo.to%2Fvavoo-iptv%2Fevil\"}]}";
        List<URI> values = MediaFlowTvVooStreamResolver
                .parseMediaFlowVavooCandidates(json);
        assertEquals(1, values.size());
        assertEquals("https://vavoo.to/vavoo-iptv/BBC%20TWO", values.get(0).toString());
    }
}

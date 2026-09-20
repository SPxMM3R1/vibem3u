package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class DynamicSourceReferenceTest {
    @Test
    public void validatesAndDecodesAppOnlyReference() {
        URI reference = URI.create(
                "vibem3u://resolver/highfly/now-sky-sports-f1-free"
        );

        assertTrue(DynamicSourceReference.isAppOnly(reference));
        assertEquals("highfly", DynamicSourceReference.provider(reference));
        assertEquals(
                "now-sky-sports-f1-free",
                DynamicSourceReference.stableId(reference)
        );
    }

    @Test
    public void rejectsUnknownProviderAndPathTraversal() {
        assertFalse(DynamicSourceReference.isAppOnly(
                URI.create("vibem3u://resolver/unknown/source")
        ));
        assertFalse(DynamicSourceReference.isAppOnly(
                URI.create("vibem3u://resolver/tvn/a%2Fb")
        ));
    }

    @Test
    public void derivesHighflySlugFromLegacyHlsPath() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "legacy-highfly");
        URI source = URI.create(
                "https://leaf.highfly.dev/m3u/now-sky-sports-tennis/live.m3u8"
        );

        URI normalized = DynamicSourceReference.normalize(source, attributes);
        DynamicSourceReference.enrichAttributes(source, attributes);

        assertEquals(
                "vibem3u://resolver/highfly/now-sky-sports-tennis",
                normalized.toString()
        );
        assertEquals("highfly", attributes.get("x-resolver"));
        assertEquals("now-sky-sports-tennis", attributes.get("x-resolver-id"));
    }
}

package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class StreamResolverRegistryTest {
    private final StreamResolverRegistry registry = new StreamResolverRegistry();

    @Test
    public void resolvesBothCurrentAndLegacyMeganoticiasIds() {
        assertEquals(
                TvnStreamResolver.ID,
                registry.find(channel("0104", "https://example.invalid/tvn.m3u8")).getId()
        );
        assertEquals(
                MeganoticiasStreamResolver.ID,
                registry.find(channel(
                        "Meganoticias.cl",
                        "https://example.invalid/meganoticias.m3u8"
                )).getId()
        );
        assertEquals(
                MeganoticiasStreamResolver.ID,
                registry.find(channel(
                        "MeganoticiasAhora.cl",
                        "https://example.invalid/meganoticias.m3u8"
                )).getId()
        );
        assertNull(registry.find(channel("0105", "https://example.invalid/mega.m3u8")));
        assertNull(registry.find(channel("other", "https://mdstrm.com/anything.m3u8")));
    }

    private static Channel channel(String tvgId, String stream) {
        return new Channel(
                "Test",
                URI.create(stream),
                null,
                "Test",
                Collections.singletonMap("tvg-id", tvgId)
        );
    }
}

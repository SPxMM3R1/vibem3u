package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class TvVooCatalogChannelTest {
    @Test
    public void rawAndEncodedAliasesProduceTheSameStableResolverReference() {
        String raw = TvVooCatalogChannel.canonicalAlias("vavoo_SKY%201|group:uk", "uk");
        String encoded = TvVooCatalogChannel.canonicalAlias(
                "vavoo_SKY%201%7Cgroup%3Auk", "uk");

        assertEquals("vavoo_SKY%201%7Cgroup%3Auk", raw);
        assertEquals(raw, encoded);
        assertEquals("vavoo_CAN%2B%20NEWS%7Cgroup%3Auk",
                TvVooCatalogChannel.canonicalAlias("vavoo_CAN+%20NEWS|group:uk", "uk"));
        assertEquals("vavoo_100%25%7Cgroup%3Auk",
                TvVooCatalogChannel.canonicalAlias("vavoo_100%25|group:uk", "uk"));
    }

    @Test
    public void requiresPublishedStableIdToMatchCanonicalAlias() {
        String stableId = "unitedkingdom|vavoo_SKY%201%7Cgroup%3Auk";
        TvVooCatalogChannel channel = new TvVooCatalogChannel(
                stableId,
                "vavoo_SKY%201|group:uk",
                "SKY 1",
                "uk",
                "Sports",
                "Sport",
                "",
                Collections.emptyList()
        );

        assertEquals(stableId, channel.getStableId());
        assertTrue(TvVooCatalogChannel.isStableId(channel.getStableId()));
        assertFalse(TvVooCatalogChannel.isStableId("stale-id-from-cache"));

        Channel playable = channel.toChannel();
        assertEquals(channel.getStableId() + "@TvVoo", playable.getTvgId());
        assertEquals("tvvoo", playable.getAttributes().get("x-resolver"));
        assertEquals(channel.getAlias(), playable.getAttributes().get("x-resolver-id"));
        assertFalse(playable.getStreamUri().toString().contains("token"));
        assertEquals("example.invalid", playable.getStreamUri().getHost());
        try {
            new TvVooCatalogChannel(
                    "stale-id-from-cache",
                    "vavoo_SKY%201|group:uk",
                    "SKY 1",
                    "uk",
                    "Sports",
                    "Sport",
                    "",
                    Collections.emptyList()
            );
            fail("La identidad TvVoo no publicada debe rechazarse.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("identidad"));
        }
    }

    @Test
    public void m3uPresentationWinsWhenMergingWebPublishedProviderRow() {
        TvVooCatalogChannel published = new TvVooCatalogChannel(
                "unitedkingdom|vavoo_SKY%201%7Cgroup%3Auk",
                "vavoo_SKY%201|group:uk",
                "SKY 1",
                "uk",
                "Sports",
                "Sport",
                "https://catalog.example/sky.png",
                Collections.singletonList("vavoo_SKY%201|group:uk")
        );
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("tvg-id", published.getStableId() + "@TvVoo");
        attributes.put("tvg-country", "UK");
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-ids", published.getAlias());
        Channel fromM3u = new Channel(
                "M3U Preferred SKY",
                URI.create("tvvoo://channel/published-row"),
                null,
                "Sports",
                attributes
        );
        List<Channel> merged = TvVooChannelMerge.merge(
                Collections.singletonList(fromM3u),
                Collections.singletonList(published.toChannel())
        );

        assertEquals(1, merged.size());
        assertEquals("M3U Preferred SKY", merged.get(0).getName());
        assertEquals("https://catalog.example/sky.png", merged.get(0).getLogoUri().toString());
        assertEquals(published.getStableId() + "@TvVoo", merged.get(0).getTvgId());
    }
}

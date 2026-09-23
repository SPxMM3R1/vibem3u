package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HighflyCatalogChannelTest {
    @Test
    public void keepsStableCatalogIdentitySeparateFromRotatingResolverReference() {
        HighflyCatalogChannel channel = new HighflyCatalogChannel(
                "leaf:tennis-847291",
                "SkySportsTennis.uk",
                "Sky Sports Tennis",
                "Highfly · Deportes",
                "Tennis",
                "https://cdn.example/tennis.webp",
                Collections.singletonList("Tennis")
        );

        assertEquals("SkySportsTennis.uk", channel.getStableId());
        assertEquals("leaf:tennis-847291", channel.getResourceId());
        assertEquals("tennis-847291", channel.getSlug());
        assertEquals("canonical", channel.getIdentityState());
        assertEquals("uk", channel.getCountryKey());

        Channel playable = channel.toChannel();
        assertEquals("SkySportsTennis.uk", playable.getAttributes().get("x-resolver-stable-id"));
        assertEquals("tennis-847291", playable.getAttributes().get("x-resolver-id"));
        assertEquals("leaf:tennis-847291", playable.getAttributes().get("x-resolver-resource-id"));
        assertTrue(playable.getStreamUri().toString().startsWith("vibem3u://resolver/highfly/"));
        assertFalse(playable.getStreamUri().toString().contains(".m3u8"));
    }

    @Test
    public void roundTripsPublishedSelectionCacheWithoutPersistingPlaybackUrl() throws Exception {
        HighflyCatalogChannel channel = new HighflyCatalogChannel(
                "leaf:f1-3949409",
                "SkySportsF1.uk",
                "Sky Sports F1",
                "Highfly · Deportes",
                "Motor Sports",
                "https://cdn.example/f1.webp",
                Collections.singletonList("Motor Sports")
        );

        JSONObject cached = channel.toJson();
        assertFalse(cached.toString().contains(".m3u8"));
        assertFalse(cached.toString().toLowerCase().contains("token"));
        HighflyCatalogChannel restored = HighflyCatalogChannel.fromJson(cached);
        assertEquals(channel.getStableId(), restored.getStableId());
        assertEquals(channel.getResourceId(), restored.getResourceId());
        assertEquals(channel.getGenres(), restored.getGenres());
    }

    @Test
    public void keepsNameDerivedIdentityProvisional() {
        HighflyCatalogChannel channel = new HighflyCatalogChannel(
                "leaf:unknown-123",
                "Unknown Highfly Channel",
                "Highfly",
                "Sports",
                "",
                Collections.emptyList()
        );

        assertFalse(channel.isCanonicalIdentity());
        assertEquals("provisional", channel.getIdentityState());
        assertEquals("", channel.getCountryKey());
        assertEquals("Highfly.someunknownchannel",
                HighflyCatalogChannel.stableIdentity("", "Some Unknown Channel"));
    }

    @Test
    public void m3uPresentationWinsWhenMergingThePublishedHighflySelection() {
        HighflyCatalogChannel selected = new HighflyCatalogChannel(
                "leaf:f1-3949409",
                "SkySportsF1.uk",
                "Catálogo F1",
                "Highfly · Deportes",
                "Motor Sports",
                "https://cdn.highfly.to/f1.webp",
                Collections.singletonList("Motor Sports")
        );
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "SkySportsF1.uk");
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-id", "f1-3949409");
        Channel fromM3u = new Channel(
                "Lista F1",
                URI.create("https://leaf.highfly.dev/m3u/f1-3949409/live.m3u8"),
                null,
                "Sports",
                attributes
        );

        List<Channel> merged = HighflyChannelMerge.merge(
                Collections.singletonList(fromM3u),
                Arrays.asList(selected.toChannel()),
                true
        );
        assertEquals(1, merged.size());
        assertEquals("Lista F1", merged.get(0).getName());
        assertEquals("https://cdn.highfly.to/f1.webp", merged.get(0).getLogoUri().toString());
    }
}

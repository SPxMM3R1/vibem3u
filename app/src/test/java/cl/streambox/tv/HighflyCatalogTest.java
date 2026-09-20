package cl.streambox.tv;

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

public final class HighflyCatalogTest {
    @Test
    public void parsesStableMetadataAndKeepsOnlyTokenlessReference() throws Exception {
        HighflyCatalog catalog = HighflyCatalog.parse(
                "{\"metas\":["
                        + "{\"id\":\"leaf:f1-3949409\",\"type\":\"sport\","
                        + "\"name\":\"(FHD) : SKY SPORTS F1\","
                        + "\"poster\":\"https://cdn.highfly.to/f1.webp\","
                        + "\"genres\":[\"Motor Sports\"]},"
                        + "{\"id\":\"leaf:4k-344334\",\"type\":\"sport\","
                        + "\"name\":\"4K : SKY SPORTS F1\","
                        + "\"genres\":[\"Motor Sports\"]}]}"
        );

        assertEquals(2, catalog.size());
        HighflyCatalogChannel channel = catalog.getChannels().get(0);
        assertEquals("f1-3949409", channel.getSlug());
        String entry = channel.toM3uEntry();
        assertTrue(entry.contains("x-resolver=\"highfly\""));
        assertTrue(entry.contains("vibem3u://resolver/highfly/f1-3949409"));
        assertFalse(entry.contains(".m3u8"));
        assertFalse(entry.toLowerCase().contains("token"));
        List<Channel> parsed = M3uParser.parse(entry, URI.create("https://example.invalid/list.m3u"));
        assertEquals(1, parsed.size());
        assertEquals("highfly", parsed.get(0).getAttributes().get("x-resolver"));
        assertEquals("f1-3949409", parsed.get(0).getAttributes().get("x-resolver-id"));
    }

    @Test
    public void parsesTheMetadataCacheFormatAndFiltersByGenre() throws Exception {
        HighflyCatalog original = HighflyCatalog.parse(
                "{\"metas\":["
                        + "{\"id\":\"leaf:one-123\",\"type\":\"sport\","
                        + "\"name\":\"Sports One\",\"logo\":\"https://cdn.highfly.to/one.webp\","
                        + "\"genres\":[\"Tennis\"]},"
                        + "{\"id\":\"leaf:two-123\",\"type\":\"sport\","
                        + "\"name\":\"Sports Two\",\"genres\":[\"Football\"]}]}"
        );
        HighflyCatalog cached = HighflyCatalog.parse(original.toJson());

        assertEquals(2, cached.size());
        assertEquals(1, cached.filter("one", "Tennis").size());
        assertEquals("https://cdn.highfly.to/one.webp",
                cached.getChannels().get(0).getLogoUrl());
    }

    @Test
    public void mergesM3uPresentationAndAppendsLocalHighflySelection() {
        HighflyCatalogChannel selected = new HighflyCatalogChannel(
                "leaf:f1-3949409",
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

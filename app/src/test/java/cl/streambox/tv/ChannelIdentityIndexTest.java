package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ChannelIdentityIndexTest {
    @Test
    public void resolvesPublishedIdsByStableIdentityAndProviderReference() {
        Map<String, String> f1Attributes = new LinkedHashMap<>();
        f1Attributes.put("tvg-id", "sky-f1-xmltv");
        f1Attributes.put("x-resolver", "highfly");
        f1Attributes.put("x-resolver-stable-id", "SkySportsF1.uk");
        f1Attributes.put("x-resolver-id", "f1-3949409");
        f1Attributes.put("x-resolver-resource-id", "leaf:f1-3949409");

        Map<String, String> tvvooAttributes = new LinkedHashMap<>();
        tvvooAttributes.put("tvg-id", "sky-main-event-xmltv");
        tvvooAttributes.put("x-resolver", "tvvoo");
        tvvooAttributes.put(
                "x-resolver-stable-id",
                "unitedkingdom|vavoo_SKY%20SPORTS%20MAIN%20EVENT%7Cgroup%3Auk"
        );
        tvvooAttributes.put(
                "x-resolver-id",
                "vavoo_SKY%20SPORTS%20MAIN%20EVENT%7Cgroup%3Auk"
        );

        Playlist playlist = new Playlist(Arrays.asList(
                new Channel(
                        "Sky F1",
                        URI.create("https://example.invalid/f1"),
                        null,
                        "Sports",
                        f1Attributes
                ),
                new Channel(
                        "Sky Main Event",
                        URI.create("https://example.invalid/main"),
                        null,
                        "Sports",
                        tvvooAttributes
                )
        ), null);
        ChannelIdentityIndex index = ChannelIdentityIndex.fromPlaylists(
                Collections.singletonList(playlist)
        );

        HighflyCatalogChannel highfly = new HighflyCatalogChannel(
                "leaf:f1-3949409",
                "SkySportsF1.uk",
                "Sky F1",
                "Highfly",
                "Sports",
                "",
                Collections.emptyList()
        );
        TvVooCatalogChannel tvvoo = new TvVooCatalogChannel(
                "unitedkingdom|vavoo_SKY%20SPORTS%20MAIN%20EVENT%7Cgroup%3Auk",
                "vavoo_SKY%20SPORTS%20MAIN%20EVENT%7Cgroup%3Auk",
                "Sky Main Event",
                "United Kingdom",
                "Sports",
                "Sports",
                "",
                Collections.emptyList()
        );

        assertEquals("sky-f1-xmltv", index.tvgIdFor(highfly));
        assertEquals("sky-main-event-xmltv", index.tvgIdFor(tvvoo));
        assertEquals(2, index.getEntriesRead());
        assertEquals(1, index.getSourcesRead());
    }

    @Test
    public void keepsAnUnpublishedChannelPending() {
        ChannelIdentityIndex index = ChannelIdentityIndex.fromPlaylists(
                Collections.singletonList(new Playlist(
                        Collections.singletonList(new Channel(
                                "Other",
                                URI.create("https://example.invalid/other"),
                                null,
                                "Other",
                                Collections.singletonMap("tvg-id", "other")
                        )),
                        null
                ))
        );
        HighflyCatalogChannel channel = new HighflyCatalogChannel(
                "leaf:unknown-123",
                "Highfly.Unknown",
                "Unknown",
                "Highfly",
                "Sports",
                "",
                Collections.emptyList()
        );
        assertTrue(index.tvgIdFor(channel).isEmpty());
    }
}

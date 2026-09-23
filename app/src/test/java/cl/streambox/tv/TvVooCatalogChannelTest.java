package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void derivesStableIdentityFromCountryAndCanonicalAliasNotCachedId() {
        TvVooCatalogChannel channel = new TvVooCatalogChannel(
                "stale-id-from-cache",
                "vavoo_SKY%201|group:uk",
                "SKY 1",
                "uk",
                "Sports",
                "Sport",
                "",
                Collections.emptyList()
        );

        assertEquals("unitedkingdom|vavoo_SKY%201%7Cgroup%3Auk", channel.getStableId());
        assertTrue(TvVooCatalogChannel.isStableId(channel.getStableId()));
        assertFalse(TvVooCatalogChannel.isStableId("stale-id-from-cache"));

        Channel playable = channel.toChannel();
        assertEquals(channel.getStableId() + "@TvVoo", playable.getTvgId());
        assertEquals("tvvoo", playable.getAttributes().get("x-resolver"));
        assertEquals(channel.getAlias(), playable.getAttributes().get("x-resolver-id"));
        assertFalse(playable.getStreamUri().toString().contains("token"));
        assertEquals("example.invalid", playable.getStreamUri().getHost());
    }

    @Test
    public void roundTripsThePublishedTvVooSelectionCache() throws Exception {
        TvVooCatalogChannel selected = new TvVooCatalogChannel(
                "ignored",
                "vavoo_SKY%201|group:uk",
                "SKY 1",
                "uk",
                "Sports",
                "Sport",
                "https://example.com/sky.png",
                Collections.singletonList("vavoo_SKY%201|group:uk")
        );

        JSONObject cached = selected.toJson();
        assertFalse(cached.toString().contains(".m3u8"));
        TvVooCatalogChannel restored = TvVooCatalogChannel.fromJson(cached);
        assertEquals(selected.getStableId(), restored.getStableId());
        assertEquals(selected.getAlias(), restored.getAlias());
        assertEquals(selected.getResolverAliases(), restored.getResolverAliases());
    }

    @Test
    public void m3uPresentationWinsOverPublishedSelectionDuringMerge() throws Exception {
        TvVooCatalogChannel selected = new TvVooCatalogChannel(
                "ignored",
                "vavoo_SKY%201|group:uk",
                "SKY 1",
                "uk",
                "Sports",
                "Sport",
                "https://catalog.example/sky.png",
                Collections.singletonList("vavoo_SKY%201|group:uk")
        );
        String entry = selected.toM3uEntry();
        assertTrue(entry.contains("x-resolver=\"tvvoo\""));
        assertFalse(entry.contains("token="));
        assertFalse(entry.contains("stream.m3u8"));

        Channel selectedChannel = M3uParser.parse(entry,
                URI.create("https://catalog.example/tv.m3u")).get(0);
        String playlist = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"opening\",Opening\n"
                + "https://example.org/opening.m3u8\n"
                + entry.replace("tvg-logo=\"https://catalog.example/sky.png\"",
                        "tvg-logo=\"https://m3u.example/sky-local.png\"")
                        .replace(",SKY 1\n", ",M3U Preferred SKY\n");
        List<Channel> merged = TvVooChannelMerge.merge(
                M3uParser.parse(playlist, URI.create("https://m3u.example/list.m3u")),
                Collections.singletonList(selectedChannel),
                true
        );

        assertEquals(2, merged.size());
        assertEquals("Opening", merged.get(0).getName());
        assertEquals("M3U Preferred SKY", merged.get(1).getName());
        assertEquals("https://m3u.example/sky-local.png", merged.get(1).getLogoUri().toString());
        assertEquals(selected.getStableId() + "@TvVoo", merged.get(1).getTvgId());
    }
}

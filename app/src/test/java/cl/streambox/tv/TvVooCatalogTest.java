package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TvVooCatalogTest {
    @Test
    public void manifestKeepsOnlyTvVooTvCatalogs() throws Exception {
        TvVooCatalogManifest manifest = TvVooCatalogManifest.parse(
                "{\"version\":\"5\",\"catalogs\":["
                        + "{\"id\":\"vavoo_tv_uk\",\"type\":\"tv\","
                        + "\"name\":\"Vavoo TV • United Kingdom\","
                        + "\"extra\":[{\"name\":\"genre\",\"options\":[\"Tutti\",\"News\"]}]},"
                        + "{\"id\":\"vavoo_movie_uk\",\"type\":\"movie\"},"
                        + "{\"id\":\"vavoo_search_uk\",\"type\":\"tv\"}]}"
        );

        assertEquals("5", manifest.getVersion());
        assertEquals(1, manifest.getCatalogs().size());
        assertEquals("unitedkingdom", manifest.getCatalogs().get(0).getCountryKey());
        assertEquals("United Kingdom", manifest.getCatalogs().get(0).getDisplayName());
        assertEquals(1, manifest.getCatalogs().get(0).getGenres().size());
    }

    @Test
    public void rawAndEncodedMetaIdsProduceTheSameCanonicalResolverAlias() {
        String raw = TvVooCatalogChannel.canonicalAlias(
                "vavoo_SKY%201|group:uk", "uk"
        );
        String encoded = TvVooCatalogChannel.canonicalAlias(
                "vavoo_SKY%201%7Cgroup%3Auk", "uk"
        );

        assertEquals("vavoo_SKY%201%7Cgroup%3Auk", raw);
        assertEquals(raw, encoded);
        assertEquals(
                "vavoo_CAN%2B%20NEWS%7Cgroup%3Auk",
                TvVooCatalogChannel.canonicalAlias("vavoo_CAN+%20NEWS|group:uk", "uk")
        );
        assertEquals(
                "vavoo_CAN%2B%20NEWS%7Cgroup%3Auk",
                TvVooCatalogChannel.canonicalAlias("vavoo_CAN%2B%20NEWS%7Cgroup%3Auk", "uk")
        );
        assertEquals(
                "vavoo_100%25%7Cgroup%3Auk",
                TvVooCatalogChannel.canonicalAlias("vavoo_100%25|group:uk", "uk")
        );
    }

    @Test
    public void catalogParsesGenresSearchesAndPagesWithoutLosingOrder() throws Exception {
        TvVooCatalog catalog = TvVooCatalog.parse(
                "{\"metas\":["
                        + "{\"id\":\"vavoo_SKY%201|group:uk\",\"type\":\"tv\","
                        + "\"name\":\"SKY 1\",\"genres\":[\"News\",\"Sport\"],"
                        + "\"logo\":\"https://example.com/sky.png\"},"
                        + "{\"id\":\"vavoo_BBC%20NEWS|group:uk\",\"type\":\"tv\","
                        + "\"name\":\"BBC NEWS\",\"genres\":[\"News\"]},"
                        + "{\"id\":\"vavoo_KIDS|group:uk\",\"type\":\"tv\","
                        + "\"name\":\"Kids\",\"genres\":null}]}" ,
                "vavoo_tv_uk",
                "uk"
        );

        assertEquals(3, catalog.size());
        assertEquals("vavoo_SKY%201%7Cgroup%3Auk", catalog.getChannels().get(0).getAlias());
        assertEquals(1, catalog.filter("news", "News").size());
        List<TvVooCatalogChannel> page = catalog.page("", "", 1, 2);
        assertEquals(1, page.size());
        assertEquals("Kids", page.get(0).getName());
        assertTrue(catalog.getCategories().contains("News"));
        assertFalse(catalog.filter("missing", "").iterator().hasNext());
    }

    @Test
    public void m3uExportContainsOnlyStableIdentityAndCanonicalAlias() throws Exception {
        TvVooCatalog catalog = TvVooCatalog.parse(
                "{\"metas\":[{\"id\":\"vavoo_SKY%201|group:uk\","
                        + "\"type\":\"tv\",\"name\":\"SKY 1\","
                        + "\"logo\":\"https://example.com/sky.png\"}]}",
                "vavoo_tv_uk",
                "uk"
        );
        String entry = catalog.getChannels().get(0).toM3uEntry();

        assertTrue(entry.contains("tvg-id=\"unitedkingdom|vavoo_SKY%201%7Cgroup%3Auk@TvVoo\""));
        assertTrue(entry.contains("x-resolver=\"tvvoo\""));
        assertTrue(entry.contains("x-resolver-id=\"vavoo_SKY%201%7Cgroup%3Auk\""));
        assertTrue(entry.contains("tvvoo://channel/unitedkingdom%7Cvavoo_SKY%25201%257Cgroup%253Auk"));
        assertFalse(entry.contains("token="));
        assertFalse(entry.contains("stream.m3u8"));
    }

    @Test
    public void exportedEntrySurvivesCacheRoundTripAndM3uIdentityWinsMerge() throws Exception {
        TvVooCatalog catalog = TvVooCatalog.parse(
                "{\"metas\":[{\"id\":\"vavoo_SKY%201|group:uk\","
                        + "\"type\":\"tv\",\"name\":\"SKY 1\","
                        + "\"logo\":\"https://catalog.example/sky.png\"}]}",
                "vavoo_tv_uk",
                "uk"
        );
        TvVooCatalogChannel catalogChannel = catalog.getChannels().get(0);
        String exported = catalogChannel.toM3uEntry();

        Channel exportedChannel = M3uParser.parse(exported, URI.create("https://catalog.example/tv.m3u"))
                .get(0);
        assertEquals(catalogChannel.getStableId() + "@TvVoo", exportedChannel.getTvgId());
        assertEquals("tvvoo", exportedChannel.getStreamUri().getScheme());
        assertFalse(exportedChannel.getStreamUri().toString().contains("token"));

        String cached = M3uCacheSanitizer.forDisk(exported);
        Channel cachedChannel = M3uParser.parse(cached, URI.create("https://cache.example/tv.m3u"))
                .get(0);
        assertEquals(exportedChannel.getTvgId(), cachedChannel.getTvgId());
        assertEquals(exportedChannel.getStreamUri(), cachedChannel.getStreamUri());

        String m3uPreferred = cached
                .replace("tvg-logo=\"https://catalog.example/sky.png\"",
                        "tvg-logo=\"https://m3u.example/sky-local.png\"")
                .replace(",SKY 1\n", ",M3U Preferred SKY\n");
        String playlist = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"opening\",Opening\n"
                + "https://example.org/opening.m3u8\n"
                + m3uPreferred;
        List<Channel> merged = TvVooChannelMerge.merge(
                M3uParser.parse(playlist, URI.create("https://m3u.example/list.m3u")),
                java.util.Collections.singletonList(cachedChannel),
                true
        );

        assertEquals(2, merged.size());
        assertEquals("Opening", merged.get(0).getName());
        assertEquals("M3U Preferred SKY", merged.get(1).getName());
        assertEquals("https://m3u.example/sky-local.png", merged.get(1).getLogoUri().toString());
        assertEquals(cachedChannel.getTvgId(), merged.get(1).getTvgId());
    }
}

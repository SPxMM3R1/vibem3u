package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HighflyPremiumPayloadParserTest {
    @Test
    public void tokenRulesAcceptOneSafePathSegmentOnly() throws Exception {
        assertTrue(HighflyPremiumTokenRules.isValid("premium-token_2026"));
        assertEquals(
                "premium-token_2026",
                HighflyPremiumTokenRules.normalize("  premium-token_2026  ")
        );
        assertFalse(HighflyPremiumTokenRules.isValid("premium/token"));
        assertFalse(HighflyPremiumTokenRules.isValid("premium%2Ftoken"));
        assertFalse(HighflyPremiumTokenRules.isValid("premium token"));
    }

    @Test
    public void parsesManifestCatalogsAndRejectsRecapCatalogs() throws Exception {
        HighflyPremiumPayloadParser.ManifestInfo manifest =
                HighflyPremiumPayloadParser.parseManifest(
                        "{\"resources\":[\"catalog\",\"meta\",\"stream\"],"
                                + "\"catalogs\":["
                                + "{\"id\":\"sports_live\",\"type\":\"sport\"},"
                                + "{\"id\":\"sports_today\",\"type\":\"sport\"},"
                                + "{\"id\":\"sports_recaps\",\"type\":\"sport\"}]}"
                );

        assertEquals(2, manifest.getCatalogIds().size());
        assertTrue(manifest.getCatalogIds().contains("sports_live"));
        assertTrue(manifest.hasMetadata());
    }

    @Test
    public void classifiesStableChannelsAndTemporaryEventsWithoutKeepingSourceUrls()
            throws Exception {
        List<HighflyPremiumCatalog.Entry> entries =
                HighflyPremiumPayloadParser.parseCatalog(
                        "{\"metas\":["
                                + "{\"id\":\"leaf:now-sky-sports-f1-free\","
                                + "\"name\":\"Sky Sports F1\",\"genres\":[\"Motorsport\"],"
                                + "\"poster\":\"https://cdn.highfly.dev/leaf_posters/f1.webp\"},"
                                + "{\"id\":\"streamed:club-vs-city-123\","
                                + "\"name\":\"Club vs City\",\"releaseInfo\":\"LIVE\"},"
                                + "{\"id\":\"javascript:unsafe\",\"name\":\"Unsafe\"}]}"
                );

        assertEquals(3, entries.size());
        assertEquals(
                HighflyPremiumCatalog.EntryType.STABLE_CHANNEL,
                entries.get(0).getType()
        );
        assertEquals(
                HighflyPremiumCatalog.EntryType.TEMPORARY_EVENT,
                entries.get(1).getType()
        );
        assertEquals(
                HighflyPremiumCatalog.EntryType.UNSUPPORTED,
                entries.get(2).getType()
        );
        assertEquals("now-sky-sports-f1-free", entries.get(0).getSlug());
        assertTrue(entries.get(0).getLogoUri().toString().startsWith(
                "https://cdn.highfly.dev/"
        ));
        assertFalse(entries.get(0).getId().contains("https://"));
    }

    @Test
    public void keepsOnlyHttpsStreamCandidatesAndScoresQualityMetadata() throws Exception {
        List<HighflyPremiumPayloadParser.StreamCandidate> candidates =
                HighflyPremiumPayloadParser.parseStreams(
                        "{\"streams\":["
                                + "{\"title\":\"1920x1080 · ~8.4 Mbps\","
                                + "\"url\":\"https://leaf.highfly.dev/live/one.m3u8\"},"
                                + "{\"title\":\"http fallback\","
                                + "\"url\":\"http://leaf.highfly.dev/live/two.m3u8\"},"
                                + "{\"title\":\"duplicate\","
                                + "\"url\":\"https://leaf.highfly.dev/live/one.m3u8\"}]}"
                );

        assertEquals(1, candidates.size());
        assertEquals(
                URI.create("https://leaf.highfly.dev/live/one.m3u8"),
                candidates.get(0).getUri()
        );
        assertTrue(candidates.get(0).getQualityScore() > 0);
    }

    @Test
    public void generatedPlaylistsSeparateStableChannelsAndSelectedEvents() {
        HighflyPremiumCatalog.Entry stable = new HighflyPremiumCatalog.Entry(
                "leaf:stable-channel",
                "stable-channel",
                "Stable Channel",
                "Sports",
                URI.create("https://cdn.highfly.dev/leaf_posters/stable.webp"),
                "",
                "LIVE",
                HighflyPremiumCatalog.EntryType.STABLE_CHANNEL
        );
        HighflyPremiumCatalog.Entry event = new HighflyPremiumCatalog.Entry(
                "streamed:event-123",
                "",
                "Event 123",
                "Football",
                null,
                "",
                "Today",
                HighflyPremiumCatalog.EntryType.TEMPORARY_EVENT
        );
        HighflyPremiumCatalog catalog = new HighflyPremiumCatalog(
                java.util.Arrays.asList(stable, event),
                HighflyPremiumPreferences.Region.MAIN,
                1L
        );

        Playlist stablePlaylist = catalog.toStablePlaylist();
        assertEquals(1, stablePlaylist.getChannels().size());
        assertEquals(
                "Lista 3 · Highfly · Sports",
                stablePlaylist.getChannels().get(0).getGroup()
        );
        assertEquals(
                "true",
                stablePlaylist.getChannels().get(0)
                        .getAttributes().get("x-highfly-premium-stable")
        );
        assertFalse(stablePlaylist.getChannels().get(0)
                .getAttributes().containsKey("x-highfly-premium"));

        Set<String> selected = new LinkedHashSet<>();
        selected.add(event.getId());
        Playlist eventPlaylist = catalog.toEventsPlaylist(selected);
        assertEquals(1, eventPlaylist.getChannels().size());
        assertEquals(
                "Lista 4 · Eventos temporales · Football",
                eventPlaylist.getChannels().get(0).getGroup()
        );
        assertEquals("evento", eventPlaylist.getChannels().get(0)
                .getAttributes().get("x-highfly-premium-kind"));
        assertEquals("true", eventPlaylist.getChannels().get(0)
                .getAttributes().get("x-highfly-premium-virtual"));
    }
}

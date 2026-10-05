package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class PublishedPlaybackCatalogTest {
    @Test
    public void parsesOneStableMixedOrderAndProviderIdentityContract() throws Exception {
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(document());

        assertEquals(Arrays.asList(
                "m3u:tvg:0104",
                "highfly:SkySportsF1.uk",
                "tvvoo:spain|vavoo_ESPN%201%7Cgroup%3Aes"
        ), catalog.playbackOrder());
        assertEquals(Integer.valueOf(10), catalog.activeNumbers().get("m3u:tvg:0104"));
        assertEquals("f1-hd", catalog.getRows().get(1).toHighfly().getSlug());
        assertEquals("SkySportsF1.uk", catalog.getRows().get(1).toHighfly().getStableId());
        assertEquals("leaf:f1-hd", catalog.getRows().get(1).resourceId);
        assertEquals(
                "spain|vavoo_ESPN%201%7Cgroup%3Aes",
                catalog.getRows().get(2).toTvVoo().getStableId()
        );
        assertTrue(catalog.hasActiveProviderChannels());
        List<Channel> publishedProviders = catalog.activeProviderChannels();
        assertEquals(2, publishedProviders.size());
        assertTrue(HighflyChannelMerge.isHighfly(publishedProviders.get(0)));
        assertTrue(TvVooChannelMerge.isTvVoo(publishedProviders.get(1)));
        assertEquals("deleted", catalog.getRows().get(4).state);
    }

    @Test
    public void emptyPublishedDocumentCannotAddProviderChannels() {
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.empty();

        assertFalse(catalog.hasActiveProviderChannels());
        assertTrue(catalog.activeProviderChannels().isEmpty());
    }

    @Test
    public void rejectsResolverLocatorAsHighflyIdentity() throws Exception {
        assertInvalid(document().replace("SkySportsF1.uk", "leaf:f1-hd"));
    }

    @Test
    public void rejectsHlsAndCredentialTextBeforeApplyingAnyRows() throws Exception {
        assertInvalid(document().replace(
                "TVN",
                "TVN https://private.example/live.m3u8?token=secret"
        ));
    }

    @Test
    public void rejectsDuplicatePublicIdentityAcrossSources() throws Exception {
        assertInvalid(document().replace(
                "\"tvgId\":\"0104\"",
                "\"tvgId\":\"SkySportsF1.uk\""
        ));
    }

    @Test
    public void preservesPermanentM3uExclusionsAndRejectsConflictingActiveRows() throws Exception {
        String withTombstone = document().replace(
                "\"schemaVersion\":1,\"channels\":[",
                "\"schemaVersion\":1,\"excludedM3u\":[\"1300\"],\"channels\":["
        );
        assertEquals(Arrays.asList("1300"),
                PublishedPlaybackCatalog.parse(withTombstone).excludedM3uIds());
        assertInvalid(document().replace(
                "\"schemaVersion\":1,\"channels\":[",
                "\"schemaVersion\":1,\"excludedM3u\":[\"0104\"],\"channels\":["
        ));
        assertInvalid(document().replace(
                "\"schemaVersion\":1,\"channels\":[",
                "\"schemaVersion\":1,\"excludedM3u\":[42],\"channels\":["
        ));
    }

    @Test
    public void hiddenOrDeletedProvidersDoNotSatisfyThePlaybackSourceRequirement() throws Exception {
        String withoutActiveProviders = document().replace(
                "\"state\":\"active\"",
                "\"state\":\"hidden\""
        );
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(withoutActiveProviders);
        assertFalse(catalog.hasActiveProviderChannels());
        assertTrue(catalog.activeProviderChannels().isEmpty());
    }

    @Test
    public void appliesPublishedOrderAndFiltersHiddenAndDeletedRows() throws Exception {
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(document());
        Channel tvn = new Channel("TVN", java.net.URI.create("https://example.org/tvn.m3u8"),
                null, "Nacionales", Collections.singletonMap("tvg-id", "0104"));
        Channel hidden = new Channel("24 Horas", java.net.URI.create("https://example.org/24.m3u8"),
                null, "Noticias", Collections.singletonMap("tvg-id", "0201"));
        Channel deleted = new Channel("CHV Noticias",
                java.net.URI.create("https://example.org/chv.m3u8"), null, "Noticias",
                Collections.singletonMap("tvg-id", "1153"));
        Channel highfly = new HighflyCatalogChannel(
                "leaf:f1-hd", "SkySportsF1.uk", "Sky Sports F1", "Highfly", "Sport", "",
                Collections.emptyList()
        ).toChannel();
        Channel tvvoo = new TvVooCatalogChannel(
                "spain|vavoo_ESPN%201%7Cgroup%3Aes",
                "vavoo_ESPN%201|group:es", "ESPN 1", "spain", "TvVoo", "Sport", "",
                Collections.emptyList()
        ).toChannel();
        Channel unlisted = new Channel("Not yet published",
                java.net.URI.create("https://example.org/new.m3u8"), null, "Other",
                Collections.singletonMap("tvg-id", "9999"));

        List<Channel> playback = catalog.applyToPlayback(
                Arrays.asList(hidden, tvvoo, deleted, highfly, unlisted, tvn));

        assertEquals(Arrays.asList(tvn, highfly, tvvoo, unlisted), playback);
        assertEquals(4, catalog.numberFor(highfly, 99));
        assertEquals(99, catalog.numberFor(unlisted, 99));
    }

    @Test
    public void appliesCustomDisplayNamesWithoutChangingStableProviderIdentity() throws Exception {
        String edited = document()
                .replace("\"name\":\"TVN\"", "\"name\":\"TVN\",\"displayName\":\"TV Nacional\"")
                .replace("\"name\":\"Sky Sports F1\"", "\"name\":\"Sky Sports F1\",\"displayName\":\"F1 en vivo\"")
                .replace("\"name\":\"ESPN 1\"", "\"name\":\"ESPN 1\",\"displayName\":\"ESPN Deportes\"");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);
        Channel tvn = new Channel("TVN", java.net.URI.create("https://example.org/tvn.m3u8"),
                null, "Nacionales", Collections.singletonMap("tvg-id", "0104"));
        Channel highfly = new HighflyCatalogChannel(
                "leaf:f1-hd", "SkySportsF1.uk", "Sky Sports F1", "Highfly", "Sport", "",
                Collections.emptyList()
        ).toChannel();
        Channel tvvoo = new TvVooCatalogChannel(
                "spain|vavoo_ESPN%201%7Cgroup%3Aes",
                "vavoo_ESPN%201|group:es", "ESPN 1", "spain", "TvVoo", "Sport", "",
                Collections.emptyList()
        ).toChannel();

        List<Channel> result = catalog.applyToPlayback(Arrays.asList(tvn, highfly, tvvoo));

        assertEquals(Arrays.asList("TV Nacional", "F1 en vivo", "ESPN Deportes"), Arrays.asList(
                result.get(0).getName(), result.get(1).getName(), result.get(2).getName()
        ));
        assertEquals("SkySportsF1.uk", result.get(1).getAttributes().get("x-resolver-stable-id"));
        assertEquals("spain|vavoo_ESPN%201%7Cgroup%3Aes", result.get(2).getAttributes().get("x-resolver-stable-id"));
    }

    @Test
    public void rejectsUnsafeCustomDisplayName() throws Exception {
        assertInvalid(document().replace(
                "\"name\":\"TVN\"",
                "\"name\":\"TVN\",\"displayName\":\"Canal\\ninyectado\""
        ));
        assertInvalid(document().replace(
                "\"name\":\"TVN\"",
                "\"name\":\"TVN\",\"displayName\":\"https://example.org/live.m3u8\""
        ));
    }

    @Test
    public void appliesRepositoryLogoToHighflyProviderRows() throws Exception {
        String edited = document().replace(
                "\"name\":\"Sky Sports F1\"",
                "\"name\":\"Sky Sports F1\",\"logoOverride\":\"logos/sky-sports-f1.png\""
        );
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);

        List<Channel> providers = catalog.activeProviderChannels();

        assertEquals(2, providers.size());
        assertEquals(
                "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/logos/sky-sports-f1.png",
                providers.get(0).getLogoUri().toString()
        );
    }

    @Test
    public void acceptsTvVooRowsThatCarryDisplayCountryAndCountryKey() throws Exception {
        String edited = document()
                .replace("\"country\":\"spain\"", "\"country\":\"Reino Unido\",\"countryKey\":\"unitedkingdom\"")
                .replace("spain|vavoo_ESPN%201%7Cgroup%3Aes", "unitedkingdom|vavoo_ESPN%201%7Cgroup%3Aes");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);

        assertEquals(
                "unitedkingdom|vavoo_ESPN%201%7Cgroup%3Aes",
                catalog.getRows().get(2).toTvVoo().getStableId()
        );
        assertEquals(2, catalog.activeProviderChannels().size());
        assertTrue(catalog.skippedProviderRows().isEmpty());
    }

    @Test
    public void skipsUnconvertibleProviderRowsWithoutFailingStartup() throws Exception {
        // Un countryKey que no coincide con catalogKey hace la fila inconvertible; "country"
        // solo es texto visible y ya no cuenta como clave (contrato layout-provider-rows).
        String edited = document().replace(
                "\"country\":\"spain\"",
                "\"country\":\"Reino Unido\",\"countryKey\":\"france\"");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);

        List<Channel> providers = catalog.activeProviderChannels();

        assertEquals(1, providers.size());
        assertTrue(HighflyChannelMerge.isHighfly(providers.get(0)));
        assertEquals(
                Collections.singletonList("spain|vavoo_ESPN%201%7Cgroup%3Aes"),
                catalog.skippedProviderRows()
        );
    }

    @Test
    public void keepsHighflyRowsThatTheEditorMarkedProvisional() throws Exception {
        String edited = document().replace(
                "\"identityState\":\"canonical\",\"name\":\"Sky Sports F1\"",
                "\"identityState\":\"provisional\",\"name\":\"Sky Sports F1\"");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);

        List<Channel> providers = catalog.activeProviderChannels();

        assertEquals(2, providers.size());
        assertTrue(HighflyChannelMerge.isHighfly(providers.get(0)));
        assertTrue(catalog.skippedProviderRows().isEmpty());
    }

    private static void assertInvalid(String value) throws Exception {
        try {
            PublishedPlaybackCatalog.parse(value);
            fail("El documento inválido se aceptó.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    @Test
    public void directChannelCarriesItsTvVooBackupForPlayback() throws Exception {
        String edited = document().replace(
                "\"name\":\"TVN\",",
                "\"name\":\"TVN\",\"backupTvVoo\":\"arabia|vavoo_ESPN%203%7Cgroup%3Aar\",");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);
        Channel tvn = new Channel("TVN", java.net.URI.create("https://example.org/tvn.m3u8"),
                null, "Nacionales", Collections.singletonMap("tvg-id", "0104"));

        Channel played = catalog.applyToPlayback(Collections.singletonList(tvn)).get(0);

        assertTrue(TvVooBackup.has(played));
        assertEquals("arabia|vavoo_ESPN%203%7Cgroup%3Aar", TvVooBackup.stableIdOf(played));
        assertEquals("0104", played.getTvgId());
        assertEquals(tvn.getStreamUri(), played.getStreamUri());
    }

    @Test
    public void malformedBackupIsIgnoredAndTheDirectChannelStillPlays() throws Exception {
        String edited = document().replace(
                "\"name\":\"TVN\",", "\"name\":\"TVN\",\"backupTvVoo\":\"https://x\",");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);
        Channel tvn = new Channel("TVN", java.net.URI.create("https://example.org/tvn.m3u8"),
                null, "Nacionales", Collections.singletonMap("tvg-id", "0104"));

        Channel played = catalog.applyToPlayback(Collections.singletonList(tvn)).get(0);

        assertFalse(TvVooBackup.has(played));
    }

    @Test
    public void preferredM3uPlaysFirstAndKeepsOwnStreamAsBackup() throws Exception {
        String edited = document()
                .replace("\"name\":\"TVN\",", "\"name\":\"TVN\",\"preferredM3u\":\"TVN.cl@Direct138\",")
                .replace("{\"kind\":\"provider\",\"provider\":\"highfly\"",
                        "{\"kind\":\"m3u\",\"tvgId\":\"TVN.cl@Direct138\",\"name\":\"TVN [IP 138]\","
                                + "\"group\":\"Nacionales\",\"sourceList\":\"1.m3u\","
                                + "\"order\":9,\"number\":79,\"state\":\"active\"},"
                                + "{\"kind\":\"provider\",\"provider\":\"highfly\"");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);
        Channel tvn = new Channel("TVN", java.net.URI.create("https://example.org/tvn.m3u8"),
                null, "Nacionales", Collections.singletonMap("tvg-id", "0104"));
        Channel better = new Channel("TVN [IP 138]", java.net.URI.create("http://example.org/hd.m3u8"),
                null, "Nacionales", Collections.singletonMap("tvg-id", "TVN.cl@Direct138"));

        List<Channel> playback = catalog.applyToPlayback(Arrays.asList(tvn, better));

        assertEquals(1, playback.size());
        Channel played = playback.get(0);
        assertEquals("0104", played.getTvgId());
        assertEquals(better.getStreamUri(), played.getStreamUri());
        assertEquals(tvn.getStreamUri(), TvVooBackup.directBackupOf(played));
        assertEquals(tvn.getStreamUri(), TvVooBackup.resolutionChannel(played).getStreamUri());
    }

    @Test
    public void backupm3uRowsTravelInsideTheirChannelInOrderAfterThePreferredOne() throws Exception {
        String edited = document()
                .replace("\"name\":\"TVN\",", "\"name\":\"TVN\",\"preferredM3u\":\"TVN.cl@Direct138\","
                        + "\"backupm3u\":[\"TVN.cl@Direct45\",\"TVN.cl@Direct38b\",\"0104\",\"bad://x\"],")
                .replace("{\"kind\":\"provider\",\"provider\":\"highfly\"",
                        m3uRow("TVN.cl@Direct138", 9, 79) + "," + m3uRow("TVN.cl@Direct45", 10, 74) + ","
                                + m3uRow("TVN.cl@Direct38b", 11, 73) + ","
                                + "{\"kind\":\"provider\",\"provider\":\"highfly\"");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);
        Channel tvn = direct("TVN", "https://example.org/tvn.m3u8", "0104");
        Channel better = direct("TVN 138", "http://example.org/hd.m3u8", "TVN.cl@Direct138");
        Channel ip45 = direct("TVN 45", "http://example.org/45.m3u8", "TVN.cl@Direct45");
        Channel ip38 = direct("TVN 38", "http://example.org/38.m3u8", "TVN.cl@Direct38b");

        List<Channel> playback = catalog.applyToPlayback(Arrays.asList(tvn, better, ip45, ip38));

        assertEquals(1, playback.size());
        Channel played = playback.get(0);
        assertEquals("0104", played.getTvgId());
        assertEquals(better.getStreamUri(), played.getStreamUri());
        assertEquals(Arrays.asList(tvn.getStreamUri(), ip45.getStreamUri(), ip38.getStreamUri()),
                TvVooBackup.directBackupsOf(played));
        assertEquals(ip38.getStreamUri(),
                TvVooBackup.directResolutionChannel(played, 2).getStreamUri());
        assertEquals(null, TvVooBackup.directResolutionChannel(played, 3));
        assertEquals(tvn.getStreamUri(), TvVooBackup.resolutionChannel(played).getStreamUri());
        assertTrue(TvVooBackup.directResolutionChannel(played, 1).getAttributes()
                .get(TvVooBackup.DIRECT_ATTRIBUTE) == null);
    }

    @Test
    public void backupm3uWithoutPreferredKeepsOwnStreamFirst() throws Exception {
        String edited = document()
                .replace("\"name\":\"TVN\",", "\"name\":\"TVN\",\"backupm3u\":[\"TVN.cl@Direct45\"],")
                .replace("{\"kind\":\"provider\",\"provider\":\"highfly\"",
                        m3uRow("TVN.cl@Direct45", 9, 74) + ",{\"kind\":\"provider\",\"provider\":\"highfly\"");
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(edited);
        Channel tvn = direct("TVN", "https://example.org/tvn.m3u8", "0104");
        Channel ip45 = direct("TVN 45", "http://example.org/45.m3u8", "TVN.cl@Direct45");

        Channel played = catalog.applyToPlayback(Arrays.asList(tvn, ip45)).get(0);

        assertEquals(tvn.getStreamUri(), played.getStreamUri());
        assertEquals(Collections.singletonList(ip45.getStreamUri()), TvVooBackup.directBackupsOf(played));
    }

    private static String m3uRow(String tvgId, int order, int number) {
        return "{\"kind\":\"m3u\",\"tvgId\":\"" + tvgId + "\",\"name\":\"" + tvgId + "\","
                + "\"group\":\"Nacionales\",\"sourceList\":\"1.m3u\","
                + "\"order\":" + order + ",\"number\":" + number + ",\"state\":\"active\"}";
    }

    private static Channel direct(String name, String url, String tvgId) {
        return new Channel(name, java.net.URI.create(url), null, "Nacionales",
                Collections.singletonMap("tvg-id", tvgId));
    }

    private static String document() {
        return "{\"schemaVersion\":1,\"channels\":["
                + "{\"kind\":\"m3u\",\"tvgId\":\"0104\",\"name\":\"TVN\","
                + "\"group\":\"Nacionales\",\"sourceList\":\"1.m3u\","
                + "\"order\":1,\"number\":10,\"state\":\"active\"},"
                + "{\"kind\":\"provider\",\"provider\":\"highfly\","
                + "\"catalogKey\":\"SkySportsF1.uk\","
                + "\"providerResourceId\":\"leaf:f1-hd\",\"resolverSlug\":\"f1-hd\","
                + "\"identityState\":\"canonical\",\"name\":\"Sky Sports F1\","
                + "\"group\":\"Highfly · Deportes\",\"category\":\"Motor Sports\","
                + "\"order\":2,\"number\":4,\"state\":\"active\"},"
                + "{\"kind\":\"provider\",\"provider\":\"tvvoo\","
                + "\"catalogKey\":\"spain|vavoo_ESPN%201%7Cgroup%3Aes\","
                + "\"providerResourceId\":\"spain|vavoo_ESPN%201%7Cgroup%3Aes\","
                + "\"alias\":\"vavoo_ESPN%201%7Cgroup%3Aes\",\"name\":\"ESPN 1\","
                + "\"country\":\"spain\",\"group\":\"TvVoo · España\","
                + "\"category\":\"Sport\","
                + "\"aliases\":[\"vavoo_ESPN%201%7Cgroup%3Aes\"],"
                + "\"order\":3,\"number\":7,\"state\":\"active\"},"
                + "{\"kind\":\"m3u\",\"tvgId\":\"0201\",\"name\":\"24 Horas\","
                + "\"group\":\"Noticias\",\"order\":4,\"number\":11,\"state\":\"hidden\"},"
                + "{\"kind\":\"m3u\",\"tvgId\":\"1153\",\"name\":\"CHV Noticias\","
                + "\"group\":\"Noticias\",\"order\":5,\"number\":12,\"state\":\"deleted\"}]}";
    }
}

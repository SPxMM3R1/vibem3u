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
        assertEquals("deleted", catalog.getRows().get(4).state);
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
        assertFalse(PublishedPlaybackCatalog.parse(withoutActiveProviders).hasActiveProviderChannels());
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
                "", "vavoo_ESPN%201|group:es", "ESPN 1", "spain", "TvVoo", "Sport", "",
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

    private static void assertInvalid(String value) throws Exception {
        try {
            PublishedPlaybackCatalog.parse(value);
            fail("El documento inválido se aceptó.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
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

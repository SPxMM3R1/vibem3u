package cl.streambox.tv;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TvVooBackupTest {
    private static final String ID = "CNCVerse.Chile.t13-en-vivo-1080p-50f5d7c89f@CNCVerse";
    private static Channel direct() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "0124");
        attributes.put("x-resolver-id", "old-primary-reference");
        return new Channel("T13", URI.create("https://example.org/t13.m3u8"),
                URI.create("https://example.org/logo.png"), "Noticias", attributes);
    }
    private static Channel cnc(String name, String id) {
        return new Channel(name, DynamicSourceReference.create("cncverse", "chiletv|" + name + "|auto"),
                null, "Noticias", Collections.singletonMap("tvg-id", id));
    }
    @Test public void cncBackupKeepsPrincipalAndResolvesWithBackupPublicIdentity() {
        Channel primary = direct(), backup = cnc("T13 En Vivo (1080p)", ID);
        Channel projected = TvVooBackup.withDirectBackups(primary, Collections.singletonList(backup));
        assertEquals(primary.getStreamUri(), projected.getStreamUri());
        assertEquals("0124", projected.getTvgId());
        assertTrue(TvVooBackup.has(projected));
        Channel resolving = TvVooBackup.directResolutionChannel(projected, 0);
        assertNotNull(resolving);
        assertEquals(ID, resolving.getTvgId());
        assertEquals("T13", resolving.getName());
        assertEquals(primary.getLogoUri(), resolving.getLogoUri());
        assertEquals("cncverse", resolving.getAttributes().get("x-resolver"));
        assertEquals("chiletv|T13 En Vivo (1080p)|auto", resolving.getAttributes().get("x-resolver-id"));
        assertTrue(new CncVerseStreamResolver().supports(resolving));
        assertNull(resolving.getAttributes().get(TvVooBackup.DIRECT_ATTRIBUTE));
    }
    @Test public void mixedBackupsPreserveOrderAndSeparateCncCacheIdentities() {
        Channel primary = direct();
        Channel http = new Channel("Directo", URI.create("https://example.org/backup.m3u8"), null,
                "Noticias", Collections.singletonMap("tvg-id", "T13.direct"));
        Channel first = cnc("T13 En Vivo (1080p)", ID), second = cnc("T13", "T13.other@CNCVerse");
        Channel projected = TvVooBackup.withDirectBackups(primary, Arrays.asList(http, first, second, first));
        assertEquals(Arrays.asList(http.getStreamUri(), first.getStreamUri(), second.getStreamUri()),
                TvVooBackup.directBackupsOf(projected));
        CncVerseStreamResolver resolver = new CncVerseStreamResolver();
        assertEquals(ID, resolver.stableSourceId(TvVooBackup.directResolutionChannel(projected, 1)));
        assertEquals("T13.other@CNCVerse", resolver.stableSourceId(TvVooBackup.directResolutionChannel(projected, 2)));
        assertNull(TvVooBackup.directResolutionChannel(projected, 3));
    }
    @Test public void internalBackupRejectsQueryUnknownSchemeAndInvalidCncReference() {
        URI valid = cnc("T13", ID).getStreamUri();
        for (String invalid : Arrays.asList(valid + "?token=test", "file:///etc/passwd",
                "vibem3u://resolver/cncverse/opaque", "vibem3u://resolver/unknown/example")) {
            Channel backup = new Channel("Invalid", URI.create(invalid), null, "Noticias",
                    Collections.singletonMap("tvg-id", ID));
            assertFalse(TvVooBackup.has(TvVooBackup.withDirectBackups(direct(), Collections.singletonList(backup))));
        }
    }
    @Test public void internalBackupRequiresPublicIdAndDoesNotMutateInputs() {
        Channel primary = direct();
        Map<String, String> prior = new LinkedHashMap<>(primary.getAttributes());
        Channel emptyId = cnc("T13", "");
        assertFalse(TvVooBackup.has(TvVooBackup.withDirectBackups(primary, Collections.singletonList(emptyId))));
        TvVooBackup.withDirectBackups(primary, Collections.singletonList(cnc("T13", ID)));
        assertEquals(prior, primary.getAttributes());
    }
    @Test public void realLayoutProjects217InsideT13WithoutSeparateVisibleChannel() throws Exception {
        String json = "{\"schemaVersion\":1,\"channels\":["
                + "{\"kind\":\"m3u\",\"tvgId\":\"0124\",\"name\":\"T13\",\"sourceList\":\"1.m3u\","
                + "\"order\":1,\"number\":9,\"state\":\"active\",\"backupm3u\":[\"" + ID + "\"]},"
                + "{\"kind\":\"m3u\",\"tvgId\":\"" + ID + "\",\"name\":\"T13 En Vivo (1080p)\","
                + "\"sourceList\":\"1.m3u\",\"order\":2,\"number\":217,\"state\":\"active\",\"trial\":true}]}";
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(json);
        List<Channel> visible = catalog.applyToPlayback(Arrays.asList(direct(), cnc("T13 En Vivo (1080p)", ID)));
        assertEquals(1, visible.size());
        assertEquals(9, catalog.numberFor(visible.get(0), -1));
        assertEquals("0124", visible.get(0).getTvgId());
        assertEquals(ID, TvVooBackup.directResolutionChannel(visible.get(0), 0).getTvgId());
    }
}

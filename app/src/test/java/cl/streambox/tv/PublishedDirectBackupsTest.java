package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class PublishedDirectBackupsTest {
    private static final String DOCUMENT = "{\"schema\":1,\"channels\":{"
            + "\"0104\":{\"backups\":["
            + "{\"tvg_id\":\"PublicDirect.aaa\",\"display_name\":\"TVN\",\"group\":\"Deportes\","
            + "\"url\":\"https://example.test/a.m3u8\"},"
            + "{\"tvg_id\":\"PublicDirect.bbb\",\"display_name\":\"TVN 2\",\"group\":\"Deportes\","
            + "\"url\":\"http://example.test/b.m3u8\"}"
            + "]}}}";

    @Test
    public void keepsCopiesInFileOrderWithTheirOwnIdentity() {
        PublishedDirectBackups.update(DOCUMENT);

        List<Channel> copies = PublishedDirectBackups.backupsFor("0104");
        assertEquals(2, copies.size());
        assertEquals("PublicDirect.aaa", copies.get(0).getTvgId());
        assertEquals("https://example.test/a.m3u8", copies.get(0).getStreamUri().toString());
        assertEquals("TVN 2", copies.get(1).getName());
        assertTrue(PublishedDirectBackups.backupsFor("otro").isEmpty());
        PublishedDirectBackups.update(null);
    }

    @Test
    public void invalidDocumentLeavesNoBackups() {
        PublishedDirectBackups.update(DOCUMENT.replace("\"schema\":1", "\"schema\":2"));

        assertTrue(PublishedDirectBackups.backupsFor("0104").isEmpty());
    }

    @Test
    public void skipsCopiesWithoutIdentityOrWithNonWebAddress() {
        PublishedDirectBackups.update("{\"schema\":1,\"channels\":{\"0104\":{\"backups\":["
                + "{\"display_name\":\"Sin id\",\"url\":\"https://example.test/a.m3u8\"},"
                + "{\"tvg_id\":\"PublicDirect.ccc\",\"display_name\":\"Resolutor\","
                + "\"url\":\"vibem3u://resolver/m6foot/90\"},"
                + "{\"tvg_id\":\"PublicDirect.ddd\",\"display_name\":\"Buena\","
                + "\"url\":\"https://example.test/d.m3u8\"}"
                + "]}}}");

        List<Channel> copies = PublishedDirectBackups.backupsFor("0104");
        assertEquals(1, copies.size());
        assertEquals("PublicDirect.ddd", copies.get(0).getTvgId());
        PublishedDirectBackups.update(null);
    }

    @Test
    public void publishedBackupsReachTheChannelPlaybackOrder() throws Exception {
        PublishedDirectBackups.update(DOCUMENT);
        PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(
                "{\"schemaVersion\":1,\"channels\":[{\"kind\":\"m3u\",\"tvgId\":\"0104\","
                + "\"name\":\"TVN\",\"group\":\"Nacionales\",\"sourceList\":\"1.m3u\","
                + "\"displayName\":\"TVN\",\"order\":1,\"number\":1,\"state\":\"active\"}]}");
        Map<String, String> attributes = new HashMap<>();
        attributes.put("tvg-id", "0104");
        Channel primary = new Channel("TVN", URI.create("https://example.test/tvn.m3u8"), null,
                "Nacionales", attributes);

        List<Channel> playable = catalog.applyToPlayback(Collections.singletonList(primary));

        assertEquals(1, playable.size());
        assertEquals(2, TvVooBackup.directBackupsOf(playable.get(0)).size());
        PublishedDirectBackups.update(null);
    }
}

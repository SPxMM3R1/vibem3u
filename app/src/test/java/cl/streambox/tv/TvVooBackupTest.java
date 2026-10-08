package cl.streambox.tv;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TvVooBackupTest {
    private static Channel direct() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "0124");
        attributes.put("x-resolver", "tvn");
        attributes.put("x-resolver-id", "old-primary-reference");
        return new Channel("T13", URI.create("https://example.org/t13.m3u8"),
                URI.create("https://example.org/logo.png"), "Noticias", attributes);
    }

    private static Channel http(String name, String url, String id) {
        return new Channel(name, URI.create(url), null, "Noticias",
                Collections.singletonMap("tvg-id", id));
    }

    @Test public void httpBackupKeepsPrincipalAndResolvesWithItsOwnIdentity() {
        Channel primary = direct();
        Channel backup = http("T13 respaldo", "https://example.org/backup.m3u8", "T13.backup");
        Channel projected = TvVooBackup.withDirectBackups(primary, Collections.singletonList(backup));
        assertEquals(primary.getStreamUri(), projected.getStreamUri());
        assertEquals("0124", projected.getTvgId());
        assertTrue(TvVooBackup.has(projected));
        Channel resolving = TvVooBackup.directResolutionChannel(projected, 0);
        assertEquals(backup.getStreamUri(), resolving.getStreamUri());
        assertEquals("T13.backup", resolving.getTvgId());
        assertEquals("T13", resolving.getName());
        assertEquals(primary.getLogoUri(), resolving.getLogoUri());
        assertNull(resolving.getAttributes().get("x-resolver"));
        assertNull(resolving.getAttributes().get("x-resolver-id"));
        assertNull(resolving.getAttributes().get(TvVooBackup.DIRECT_ATTRIBUTE));
    }

    @Test public void mixedBackupsPreserveOrderAndDropDuplicates() {
        Channel primary = direct();
        Channel first = http("A", "https://example.org/a.m3u8", "T13.a");
        Channel second = http("B", "https://example.org/b.m3u8", "T13.b");
        Channel projected = TvVooBackup.withDirectBackups(primary, Arrays.asList(first, second, first));
        assertEquals(Arrays.asList(first.getStreamUri(), second.getStreamUri()),
                TvVooBackup.directBackupsOf(projected));
        assertEquals("T13.a", TvVooBackup.directResolutionChannel(projected, 0).getTvgId());
        assertEquals("T13.b", TvVooBackup.directResolutionChannel(projected, 1).getTvgId());
        assertNull(TvVooBackup.directResolutionChannel(projected, 2));
    }

    @Test public void validatedNautaReferenceCanBeResolvedAsDirectBackup() {
        URI reference = DynamicSourceReference.create("nauta", "cat_4|Canal X");
        assertNotNull(reference);
        Channel backup = new Channel("Canal X", reference, null, "Nauta",
                Collections.singletonMap("tvg-id", "Nauta.base@Nauta"));

        Channel projected = TvVooBackup.withDirectBackups(direct(), Collections.singletonList(backup));
        Channel resolving = TvVooBackup.directResolutionChannel(projected, 0);

        assertEquals(Collections.singletonList(reference), TvVooBackup.directBackupsOf(projected));
        assertEquals(reference, resolving.getStreamUri());
        assertEquals("Nauta.base@Nauta", resolving.getTvgId());
        assertTrue(new NautaStreamResolver().supports(resolving));
    }

    @Test public void unsafeBackupsAreRejected() {
        for (String invalid : Arrays.asList("file:///etc/passwd", "vibem3u://resolver/unknown/example",
                "vibem3u://resolver/nauta/not-valid")) {
            Channel backup = new Channel("Invalid", URI.create(invalid), null, "Noticias",
                    Collections.singletonMap("tvg-id", "T13.bad"));
            assertFalse(TvVooBackup.has(TvVooBackup.withDirectBackups(direct(), Collections.singletonList(backup))));
        }
    }

    @Test public void buildingBackupsDoesNotMutateInputs() {
        Channel primary = direct();
        Map<String, String> prior = new LinkedHashMap<>(primary.getAttributes());
        TvVooBackup.withDirectBackups(primary, Collections.singletonList(
                http("B", "https://example.org/b.m3u8", "T13.b")));
        assertEquals(prior, primary.getAttributes());
    }
}

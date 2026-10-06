package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Memoria de la señal elegida y nombres de versiones de otros países (0.5.75). */
public final class SourceMemoryTest {
    @Test
    public void versionFromAnotherCountryShowsItsCountry() {
        String spain = "vavoo_EUROSPORT%201%7Cgroup%3Aes";
        assertEquals("EUROSPORT 1 HD · PT",
                TvVooStreamResolver.versionLabel("vavoo_EUROSPORT%201%20HD%7Cgroup%3Apt", spain));
        assertEquals("EUROSPORT 1 HD",
                TvVooStreamResolver.versionLabel("vavoo_EUROSPORT%201%20HD%7Cgroup%3Aes", spain));
        assertEquals("EUROSPORT 1 · UK",
                TvVooStreamResolver.versionLabel("vavoo_EUROSPORT%201%7Cgroup%3Auk",
                        "spain|" + spain));
    }

    @Test
    public void groupCodeIsReadFromEncodedOrPlainAliases() {
        assertEquals("de", TvVooStreamResolver.groupCode("vavoo_EUROSPORT%202%7Cgroup%3ADE"));
        assertEquals("fr", TvVooStreamResolver.groupCode("vavoo_EUROSPORT 2|group:fr"));
        assertEquals("", TvVooStreamResolver.groupCode("vavoo_EUROSPORT 2"));
    }

    @Test
    public void rememberedDirectBackupIsAFingerprintNotTheAddress() {
        URI first = URI.create("http://38.44.109.41:8003/play/a0gs/index.m3u8");
        URI second = URI.create("http://45.173.231.22:8000/play/a06l/index.m3u8");
        String choice = PlaybackPreferences.directBackupChoice(first);
        assertTrue(choice.startsWith(PlaybackPreferences.SOURCE_DIRECT_BACKUP_PREFIX));
        assertTrue(!choice.contains("38.44.109.41"));
        assertEquals(choice, PlaybackPreferences.directBackupChoice(first));
        assertNotEquals(choice, PlaybackPreferences.directBackupChoice(second));
    }

    @Test
    public void withoutAStoreTheAliasOrderIsUnchanged() {
        List<String> aliases = Arrays.asList("vavoo_A%7Cgroup%3Aes", "vavoo_B%7Cgroup%3Apt");
        assertEquals(aliases, TvVooSourceHistory.withPinnedFirst("spain|vavoo_A", aliases));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class M3uCacheSanitizerTest {
    @Test
    public void removesOnlyRenewableProviderQueryTokens() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"0104\",TVN\n"
                + "https://mdstrm.example/live.m3u8?access_token=temporary\n"
                + "#EXTINF:-1 tvg-id=\"MeganoticiasAhora.cl\",Mega\n"
                + "https://mdstrm.example/mega.m3u8?access_token=temporary\n"
                + "#EXTINF:-1 tvg-id=\"other\",Otro\n"
                + "https://example.org/other.m3u8?token=keep\n";

        String sanitized = M3uCacheSanitizer.forDisk(playlist);

        assertTrue(sanitized.contains("https://mdstrm.example/live.m3u8"));
        assertTrue(sanitized.contains("https://mdstrm.example/mega.m3u8"));
        assertFalse(sanitized.contains("access_token=temporary"));
        assertTrue(sanitized.contains("https://example.org/other.m3u8?token=keep"));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void removesLegacyProviderFragmentsToo() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"0104\",TVN\n"
                + "https://mdstrm.example/live.m3u8#access_token=legacy\n";

        String sanitized = M3uCacheSanitizer.forDisk(playlist);

        assertTrue(sanitized.contains("https://mdstrm.example/live.m3u8\n"));
        assertFalse(sanitized.contains("legacy"));
        assertEquals(sanitized, M3uCacheSanitizer.forDisk(sanitized));
    }

    @Test
    public void neverPersistsTvVooSessionPathsAndKeepsStableMetadata() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"SkySportsNFL.uk@TvVoo\" "
                + "x-resolver=\"tvvoo\" x-resolver-ids=\"stable-one;stable-two\",NFL\n"
                + "http://temporary.example/sunshine/SECRET/hls/index.m3u8\n";

        String sanitized = M3uCacheSanitizer.forDisk(playlist);

        assertTrue(sanitized.contains("x-resolver-ids=\"stable-one;stable-two\""));
        assertTrue(sanitized.contains("https://resolver.invalid/tvvoo.m3u8"));
        assertFalse(sanitized.contains("SECRET"));
        assertEquals(sanitized, M3uCacheSanitizer.forDisk(sanitized));
    }

    @Test
    public void keepsNonCredentialQueryParametersForDynamicFallbacks() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"0201\" x-resolver=\"24horas\",24 Horas\n"
                + "https://mdstrm.com/live.m3u8?quality=high&access_token=secret\n";

        String sanitized = M3uCacheSanitizer.forDisk(playlist);

        assertTrue(sanitized.contains("?quality=high"));
        assertFalse(sanitized.contains("access_token"));
    }
}

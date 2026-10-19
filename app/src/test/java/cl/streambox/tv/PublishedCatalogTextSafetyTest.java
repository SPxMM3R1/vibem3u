package cl.streambox.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Random;

/** La búsqueda sin regex (0.6.17) decide igual que el patrón que reemplazó. */
public final class PublishedCatalogTextSafetyTest {
    @Test
    public void manualScanMatchesTheOriginalPattern() {
        String[] samples = {
                "Sky Sports F1", "https://x", "HTTP://X", "lista.m3u", "lista.M3U8", "a.m3u8?x=1",
                "a.m3u8x", "a.m3ux", "a.m3u_b", "a.m3u é", "a.m3ué", "a.m3u8é", "access_token=1",
                "Token=2", "signature=3", "hdnts=4", "tokenx", "m3u", ".m3u", ".m3u8", "x.m3u 8",
                "http:/x", "ESPN 2", "spain|vavoo_ESPN%201%7Cgroup%3Aes", "",
        };
        for (String sample : samples) {
            assertEquals(sample, original(sample), PublishedPlaybackCatalog.isUnsafeText(sample));
        }
        String alphabet = "aM.3u8 _é?=/:htpsokenigdHT";
        Random random = new Random(7);
        for (int round = 0; round < 20_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(14);
            for (int index = 0; index < length; index++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String sample = text.toString();
            assertEquals(sample, original(sample), PublishedPlaybackCatalog.isUnsafeText(sample));
        }
    }

    private static boolean original(String text) {
        return text.matches("(?is).*https?://.*|.*\\.m3u8?(?:\\b|\\?).*"
                + "|.*(?:access_token|token|signature|hdnts)=.*");
    }
}

package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HighflyPremiumStableIdentityTest {
    private static HighflyPremiumCatalogRepository repository() {
        return new HighflyPremiumCatalogRepository(null, null, null, null, 0L);
    }

    @Test
    public void listOneHighflyPremiumTvgIdUsesProtectedStablePlayback() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "HighflyPremium.now-sky-sports-f1-2");
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-id", "now-sky-sports-f1-2");
        attributes.put("x-resolver-manifest", "https://sports.highfly.dev/example/manifest.json");
        Channel channel = new Channel(
                "Sky Sports F1 UHD",
                URI.create("https://leaf.highfly.dev/m3u/now-sky-sports-f1-2/live.m3u8"),
                null,
                "Deportes",
                attributes
        );

        assertTrue(repository().isPremiumStableChannel(channel));
    }

    @Test
    public void generatedStableMetadataStillUsesProtectedPlayback() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "highfly-premium:now-sky-sports-f1-2");
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-id", "now-sky-sports-f1-2");
        attributes.put("x-highfly-premium-stable", "true");
        attributes.put("x-highfly-premium-kind", "estable");
        attributes.put("x-highfly-premium-list", "3");
        attributes.put("x-highfly-premium-id", "leaf:now-sky-sports-f1-2");
        Channel channel = new Channel(
                "Sky Sports F1 UHD",
                URI.create("https://leaf.highfly.dev/m3u/now-sky-sports-f1-2/live.m3u8"),
                null,
                "Lista 3",
                attributes
        );

        assertTrue(repository().isPremiumStableChannel(channel));
    }

    @Test
    public void ordinaryHighflyChannelDoesNotConsumePremiumCredential() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "SkySportsF1.uk");
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-id", "now-sky-sports-f1-free");
        Channel channel = new Channel(
                "Sky Sports F1",
                URI.create("https://leaf.highfly.dev/m3u/now-sky-sports-f1-free/live.m3u8"),
                null,
                "Deportes",
                attributes
        );

        assertFalse(repository().isPremiumStableChannel(channel));
    }

    @Test
    public void temporaryPremiumEventIsNotReclassifiedAsStable() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "highfly-premium:event-123");
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-id", "event-123");
        attributes.put("x-highfly-premium", "true");
        attributes.put("x-highfly-premium-virtual", "true");
        attributes.put("x-highfly-premium-kind", "evento");
        attributes.put("x-highfly-premium-list", "4");
        attributes.put("x-highfly-premium-id", "event-123");
        Channel channel = new Channel(
                "Evento Premium",
                URI.create("https://premium.highfly.dev/premium-event/event.m3u8"),
                null,
                "Lista 4",
                attributes
        );

        assertFalse(repository().isPremiumStableChannel(channel));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.Instant;

import org.junit.After;
import org.junit.Test;

public final class PublishedHighflyLinksTest {
    private static final long NOW = Instant.parse("2026-09-29T02:00:00Z").toEpochMilli();

    private static String document(String generatedAt, String slug, String url) {
        return "{\"schema\":1,\"generatedAt\":\"" + generatedAt + "\",\"channels\":[{"
                + "\"catalogKey\":\"SkySportsF1.uk\",\"slug\":\"" + slug + "\",\"url\":\"" + url + "\"}]}";
    }

    @After
    public void reset() {
        PublishedHighflyLinks.resetForTests();
    }

    @Test
    public void usesTheRunnerLinkForTheChannel() {
        PublishedHighflyLinks.update(document("2026-09-29T01:30:00Z", "now-545445",
                "https://papacito.cfd/m3u/now-545445/live.m3u8"), NOW);

        assertEquals(URI.create("https://papacito.cfd/m3u/now-545445/live.m3u8"),
                PublishedHighflyLinks.usable("SkySportsF1.uk"));
        assertNull(PublishedHighflyLinks.usable("SkySportsTennis.uk"));
    }

    @Test
    public void aFailedLinkFallsBackToTheResolverForTheSession() {
        PublishedHighflyLinks.update(document("2026-09-29T01:30:00Z", "now-545445",
                "https://papacito.cfd/m3u/now-545445/live.m3u8"), NOW);
        PublishedHighflyLinks.markFailed(PublishedHighflyLinks.usable("SkySportsF1.uk"));

        assertNull(PublishedHighflyLinks.usable("SkySportsF1.uk"));
    }

    @Test
    public void aFailureInOneChannelKeepsAnotherChannelWithTheSameLink() {
        String url = "https://papacito.cfd/m3u/now-545445/live.m3u8";
        PublishedHighflyLinks.update("{\"schema\":1,\"generatedAt\":\"2026-09-29T01:30:00Z\",\"channels\":["
                + "{\"catalogKey\":\"SkySportsF1.uk\",\"slug\":\"now-545445\",\"url\":\"" + url + "\"},"
                + "{\"catalogKey\":\"SkySportsF1UHD.uk\",\"slug\":\"now-545445\",\"url\":\"" + url + "\"}]}", NOW);
        PublishedHighflyLinks.markFailed("SkySportsF1.uk");

        assertNull(PublishedHighflyLinks.usable("SkySportsF1.uk"));
        assertEquals(URI.create(url), PublishedHighflyLinks.usable("SkySportsF1UHD.uk"));
    }

    @Test
    public void rejectsLinksThatAreNotTheExactTokenFreeForm() {
        assertTrue(PublishedHighflyLinks.parse(document("2026-09-29T01:30:00Z", "now-545445",
                "https://papacito.cfd/m3u/now-545445/live.m3u8?token=x"), NOW).isEmpty());
        assertTrue(PublishedHighflyLinks.parse(document("2026-09-29T01:30:00Z", "now-545445",
                "https://evil.example/m3u/now-545445/live.m3u8"), NOW).isEmpty());
        assertTrue(PublishedHighflyLinks.parse(document("2026-09-29T01:30:00Z", "now-545445",
                "https://papacito.cfd/m3u/otro/live.m3u8"), NOW).isEmpty());
    }

    @Test
    public void ignoresOldOrInvalidDocuments() {
        assertTrue(PublishedHighflyLinks.parse(document("2026-09-27T01:30:00Z", "now-545445",
                "https://papacito.cfd/m3u/now-545445/live.m3u8"), NOW).isEmpty());
        assertTrue(PublishedHighflyLinks.parse("no es json", NOW).isEmpty());
        assertTrue(PublishedHighflyLinks.parse("{\"schema\":2}", NOW).isEmpty());
    }
}

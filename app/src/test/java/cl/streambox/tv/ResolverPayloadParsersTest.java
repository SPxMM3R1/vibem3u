package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ResolverPayloadParsersTest {
    @Test
    public void readsAndDeduplicatesTvVooJsonCandidates() throws Exception {
        List<URI> candidates = ResolverPayloadParsers.parseTvVooCandidates(
                "{\"streams\":[{\"url\":\"https://cdn.example/one.m3u8\"},"
                        + "{\"url\":\"https://cdn.example/one.m3u8\"},"
                        + "{\"url\":\"http://cdn.example/two.m3u8\"}]}"
        );

        assertEquals(2, candidates.size());
        assertEquals("https://cdn.example/one.m3u8", candidates.get(0).toString());
    }

    @Test
    public void supportsCatalogDrivenTvVooJsonFields() throws Exception {
        List<URI> candidates = ResolverPayloadParsers.parseTvVooCandidates(
                "{\"data\":{\"items\":[{\"hls\":\"https://cdn.example/live.m3u8\"}]}}",
                "data.items",
                "hls"
        );

        assertEquals(1, candidates.size());
        assertEquals("https://cdn.example/live.m3u8", candidates.get(0).toString());
    }

    @Test
    public void readsHighflyBySlugWithoutDependingOnOneManifestShape() throws Exception {
        URI uri = ResolverPayloadParsers.parseHighflyManifest(
                "{\"channels\":[{\"slug\":\"now-sky-sports-f1-free\","
                        + "\"source\":{\"hls\":\"https://papacito.cfd/m3u/f1/live.m3u8\"}}]}",
                Arrays.asList("now-sky-sports-f1-free", "SkySportsF1.uk")
        );

        assertEquals("https://papacito.cfd/m3u/f1/live.m3u8", uri.toString());
    }

    @Test
    public void ordersHighflyCandidatesByAdvertisedBitrateAndReadsStremioMetadata()
            throws Exception {
        List<ResolverPayloadParsers.HighflyCandidate> candidates =
                ResolverPayloadParsers.parseHighflyCandidates(
                        "{\"streams\":["
                                + "{\"name\":\"Leaf low\","
                                + "\"title\":\"1920x1080 · Stereo · ~3.8 Mbps\","
                                + "\"url\":\"https://leaf.highfly.dev/low.m3u8\"},"
                                + "{\"name\":\"Leaf high\","
                                + "\"title\":\"1920x1080 · Stereo · ~4.9 Mbps\","
                                + "\"url\":\"https://papacito.cfd/high.m3u8\"},"
                                + "{\"name\":\"No bitrate\","
                                + "\"url\":\"https://leaf.highfly.dev/unknown.m3u8\"}]}" ,
                        "streams",
                        16
                );

        assertEquals(3, candidates.size());
        assertEquals(
                "https://papacito.cfd/high.m3u8",
                candidates.get(0).getUri().toString()
        );
        assertEquals(4_900_000L, candidates.get(0)
                .getAdvertisedBitrateBitsPerSecond());
        assertEquals(1920, candidates.get(0).getWidth());
        assertEquals(1080, candidates.get(0).getHeight());
        assertTrue(candidates.get(0).displayDetail().contains("4.9 Mbps"));
        assertEquals(
                "https://leaf.highfly.dev/low.m3u8",
                candidates.get(1).getUri().toString()
        );
        assertEquals(0L, candidates.get(2).getAdvertisedBitrateBitsPerSecond());
    }

    @Test
    public void ignoresNonHlsHighflyStreamsAndHonorsTheStreamLimit() throws Exception {
        List<ResolverPayloadParsers.HighflyCandidate> candidates =
                ResolverPayloadParsers.parseHighflyCandidates(
                        "{\"streams\":["
                                + "{\"url\":\"https://www.google.com/\"},"
                                + "{\"url\":\"https://leaf.highfly.dev/one.m3u8\"},"
                                + "{\"url\":\"https://leaf.highfly.dev/two.m3u8\"}]}" ,
                        "streams",
                        2
                );

        assertEquals(1, candidates.size());
        assertEquals(
                "https://leaf.highfly.dev/one.m3u8",
                candidates.get(0).getUri().toString()
        );
    }

    @Test
    public void boundedRecipeFindsSerializedBase64AndUrlEncodedHls() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(
                "https://cdn.example/live%2Dsession.m3u8%3Ftoken%3Dfresh"
                        .getBytes(StandardCharsets.UTF_8)
        );
        String payload = "{\"wrapper\":\"{\\\"deep\\\":\\\"" + encoded
                + "\\\"}\"}";

        List<URI> candidates = ResolverPayloadParsers.parseBoundedHlsCandidates(
                payload,
                URI.create("https://tvvoo.hayd.uk/stream/tv/alias.json"),
                6,
                64,
                8
        );

        assertEquals(1, candidates.size());
        assertEquals(
                "https://cdn.example/live-session.m3u8?token=fresh",
                candidates.get(0).toString()
        );
    }

    @Test
    public void boundedRecipeIgnoresExecutableTextAndNonHlsUrls() throws Exception {
        List<URI> candidates = ResolverPayloadParsers.parseBoundedHlsCandidates(
                "<script>eval('https://cdn.example/not-video.js')</script>",
                URI.create("https://tvvoo.hayd.uk/stream/tv/alias.json"),
                3,
                32,
                4
        );

        assertEquals(0, candidates.size());
    }

}

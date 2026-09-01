package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;

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
                        + "\"source\":{\"hls\":\"https://leaf.highfly.dev/m3u/f1/live.m3u8\"}}]}",
                Arrays.asList("now-sky-sports-f1-free", "SkySportsF1.uk")
        );

        assertEquals("https://leaf.highfly.dev/m3u/f1/live.m3u8", uri.toString());
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

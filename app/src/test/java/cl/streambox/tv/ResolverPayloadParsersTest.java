package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
    public void readsActiveTwentyFourHoursDataMsAndUsesBoundedFallback() throws Exception {
        String html = "<a data-ms=\"newStreamId123\" "
                + "class=\"tab playertablink active\">TV</a>";
        assertEquals(
                "newStreamId123",
                ResolverPayloadParsers.parseTwentyFourHoursStreamId(
                        html, "", "fallbackStream123"
                )
        );
        assertThrows(IOException.class, () ->
                ResolverPayloadParsers.parseTwentyFourHoursStreamId(
                        "<html>empty</html>", "", "bad"
                ));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** La versión elegida no entrega video y una hermana (HD/FHD/BACKUP) sí: se reproduce la hermana. */
public class TvVooVariantFallbackTest {
    private static final String SELECTED = "vavoo_SKY%20SPORTS%20MIX%7Cgroup%3Auk";
    private static final String SIBLING = "vavoo_SKY%20SPORTS%20MIX%20HD%7Cgroup%3Auk";
    private static final String STABLE_ID = "unitedkingdom|" + SELECTED;
    // IP públicas literales: la política de red no necesita DNS en la prueba.
    private static final String SIBLING_STREAM = "http://1.1.1.1/sunshine/live/index.m3u8";

    @After
    public void reset() {
        PublishedTvVooVariants.resetForTests();
    }

    @Test
    public void siblingPlaysWhenTheChosenVersionHasNoSource() throws Exception {
        PublishedTvVooVariants.update(variantsJson(Instant.now().toString()), System.currentTimeMillis());
        AtomicInteger selectedQueries = new AtomicInteger();
        FakeClient client = new FakeClient(selectedQueries);
        TvVooStreamResolver resolver = new TvVooStreamResolver(
                definition(), client, new HlsStreamValidator(client));

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertEquals("1.1.1.1", source.getPlaybackUri().getHost());
        // La elegida se consultó y se reintentó antes de darla por vacía.
        assertTrue(selectedQueries.get() >= 2);
    }

    @Test
    public void staleVariantsFileIsIgnored() {
        long now = System.currentTimeMillis();
        String old = Instant.ofEpochMilli(now - PublishedTvVooVariants.MAX_AGE_MILLIS - 60_000L).toString();
        assertTrue(PublishedTvVooVariants.parse(variantsJson(old), now).isEmpty());
    }

    @Test
    public void unsafeAliasesAreDropped() {
        String json = "{\"schema\":1,\"generatedAt\":\"" + Instant.now() + "\",\"channels\":{\""
                + STABLE_ID + "\":[\"https://evil.example/x\",\"" + SIBLING + "\"]}}";
        Map<String, List<String>> parsed = PublishedTvVooVariants.parse(json, System.currentTimeMillis());
        assertEquals(Collections.singletonList(SIBLING), parsed.get(STABLE_ID));
    }

    private static String variantsJson(String generatedAt) {
        return "{\"schema\":1,\"generatedAt\":\"" + generatedAt + "\",\"channels\":{\""
                + STABLE_ID + "\":[\"" + SIBLING + "\"]}}";
    }

    private static Channel channel() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", STABLE_ID + "@TvVoo");
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-id", SELECTED);
        attributes.put("x-resolver-ids", SELECTED);
        attributes.put("x-resolver-stable-id", STABLE_ID);
        return new Channel("SKY SPORTS MIX", URI.create("tvvoo://placeholder"), null, "UK", attributes);
    }

    private static ResolverDefinition definition() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("endpointBase", "https://tvvoo.hayd.uk/stream/tv");
        config.put("maxAliases", "8");
        return new ResolverDefinition(
                "tvvoo", "TvVoo", "tvvoo", true, 25L * 60L * 1000L,
                Collections.emptySet(), Collections.emptyList(), Collections.emptySet(),
                config, Collections.emptyMap()
        );
    }

    private static final class FakeClient extends TokenHttpClient {
        private final AtomicInteger selectedQueries;

        FakeClient(AtomicInteger selectedQueries) {
            super(1_000, 1_000);
            this.selectedQueries = selectedQueries;
        }

        @Override
        public String getText(String url, Map<String, String> headers) throws IOException {
            if (url.contains(SELECTED)) {
                selectedQueries.incrementAndGet();
                return "{\"streams\":[]}";
            }
            if (url.contains(SIBLING)) {
                return "{\"streams\":[{\"name\":\"Vavoo\",\"url\":\"" + SIBLING_STREAM + "\"}]}";
            }
            throw new IOException("alias desconocido");
        }

        @Override
        public Response getPublic(String url, Map<String, String> headers, int max, String range) {
            String playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:6.0,\nseg10.ts\n#EXTINF:6.0,\nseg11.ts\n"
                    + "#EXTINF:6.0,\nseg12.ts\n";
            return new Response(200, URI.create(url), "application/vnd.apple.mpegurl",
                    Collections.emptyMap(), playlist.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Response getPublicPrefix(String url, Map<String, String> headers, int max, String range) {
            byte[] segment = new byte[188 * 8];
            for (int index = 0; index < segment.length; index += 188) segment[index] = 0x47;
            return new Response(206, URI.create(url), "video/mp2t", Collections.emptyMap(), segment);
        }
    }
}

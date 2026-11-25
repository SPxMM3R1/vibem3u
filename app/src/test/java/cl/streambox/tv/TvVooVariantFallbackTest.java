package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * La versión elegida no entrega video y una hermana (HD/FHD/BACKUP) sí: se reproduce la hermana.
 *
 * <p>La carrera es real (la hermana puede responder antes de que la elegida termine de
 * reintentar), así que la prueba fuerza el orden: la hermana no entrega enlaces hasta que la
 * elegida fue consultada. Antes se afirmaba «se consultó al menos una vez» sin sincronizar y
 * falló una vez en CI (corrida 45505052650) cuando el hilo de la elegida aún no había corrido.
 */
public class TvVooVariantFallbackTest {
    private static final String SELECTED = "vavoo_SKY%20SPORTS%20MIX%7Cgroup%3Auk";
    private static final String SIBLING = "vavoo_SKY%20SPORTS%20MIX%20HD%7Cgroup%3Auk";
    private static final String STABLE_ID = "unitedkingdom|" + SELECTED;
    // IP públicas literales: la política de red no necesita DNS en la prueba.
    private static final String SIBLING_STREAM = "http://1.1.1.1/sunshine/live/index.m3u8";
    private static final long ORDER_WAIT_MILLIS = 5_000L;

    @Before
    public void resetStatics() {
        PublishedTvVooVariants.resetForTests();
        TvVooDeadVersions.resetForTests();
    }

    @After
    public void reset() {
        PublishedTvVooVariants.resetForTests();
        TvVooDeadVersions.resetForTests();
    }

    @Test
    public void siblingPlaysWhenTheChosenVersionHasNoSource() throws Exception {
        PublishedTvVooVariants.update(variantsJson(Instant.now().toString()), System.currentTimeMillis());
        AtomicInteger selectedQueries = new AtomicInteger();
        CountDownLatch selectedQueried = new CountDownLatch(1);
        FakeClient client = new FakeClient(selectedQueries, selectedQueried);
        TvVooStreamResolver resolver = new TvVooStreamResolver(
                definition(), client, new HlsStreamValidator(client));

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertEquals("1.1.1.1", source.getPlaybackUri().getHost());
        // La elegida se consultó primero; si no, la hermana no puede ganar.
        assertTrue("la versión elegida no llegó a consultarse",
                selectedQueried.await(1, TimeUnit.SECONDS));
        assertTrue(selectedQueries.get() >= 1);
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
        private final CountDownLatch selectedQueried;

        FakeClient(AtomicInteger selectedQueries, CountDownLatch selectedQueried) {
            super(1_000, 1_000);
            this.selectedQueries = selectedQueries;
            this.selectedQueried = selectedQueried;
        }

        @Override public String postJsonText(String url, Map<String, String> headers,
                String body, int limit) throws IOException {
            if (url.endsWith("/api/app/ping"))
                return "{\"addonSig\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}";
            if (url.contains("catalog"))
                return "{\"items\":[{\"type\":\"iptv\",\"ids\":{\"id\":\"1\"},\"name\":\"SKY SPORTS MIX\",\"group\":\"United Kingdom\",\"url\":\"https://vavoo.to/vavoo-iptv/play/selected\"},{\"type\":\"iptv\",\"ids\":{\"id\":\"2\"},\"name\":\"SKY SPORTS MIX HD\",\"group\":\"United Kingdom\",\"url\":\"https://vavoo.to/vavoo-iptv/play/sibling\"}]}";
            if (body.contains("selected")) {
                countSelectedQuery();
                return "[]";
            }
            awaitSelectedQuery();
            return "[{\"url\":\"" + SIBLING_STREAM + "\"}]";
        }

        @Override
        public String getText(String url, Map<String, String> headers) throws IOException {
            if (url.contains(SELECTED)) {
                countSelectedQuery();
                return "{\"streams\":[]}";
            }
            if (url.contains(SIBLING)) {
                awaitSelectedQuery();
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

        private void countSelectedQuery() {
            selectedQueries.incrementAndGet();
            selectedQueried.countDown();
        }

        /**
         * La hermana espera a que la elegida se haya consultado. Con tope de tiempo: si la
         * elegida no se consultara (un fallo real), la prueba falla por la afirmación y no se
         * queda colgada.
         */
        private void awaitSelectedQuery() {
            try {
                selectedQueried.await(ORDER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class M6FootStreamResolverTest {
    private static final String STREAM_URL = "https://8.8.8.8/live/espn4.m3u8";

    static Channel channel(String playerId) {
        return new Channel(
                "ESPN " + playerId,
                DynamicSourceReference.create("m6foot", playerId),
                null,
                "M6Foot",
                Map.of("tvg-id", "M6Foot." + playerId + "@M6Foot", "x-resolver", "m6foot")
        );
    }

    @Test public void tokenlessReferenceIsStableAndResolves() throws Exception {
        Channel channel = channel("90");
        assertNotNull(channel.getStreamUri());
        assertEquals("90", DynamicSourceReference.stableId(channel.getStreamUri()));
        assertEquals("m6foot", DynamicSourceReference.provider(channel.getStreamUri()));
        assertTrue(new M6FootStreamResolver().supports(channel));
        assertFalse(new M6FootStreamResolver().supports(new Channel(
                "Directo",
                URI.create("https://example.org/live.m3u8"),
                null,
                "Directo",
                Map.of()
        )));
    }

    @Test public void decodesEmbedConfigAndReturnsHlsWithReferer() throws Exception {
        Fake client = new Fake();
        ResolvedPlaybackSource source = new M6FootStreamResolver(client).resolve(channel("90"));
        assertEquals(URI.create(STREAM_URL), source.getPlaybackUri());
        assertEquals("m6foot", source.getResolverId());
        assertEquals("M6Foot.90@M6Foot", source.getStableSourceId());
        assertEquals("https://navytitle.net/", source.getRequestHeaders().get("Referer"));
        assertTrue(source.getRequestHeaders().get("User-Agent").contains("Mozilla"));
        assertEquals(1, client.pages);
        assertEquals(1, client.embeds);
        assertEquals(1, client.playlists);
        assertEquals(1, client.segments);
    }

    @Test public void rejectsMissingEmbedInvalidConfigAndPrivateMedia() {
        Fake missing = new Fake(); missing.embed = false;
        assertThrows(IOException.class, () -> new M6FootStreamResolver(missing).resolve(channel("90")));
        Fake invalid = new Fake(); invalid.config = false;
        assertThrows(IOException.class, () -> new M6FootStreamResolver(invalid).resolve(channel("90")));
        Fake privateMedia = new Fake(); privateMedia.privateMedia = true;
        assertThrows(IOException.class, () -> new M6FootStreamResolver(privateMedia).resolve(channel("90")));
        assertEquals(0, privateMedia.segments);
    }

    @Test public void rejectedSegmentNeverReturnsFalseSuccess() {
        Fake invalidSegment = new Fake(); invalidSegment.invalidSegment = true;
        assertThrows(IOException.class, () -> new M6FootStreamResolver(invalidSegment).resolve(channel("90")));
    }

    @Test public void cancellationDoesNotFetch() {
        Fake client = new Fake();
        ResolutionContext context = new ResolutionContext(1000);
        context.cancel();
        try (ResolutionContext.Scope ignored = context.activate()) {
            assertThrows(IOException.class, () -> new M6FootStreamResolver(client).resolve(channel("90")));
        }
        assertEquals(0, client.pages);
    }

    @Test public void decodeConfigRoundTrips() throws Exception {
        String json = "{\"stream_url\":\"" + STREAM_URL + "\",\"stream_url_nop2p\":\"" + STREAM_URL + "\"}";
        JSONObject decoded = M6FootStreamResolver.decodeConfig(encodeConfig(json));
        assertEquals(STREAM_URL, decoded.getString("stream_url"));
    }

    /** Inverse of the embed encoding used for the test fixture. */
    static String encodeConfig(String json) {
        String joined = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        while (joined.length() % 16 != 0) {
            json = json + " ";
            joined = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        int quarter = joined.length() / 4;
        int[] slots = {2, 0, 3, 1};
        StringBuilder inner = new StringBuilder(joined.length() + 8);
        for (int index = 0; index < 4; index++) {
            int chunk = slots[index];
            String text = joined.substring(chunk * quarter, (chunk + 1) * quarter);
            String part = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            inner.append(part, 0, 3).append('X').append(part.substring(3));
        }
        return Base64.getEncoder().encodeToString(inner.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final class Fake extends TokenHttpClient {
        int pages, embeds, playlists, segments;
        boolean embed = true, config = true, privateMedia, invalidSegment;

        @Override
        public Response getPublicOnHosts(
                String url,
                Map<String, String> headers,
                int maxResponseBytes,
                String range,
                Set<String> hosts
        ) {
            if (url.startsWith(M6FootStreamResolver.PAGE_BASE)) {
                pages++;
                assertEquals(Set.of("m6foot.click", "www.m6foot.click"), hosts);
                return response(url, embed
                        ? "<html><iframe src=\"https://navytitle.net/embed/abc123\"></iframe></html>"
                        : "<html>sin reproductor</html>");
            }
            if (url.startsWith(M6FootStreamResolver.EMBED_BASE)) {
                embeds++;
                assertEquals(Set.of("navytitle.net", "www.navytitle.net"), hosts);
                String stream = privateMedia ? "http://127.0.0.1/private.m3u8" : STREAM_URL;
                String json = "{\"stream_url\":\"" + stream + "\",\"stream_url_nop2p\":\"" + stream + "\"}";
                return response(url, config
                        ? "<html><script>window._econfig='" + encodeConfig(json) + "';</script></html>"
                        : "<html>sin configuracion</html>");
            }
            throw new AssertionError("URL inesperada: " + url);
        }

        @Override
        public Response getPublic(
                String url,
                Map<String, String> headers,
                int maxResponseBytes,
                String range
        ) {
            playlists++;
            assertEquals("https://navytitle.net/", headers.get("Referer"));
            return response(url, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n");
        }

        @Override
        public Response getPublicPrefix(
                String url,
                Map<String, String> headers,
                int maxResponseBytes,
                String range
        ) {
            segments++;
            assertEquals("https://navytitle.net/", headers.get("Referer"));
            byte[] media = new byte[512];
            if (!invalidSegment) {
                media[0] = 0x47;
                media[188] = 0x47;
            }
            return new Response(206, URI.create(url), "video/mp2t", Collections.emptyMap(), media);
        }

        private Response response(String url, String body) {
            return new Response(
                    200,
                    URI.create(url),
                    "text/html",
                    Collections.emptyMap(),
                    body.getBytes(StandardCharsets.UTF_8)
            );
        }
    }
}

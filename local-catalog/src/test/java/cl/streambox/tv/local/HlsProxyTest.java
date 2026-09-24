package cl.streambox.tv.local;

import cl.streambox.tv.ResolvedPlaybackSource;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HlsProxyTest {
    private static final Pattern ASSET_ID = Pattern.compile("/asset/([a-f0-9]{32})");
    private MockWebServer upstream;
    private MockWebServer cdn;

    @Before public void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        cdn = new MockWebServer();
        cdn.start();
    }

    @After public void tearDown() throws IOException {
        upstream.shutdown();
        cdn.shutdown();
    }

    @Test public void rewritesMasterVariantAndSegmentWithoutExposingSignedUrl() throws Exception {
        String secret = "secret-token-value";
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nvariant.m3u8?auth=" + secret + "\n"));
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXTINF:4.0,\nsegment.ts?auth=" + secret + "\n#EXT-X-ENDLIST\n"));
        byte[] segment = new byte[] { 0x47, 0x01, 0x02, 0x03 };
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "video/mp2t").setBody(new okio.Buffer().write(segment)));

        HlsProxy proxy = new HlsProxy(true);
        URI signedMaster = upstream.url("master.m3u8?auth=" + secret).uri();
        HlsProxy.Session session = proxy.open(ResolvedPlaybackSource.dynamic(
                "tvvoo", "stable-catalog-key", signedMaster, Collections.emptyMap(), "TestPlayer", 0L
        ));

        String master = fetchText(proxy, session.id, "root");
        assertTrue(master.contains("/api/preview/" + session.id + "/asset/"));
        assertFalse(master.contains(secret));
        String variantId = assetId(master);

        String media = fetchText(proxy, session.id, variantId);
        assertTrue(media.contains("#EXTINF"));
        assertFalse(media.contains(secret));
        String segmentId = assetId(media);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        proxy.stream(session.id, segmentId, null, bytes, (status, type, length, contentRange, acceptRanges) -> {
            assertTrue(status >= 200 && status < 300);
            assertTrue(type.startsWith("video/mp2t"));
        });
        assertArrayEquals(segment, bytes.toByteArray());
        proxy.close(session.id);
        assertThrows(IOException.class, () -> proxy.stream(session.id, "root", null, new ByteArrayOutputStream(), (s, t, l, r, a) -> {}));
    }

    @Test public void rejectsLoopbackUpstreamInProductionMode() throws Exception {
        HlsProxy proxy = new HlsProxy();
        URI local = upstream.url("private.m3u8").uri();
        ResolvedPlaybackSource source = ResolvedPlaybackSource.dynamic(
                "test", "stable-key", local, Collections.emptyMap(), "TestPlayer", 0L
        );
        IOException error = assertThrows(IOException.class, () -> proxy.open(source));
        assertTrue(error.getMessage().contains("destino local"));
    }

    @Test public void stripsCredentialsWhenProviderRedirectsToAnotherOrigin() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("Location", cdn.url("master.m3u8").toString()));
        cdn.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-ENDLIST\n"));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer access-secret");
        headers.put("Cookie", "session=private-secret");
        headers.put("X-API-Key", "private-api-key");
        headers.put("User-Agent", "VibeM3U-Test");
        HlsProxy proxy = new HlsProxy(true);
        HlsProxy.Session session = proxy.open(ResolvedPlaybackSource.dynamic(
                "tvvoo", "stable-catalog-key", upstream.url("master.m3u8").uri(), headers, "VibeM3U-Test", 0L
        ));

        String manifest = fetchText(proxy, session.id, "root");
        assertTrue(manifest.contains("#EXT-X-ENDLIST"));
        assertTrue(upstream.takeRequest().getHeader("Authorization").contains("access-secret"));
        okhttp3.mockwebserver.RecordedRequest redirectedRequest = cdn.takeRequest();
        assertTrue(redirectedRequest.getHeader("Authorization") == null);
        assertTrue(redirectedRequest.getHeader("Cookie") == null);
        assertTrue(redirectedRequest.getHeader("X-API-Key") == null);
        assertTrue("VibeM3U-Test".equals(redirectedRequest.getHeader("User-Agent")));
        proxy.close(session.id);
    }

    @Test public void preservesByteRangeHeadersForHlsSegments() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXTINF:4.0,\nsegment.ts\n#EXT-X-ENDLIST\n"));
        byte[] segment = new byte[] { 0x47, 0x01, 0x02, 0x03 };
        upstream.enqueue(new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp2t")
                .setHeader("Content-Range", "bytes 4-7/12")
                .setHeader("Accept-Ranges", "bytes")
                .setBody(new okio.Buffer().write(segment)));
        HlsProxy proxy = new HlsProxy(true);
        HlsProxy.Session session = proxy.open(ResolvedPlaybackSource.dynamic(
                "test", "stable-key", upstream.url("master.m3u8").uri(), Collections.emptyMap(), "TestPlayer", 0L
        ));

        String manifest = fetchText(proxy, session.id, "root");
        upstream.takeRequest();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        proxy.stream(session.id, assetId(manifest), "bytes=4-7", bytes, (status, type, length, contentRange, acceptRanges) -> {
            assertTrue(status == 206);
            assertTrue("bytes 4-7/12".equals(contentRange));
            assertTrue("bytes".equals(acceptRanges));
        });
        assertArrayEquals(segment, bytes.toByteArray());
        assertTrue("bytes=4-7".equals(upstream.takeRequest().getHeader("Range")));
        proxy.close(session.id);
    }

    private static String fetchText(HlsProxy proxy, String sessionId, String assetId) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        proxy.stream(sessionId, assetId, null, output, (status, type, length, contentRange, acceptRanges) -> {
            assertTrue(status >= 200 && status < 300);
            assertTrue(type.contains("mpegurl"));
        });
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String assetId(String playlist) {
        Matcher matcher = ASSET_ID.matcher(playlist);
        assertTrue("playlist should contain an opaque asset id", matcher.find());
        return matcher.group(1);
    }
}

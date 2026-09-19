package cl.streambox.tv;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class MediaFlowExtractorTest {
    @Test
    public void buildsOfficialJsonProxyContractAndRedactsRangeHeader() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{"
                            + "\"mediaflow_proxy_url\":\"" + server.url("/") + "\","
                            + "\"endpoint\":\"/proxy/hls/manifest.m3u8\","
                            + "\"query_params\":{\"profile\":\"tv\"},"
                            + "\"request_headers\":{\"User-Agent\":\"VAVOO/2.6\",\"Range\":\"bytes=0-1\"}"
                            + "}"));
            URI origin = URI.create(server.url("/").toString());
            URI original = URI.create("https://vavoo.to/vavoo-iptv/BBC%20TWO");
            MediaFlowExtractor.Result result = new MediaFlowExtractor().extract(
                    origin, original, "secret"
            );
            String value = result.getPlaybackUri().toString();
            assertEquals(MediaFlowExtractor.HLS_MIME, result.getMimeType());
            assertTrue(value.contains("profile=tv"));
            assertTrue(value.contains("h_User-Agent=VAVOO%2F2.6"));
            assertFalse(value.contains("h_Range"));
            assertTrue(value.contains("api_password=secret"));
            assertTrue(value.contains("d=https%3A%2F%2Fvavoo.to"));

            RecordedRequest request = server.takeRequest();
            assertTrue(request.getPath().contains("redirect_stream=false"));
            assertTrue(request.getPath().contains("api_password=secret"));
        }
    }

    @Test
    public void acceptsTransportStreamMimeFromOfficialEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{"
                            + "\"mediaflow_proxy_url\":\"" + server.url("/") + "\","
                            + "\"endpoint\":\"/proxy/stream\","
                            + "\"content_type\":\"video/mp2t\""
                            + "}"));
            URI origin = URI.create(server.url("/").toString());
            MediaFlowExtractor.Result result = new MediaFlowExtractor().extract(
                    origin,
                    URI.create("https://vavoo.to/vavoo-iptv/BBC%20TWO"),
                    "secret"
            );
            assertEquals(MediaFlowExtractor.TS_MIME, result.getMimeType());
        }
    }

    @Test
    public void detectsShortHlsProbeWhenStreamEndpointOmitsMime() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{"
                            + "\"mediaflow_proxy_url\":\"" + server.url("/") + "\","
                            + "\"endpoint\":\"/proxy/stream\""
                            + "}"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setBody("#EXTM3U\n#EXT-X-VERSION:3\n"));
            URI origin = URI.create(server.url("/").toString());
            MediaFlowExtractor.Result result = new MediaFlowExtractor().extract(
                    origin,
                    URI.create("https://vavoo.to/vavoo-iptv/BBC%20TWO"),
                    "secret"
            );
            assertEquals(MediaFlowExtractor.HLS_MIME, result.getMimeType());
        }
    }
}

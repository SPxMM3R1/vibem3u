package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VavooProtocolTest {
    @Test public void publishedHttpFailureRetainsOriginStatusWithoutTryingTls() throws Exception {
        URI published = URI.create("http://1.1.1.1:8008/sunshine/live/index.m3u8");
        java.util.List<URI> seen = new java.util.ArrayList<>();
        try {
            TvVooStreamResolver.validateCandidate((uri, headers, strict, progress) -> {
                seen.add(uri); throw new IOException("HTTP 502");
            }, published, true, Collections.emptyMap(), true, ResolutionProgressListener.NONE);
            fail("Expected origin failure");
        } catch (IOException expected) { assertEquals("HTTP 502", expected.getMessage()); }
        assertEquals(Collections.singletonList(published), seen);
    }

    @Test public void preservesProviderHttpMediaPortWithoutTryingTls() throws Exception {
        URI published = URI.create("http://8.8.8.8:8080/live/index.m3u8");
        java.util.List<URI> seen = new java.util.ArrayList<>();
        URI accepted = TvVooStreamResolver.validateCandidate(
                (uri, headers, strict, progress) -> { seen.add(uri); assertTrue(strict); },
                published, true, TvVooStreamResolver.playbackHeaders(), true, ResolutionProgressListener.NONE);
        assertEquals(published, accepted);
        assertEquals(Collections.singletonList(published), seen);
    }

    @Test public void httpsFailureDoesNotDowngradeOrDisableCertificateChecks() throws Exception {
        java.util.List<URI> seen = new java.util.ArrayList<>();
        URI source = URI.create("https://8.8.8.8/live/index.m3u8");
        try {
            TvVooStreamResolver.validateCandidate((uri, headers, strict, progress) -> {
                seen.add(uri); throw new javax.net.ssl.SSLHandshakeException("certificate expired");
            }, source, false, Collections.emptyMap(), true, ResolutionProgressListener.NONE);
            fail("Expected TLS failure");
        } catch (javax.net.ssl.SSLHandshakeException expected) { }
        assertEquals(Collections.singletonList(source), seen);
    }

    @Test public void originalDiscoveryUsesCurrentProtocolWithoutRetiredPublicAddon() throws Exception {
        TokenHttpClient fake = new TokenHttpClient() {
            @Override public String postJsonText(String url, Map<String,String> headers,
                    String json, int limit) throws IOException {
                try {
                    JSONObject payload = new JSONObject(json);
                    assertFalse(url.contains("tvvoo.hayd.uk"));
                    if (url.endsWith("/api/app/ping")) {
                        assertTrue(url.startsWith("https://www.vypn.net/"));
                        assertTrue(headers.get("User-Agent").startsWith("electron-fetch/"));
                        return "{\"addonSig\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}";
                    }
                    assertEquals("3.1.0", payload.getString("clientVersion"));
                    assertEquals("de", payload.getString("language"));
                    assertEquals("AT", payload.getString("region"));
                    assertEquals("okhttp/4.11.0", headers.get("User-Agent"));
                    assertNotNull(headers.get("mediahubmx-signature"));
                    return "{\"items\":[{\"type\":\"iptv\",\"ids\":{\"id\":\"42\"},"
                            + "\"name\":\"TNT SPORTS 3\",\"group\":\"United Kingdom\","
                            + "\"url\":\"https://vavoo.to/vavoo-iptv/play/42\"}]}";
                } catch (org.json.JSONException error) { throw new IOException(error); }
            }
        };
        String alias = "vavoo_TNT%20SPORTS%203%7Cgroup%3Auk";
        Channel channel = new Channel("TNT SPORTS 3", URI.create("https://example.com/live"),
                null, "Deportes", Collections.singletonMap("x-resolver-id", alias));
        List<URI> originals = new VavooSessionClient(ResolverDefinition.fallbackTvVoo(), fake)
                .originalCandidates(channel, Collections.singletonList(alias),
                        ResolutionProgressListener.NONE, new ResolutionDeadline(10000));
        assertEquals(Collections.singletonList(URI.create("https://vavoo.to/vavoo-iptv/play/42")), originals);
    }

    @Test public void existingTvVooIdentitySelectsNativeResolver() {
        Channel channel = new Channel("TNT SPORTS 3", URI.create("https://example.com/live"),
                null, "Deportes", Collections.singletonMap("tvg-id", "TNTSports3.uk@TvVoo"));
        assertTrue(new StreamResolverRegistry().find(channel) instanceof TvVooStreamResolver);
    }
}

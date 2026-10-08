package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public final class NautaStreamResolverTest {
    static Channel channel(String name) {
        return new Channel(name, DynamicSourceReference.create("nauta", "cat_4|" + name), null,
                "Nauta", Map.of("tvg-id", "Nauta.dummy@Nauta", "x-resolver", "nauta"));
    }
    @Test public void exactPublicReferencePreservesRegionSlashAndSpaces() throws Exception {
        for (String name : new String[]{"ESPN 1 | Chile", "South Park 24/7", "TUDN "}) {
            Channel c = channel(name);
            assertNotNull(c.getStreamUri());
            assertEquals("cat_4|" + name, DynamicSourceReference.stableId(c.getStreamUri()));
            assertEquals(name, NautaStreamResolver.parseLocator(DynamicSourceReference.stableId(c.getStreamUri()))[1]);
            assertTrue(new NautaStreamResolver().supports(c));
        }
        assertNull(DynamicSourceReference.create("nauta", "opaque-provider-id"));
        assertNull(DynamicSourceReference.create("nauta", "cat_4|https://bad"));
    }
    @Test public void browserNotReadyIsNotDrmAndUserAgentIsCarried() throws Exception {
        JSONObject s = new JSONObject("{\"behaviorHints\":{\"notWebReady\":true,\"proxyHeaders\":{\"request\":{\"user-agent\":\"ProviderUA\",\"Host\":\"bad\"}}}}");
        assertFalse(NautaStreamResolver.hasDrm(s));
        assertEquals(Map.of("User-Agent", "ProviderUA"), NautaStreamResolver.headersOf(s));
        assertTrue(NautaStreamResolver.hasDrm(new JSONObject("{\"licenseUrl\":\"dummy\"}")));
    }
    @Test public void injectionHeadersAreRejected() throws Exception {
        JSONObject s = new JSONObject("{\"behaviorHints\":{\"proxyHeaders\":{\"request\":{\"User-Agent\":\"bad\\r\\nHost: bad\"}}}}");
        assertThrows(IOException.class, () -> NautaStreamResolver.headersOf(s));
    }
    @Test public void resolvesFreshUrlButReusesMetadataInRamAndClearsOnSessionEnd() throws Exception {
        Fake client = new Fake(); NautaStreamResolver r = new NautaStreamResolver(client);
        ResolvedPlaybackSource first = r.resolve(channel("Example | Chile"));
        ResolvedPlaybackSource second = r.resolve(channel("Example | Chile"));
        assertNotEquals(first.getPlaybackUri(), second.getPlaybackUri());
        assertEquals(1, client.catalogs);
        assertEquals(2, client.streams);
        assertEquals(2, client.segments);
        assertEquals("ProviderUA", second.getUserAgent());
        assertEquals("Nauta.dummy@Nauta", second.getStableSourceId());
        assertTrue(second.hasMimeType());
        r.clearSensitiveState(); r.resolve(channel("Example | Chile"));
        assertEquals(2, client.catalogs);
    }
    @Test public void invalidSegmentNeverReturnsFalseSuccessAndRetriesAreBounded() throws Exception {
        Fake client = new Fake(); client.invalid = true;
        assertThrows(IOException.class, () -> new NautaStreamResolver(client).resolve(channel("Example | Chile")));
        assertEquals(2, client.catalogs); assertEquals(2, client.segments);
    }
    @Test public void exactMatchingNeverOpensSimilarOrWrongRegion() throws Exception {
        Fake client = new Fake();
        assertThrows(IOException.class, () -> new NautaStreamResolver(client).resolve(channel("Example | Argentina")));
        assertEquals(0, client.streams);
    }
    @Test public void retiredMetadataIdRefreshesOnceAndDuplicateIdsRemainAlternatives() throws Exception {
        Fake client = new Fake(); client.rotated = true;
        new NautaStreamResolver(client).resolve(channel("Example | Chile"));
        assertEquals(2, client.catalogs); assertEquals(1, client.segments);
        Fake duplicate = new Fake(); duplicate.duplicate = true;
        new NautaStreamResolver(duplicate).resolve(channel("Example | Chile"));
        assertEquals(1, duplicate.catalogs); assertEquals(2, duplicate.streams);
    }
    @Test public void privateMediaIsRejectedBeforeSegmentFetch() {
        Fake client = new Fake(); client.privateMedia = true;
        assertThrows(IOException.class, () -> new NautaStreamResolver(client).resolve(channel("Example | Chile")));
        assertEquals(0, client.segments);
    }
    @Test public void knownProviderUpdateSlateIsNotAcceptedAsChannelContent() {
        Fake client = new Fake(); client.updateSlate = true;
        assertThrows(IOException.class, () -> new NautaStreamResolver(client).resolve(channel("Example | Chile")));
        assertEquals(0, client.segments);
        assertTrue(NautaStreamResolver.isKnownUpdateSlateOrigin(URI.create("http://TV.M3UTS.XYZ/dummy.m3u8")));
        assertFalse(NautaStreamResolver.isKnownUpdateSlateOrigin(URI.create("http://m3u.tvcluboficial.com/dummy.m3u8")));
    }
    @Test public void cancellationDoesNotRetryOrLeakOpaqueId() throws Exception {
        Fake client = new Fake(); ResolutionContext context = new ResolutionContext(1000); context.cancel();
        try (ResolutionContext.Scope scope = context.activate()) {
            IOException e = assertThrows(IOException.class, () -> new NautaStreamResolver(client).resolve(channel("Example | Chile")));
            assertNull(e.getCause()); assertFalse(e.toString().contains("dummy-id"));
        }
        assertEquals(0, client.catalogs);
    }
    @Test public void diskCacheNeverRetainsAnAccountStyleNautaPath() {
        String text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"Nauta.dummy@Nauta\" x-resolver=\"nauta\",Example\nhttp://8.8.8.8/stream/dummy-account/dummy-password/a.m3u8\n";
        assertFalse(M3uCacheSanitizer.forDisk(text).contains("dummy-password"));
        assertFalse(M3uCacheSanitizer.forDisk(text).contains("http://8.8.8.8"));
    }
    @Test public void diagnosticsHideAccountPathsAndOpaqueProviderResources() {
        for (String url : new String[]{"http://tv.m3uts.xyz/stream/dummy/dummy-password/1.m3u8",
                "http://m3u.tvcluboficial.com/dummy/dummy-password/1.m3u8",
                "https://stremio-addon-wheat.vercel.app/stream/tv/dummy-id.json"}) {
            assertFalse(SafePlaybackText.url(url).contains("dummy"));
        }
    }
    private static final class Fake extends TokenHttpClient {
        int catalogs, streams, segments; boolean invalid, rotated, duplicate, privateMedia, updateSlate;
        @Override public Response getPublicOnHosts(String url, Map<String,String> headers, int max, String range, Set<String> hosts) {
            assertEquals(Collections.singleton("stremio-addon-wheat.vercel.app"), hosts);
            if (url.contains("/catalog/")) {
                catalogs++;
                if (rotated && catalogs == 1) return response(url, "{\"metas\":[]}");
                if (duplicate) return response(url, "{\"metas\":[{\"id\":\"dummy-dead\",\"name\":\"Example | Chile\"},{\"id\":\"dummy-id\",\"name\":\"Example | Chile\"}]}");
                return response(url, "{\"metas\":[{\"id\":\"dummy-id\",\"name\":\"Example | Chile\"}]}");
            }
            streams++;
            if (url.contains("dummy-dead")) return response(url, "{\"streams\":[]}");
            return response(url, "{\"streams\":[{\"url\":\"https://"+(privateMedia ? "127.0.0.1" : updateSlate ? "tv.m3uts.xyz" : "8.8.8.8")+"/test/"+streams+".m3u8\",\"behaviorHints\":{\"notWebReady\":true,\"proxyHeaders\":{\"request\":{\"User-Agent\":\"ProviderUA\"}}}}]}");
        }
        @Override public Response getPublic(String url, Map<String,String> headers, int max, String range) {
            assertEquals("ProviderUA", headers.get("User-Agent"));
            return response(url, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n");
        }
        @Override public Response getPublicPrefix(String url, Map<String,String> headers, int max, String range) {
            segments++; byte[] media = new byte[512];
            if (!invalid) { media[0]=0x47; media[188]=0x47; }
            return new Response(206, URI.create(url), "video/mp2t", Collections.emptyMap(), media);
        }
        private Response response(String url, String body) {
            return new Response(200, URI.create(url), "application/json", Collections.emptyMap(), body.getBytes(StandardCharsets.UTF_8));
        }
    }
}

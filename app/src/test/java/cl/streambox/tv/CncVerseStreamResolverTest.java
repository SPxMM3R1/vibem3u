package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public final class CncVerseStreamResolverTest {
    private static final String REF = "sportsworld|TNT Sports UK|TNT Sports 1";
    private static final String PLAYBACK = "https://cncverse.dpdns.org/proxy/mpd/manifest.m3u8?d=example&clearkey=test-value";

    private Channel channel() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tvg-id", "TNTSports1.uk@CNCVerse");
        attrs.put("x-resolver", "cncverse");
        attrs.put("x-resolver-id", REF);
        return new Channel("Nombre personalizado", DynamicSourceReference.create("cncverse", REF),
                null, "Deportes", attrs);
    }
    private static JSONObject object(String value) { return new JSONObject(value); }
    private static String payload(String label, String uri) {
        return new JSONObject().put("streams", new org.json.JSONArray().put(new JSONObject()
                .put("title", "🍿 TNT Sports UK\n🏷️ " + label + "\n🎞️ Auto • DASH")
                .put("url", uri))).toString();
    }
    private static class FakeClient extends TokenHttpClient {
        int lookups;
        final List<String> requests = new ArrayList<>();
        String streams = payload("TNT Sports 1", PLAYBACK);
        String catalog;
        @Override public Response getPublicOnHosts(String url, Map<String, String> headers,
                int maxBytes, String range, Set<String> hosts) {
            requests.add(url);
            assertEquals(Collections.singleton("cncverse.dpdns.org"), hosts);
            assertEquals(1024 * 1024, maxBytes);
            String body = url.contains("/catalog/")
                    ? (catalog == null ? "{\"metas\":[{\"name\":\"TNT Sports UK\",\"id\":\"fresh-" + ++lookups + "\"}]}" : catalog)
                    : streams;
            return new Response(200, URI.create(url), "application/json", Collections.emptyMap(),
                    body.getBytes(StandardCharsets.UTF_8));
        }
    }
    private CncVerseStreamResolver resolver(FakeClient client) {
        return new CncVerseStreamResolver(null, client, (uri, headers, context, progress) -> {
            context.check();
            assertEquals(URI.create(PLAYBACK), uri);
        });
    }

    @Test public void resolvesExactSignalKeepsMasterAndNeverUsesVisibleName() throws Exception {
        FakeClient client = new FakeClient();
        ResolvedPlaybackSource source = resolver(client).resolve(channel());
        assertEquals(PLAYBACK, source.getPlaybackUri().toString());
        assertEquals("TNTSports1.uk@CNCVerse", source.getStableSourceId());
        assertEquals("TNT Sports 1", source.getVariantId());
        assertEquals("application/x-mpegURL", source.getMimeType());
        assertTrue(source.getExpiresAtMillis() > System.currentTimeMillis());
        assertTrue(client.requests.get(1).contains("fresh-1.json"));
    }
    @Test public void discoversFreshOpaqueResourceOnEveryResolution() throws Exception {
        FakeClient client = new FakeClient();
        CncVerseStreamResolver resolver = resolver(client);
        resolver.resolve(channel()); resolver.resolve(channel());
        assertTrue(client.requests.get(1).endsWith("fresh-1.json"));
        assertTrue(client.requests.get(3).endsWith("fresh-2.json"));
        assertEquals(120000L, resolver.cacheTtlMillis());
        assertFalse(resolver.keepSessionSourceOnPlaybackPause());
    }
    @Test public void neverSelectsNeighbouringChannel() {
        FakeClient client = new FakeClient(); client.streams = payload("TNT Sports 2", PLAYBACK);
        assertThrows(IOException.class, () -> resolver(client).resolve(channel()));
    }
    @Test public void refusesAmbiguousGroupInsteadOfSelectingFirst() {
        FakeClient client = new FakeClient();
        client.catalog = "{\"metas\":[{\"name\":\"TNT Sports UK\",\"id\":\"one\"},"
                + "{\"name\":\"TNT Sports UK\",\"id\":\"two\"}]}";
        assertThrows(IOException.class, () -> resolver(client).resolve(channel()));
        assertEquals(1, client.requests.size());
    }
    @Test public void rejectsMissingGroup() {
        assertThrows(IOException.class, () -> CncVerseStreamResolver.uniqueResource(object("{\"metas\":[]}"), "TNT"));
    }
    @Test public void rejectsRawDashExternalPlayersAndForeignHosts() {
        for (String url : Arrays.asList("https://evil.invalid/proxy/a.m3u8",
                "https://cncverse.dpdns.org/proxy/a.mpd", "https://cncverse.dpdns.org@evil.invalid/proxy/a.m3u8",
                "https://cncverse.dpdns.org/proxy/a.m3u8#token", "file:///a.m3u8")) {
            assertNull(CncVerseStreamResolver.playbackUri(url));
        }
    }
    @Test public void referencesRejectSecretsUrlsControlsAndEmptyLabels() {
        for (String ref : Arrays.asList("opaque", "sportsworld|TNT|", "sportsworld|TNT|token=x",
                "sportsworld|TNT|https://a", "sportsworld| TNT|one", "sportsworld|TNT|a\nb")) {
            assertThrows(IOException.class, () -> CncVerseStreamResolver.referenceParts(ref));
        }
    }
    @Test public void bridgeLiteralJsonQueryIsEncodedOnlyInMemory() {
        String raw = "https://cncverse.dpdns.org/proxy/mpd/manifest.m3u8?d=example&clearkey={\"dummy\":\"fake\"}";
        URI uri = CncVerseStreamResolver.playbackUri(raw);
        assertNotNull(uri);
        assertTrue(uri.getRawQuery().contains("%7B%22dummy%22"));
        assertTrue(uri.getQuery().contains("{\"dummy\":\"fake\"}"));
        assertFalse(SafePlaybackText.url(uri).contains("dummy"));
    }
    @Test public void bridgeHttpAdvertisementIsUpgradedBeforeAnyRequest() {
        URI uri = CncVerseStreamResolver.playbackUri("http://cncverse.dpdns.org/proxy/a.m3u8?clearkey=fake");
        assertNotNull(uri);
        assertEquals("https", uri.getScheme());
        assertNull(CncVerseStreamResolver.playbackUri("http://foreign.invalid/proxy/a.m3u8"));
    }
    @Test public void manifestMustBeFixedHostHttpsAndTokenless() throws Exception {
        assertEquals("https://cncverse.dpdns.org/u/p_0md89st1e5th",
                CncVerseStreamResolver.addonBase(CncVerseStreamResolver.DEFAULT_MANIFEST_URL));
        for (String uri : Arrays.asList("http://cncverse.dpdns.org/manifest.json", "https://evil.invalid/manifest.json",
                "https://cncverse.dpdns.org/manifest.json?token=test", "https://cncverse.dpdns.org/../manifest.json")) {
            assertThrows(IOException.class, () -> CncVerseStreamResolver.addonBase(uri));
        }
    }
    @Test public void selectorKeepsOnlyTheChosenSignal() throws Exception {
        FakeClient client = new FakeClient();
        List<ResolvedPlaybackCandidate> result = resolver(client).resolvePlaybackCandidates(channel(), null);
        assertEquals(1, result.size()); assertEquals("TNT Sports 1", result.get(0).getLabel());
        assertFalse(result.get(0).getDetail().contains("clearkey"));
    }
    @Test public void invalidJsonHasNoResponseOrOpaqueIdInError() {
        FakeClient client = new FakeClient(); client.streams = "private-provider-response";
        IOException error = assertThrows(IOException.class, () -> resolver(client).resolve(channel()));
        assertFalse(error.toString().contains("private-provider-response"));
        assertNull(error.getCause());
    }
    @Test public void headersAreCanonicalLimitedAndExcludeCredentialFields() throws Exception {
        FakeClient client = new FakeClient();
        JSONObject stream = new JSONObject(client.streams).getJSONArray("streams").getJSONObject(0);
        stream.put("behaviorHints", new JSONObject().put("proxyHeaders", new JSONObject().put("request",
                new JSONObject().put("user-agent", "Example UA").put("Authorization", "dummy")
                        .put("referer", "bad\r\nheader").put("accept", "*/*"))));
        client.streams = new JSONObject().put("streams", new org.json.JSONArray().put(stream)).toString();
        ResolvedPlaybackSource source = resolver(client).resolve(channel());
        assertEquals("Example UA", source.getUserAgent());
        assertEquals("Example UA", source.getRequestHeaders().get("User-Agent"));
        assertFalse(source.getRequestHeaders().containsKey("Authorization"));
        assertFalse(source.getRequestHeaders().containsKey("Referer"));
    }
    @Test public void bridgeSecretsAreRedactedInDiagnostics() {
        String safe = SafePlaybackText.url(PLAYBACK);
        assertFalse(safe.contains("test-value")); assertFalse(safe.contains("d=example"));
        assertFalse(SafePlaybackText.detail("clearkey=test-value").contains("test-value"));
    }
    @Test public void cancellationPreventsAnyRequest() {
        FakeClient client = new FakeClient();
        ResolutionContext parent = new ResolutionContext(1000); parent.cancel();
        try (ResolutionContext.Scope ignored = parent.activate()) {
            assertThrows(IOException.class, () -> resolver(client).resolve(channel()));
        }
        // The production HTTP client checks cancellation before connecting; fake clients also
        // must not be reached when the context was already cancelled.
        assertEquals(0, client.requests.size());
    }
    @Test public void registryAndPlaylistUseOnlyTokenlessReference() throws Exception {
        Channel channel = channel();
        assertTrue(DynamicSourceReference.isAppOnly(channel.getStreamUri()));
        String list = "#EXTM3U\n#EXTINF:-1 tvg-id=\"TNTSports1.uk@CNCVerse\" x-resolver=\"cncverse\""
                + " x-resolver-id=\"" + REF + "\",TNT Sports 1\n" + PLAYBACK + "\n";
        String disk = M3uCacheSanitizer.forDisk(list);
        assertFalse(disk.contains("test-value")); assertFalse(disk.contains("/proxy/"));
        assertTrue(disk.contains("vibem3u://resolver/cncverse/"));
    }
}

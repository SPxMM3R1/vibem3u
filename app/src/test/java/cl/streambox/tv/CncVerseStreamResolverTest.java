package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public final class CncVerseStreamResolverTest {
    private static final String REF = "sportsworld|TNT Sports UK|TNT Sports 1";
    private static final String PLAYBACK = "https://cncverse.dpdns.org/proxy/mpd/manifest.m3u8?d=example&clearkey=test-value";

    private static List<InetAddress> addresses(String... values) throws Exception {
        List<InetAddress> result = new ArrayList<>();
        for (String value : values) result.add(InetAddress.getByName(value));
        return result;
    }

    @Test public void bridgeIpAliasesFollowCurrentDnsWithoutPinningAnAddress() throws Exception {
        for (String ip : Arrays.asList("1.1.1.1", "8.8.8.8")) {
            String raw = PLAYBACK.replace("https://cncverse.dpdns.org", "http://" + ip);
            assertEquals(URI.create(PLAYBACK), CncVerseStreamResolver.playbackUri(raw, addresses(ip)));
            assertNull(CncVerseStreamResolver.playbackUri(raw, addresses("9.9.9.9")));
        }
    }
    @Test public void bridgeIpv6AndMultipleDnsAddressesNormalizeToTlsDomain() throws Exception {
        assertEquals(URI.create(PLAYBACK), CncVerseStreamResolver.playbackUri(
                PLAYBACK.replace("cncverse.dpdns.org", "[2606:4700:4700::1111]"),
                addresses("8.8.8.8", "2606:4700:4700::1111")));
    }
    @Test public void aliasesRejectPrivateMixedDnsAndUnrelatedDomains() throws Exception {
        for (String ip : Arrays.asList("127.0.0.1", "10.0.0.1", "169.254.169.254", "::1")) {
            String host = ip.contains(":") ? "[" + ip + "]" : ip;
            assertNull(CncVerseStreamResolver.playbackUri(PLAYBACK.replace("cncverse.dpdns.org", host), addresses(ip)));
        }
        assertNull(CncVerseStreamResolver.playbackUri(PLAYBACK.replace("cncverse.dpdns.org", "1.1.1.1"),
                addresses("1.1.1.1", "127.0.0.1")));
        assertNull(CncVerseStreamResolver.playbackUri(PLAYBACK.replace("cncverse.dpdns.org", "evil.invalid"),
                addresses("1.1.1.1")));
    }
    @Test public void aliasesRequireProxyHlsNoCredentialsFragmentOrUnexpectedPort() throws Exception {
        List<InetAddress> current = addresses("1.1.1.1");
        for (String url : Arrays.asList("http://1.1.1.1:8000/proxy/a.m3u8", "https://1.1.1.1:8443/proxy/a.m3u8",
                "http://user:pass@1.1.1.1/proxy/a.m3u8", "http://1.1.1.1/proxy/a.m3u8#fragment",
                "http://1.1.1.1/a.m3u8", "http://1.1.1.1/proxy/a.mpd", "file:///proxy/a.m3u8")) {
            assertNull(CncVerseStreamResolver.playbackUri(url, current));
        }
    }
    @Test public void defaultPortsAndEscapedQueryArePreservedWithoutCleartext() throws Exception {
        URI result = CncVerseStreamResolver.playbackUri(
                "http://1.1.1.1:80/proxy/hls/manifest.m3u8?d=a%2Fb&clearkey={\"fake\":\"dummy\"}", addresses("1.1.1.1"));
        assertNotNull(result);
        assertEquals("https", result.getScheme());
        assertEquals("cncverse.dpdns.org", result.getHost());
        assertEquals(-1, result.getPort());
        assertTrue(result.getRawQuery().startsWith("d=a%2Fb&clearkey="));
        assertTrue(result.getQuery().contains("{\"fake\":\"dummy\"}"));
        assertEquals(URI.create(PLAYBACK), CncVerseStreamResolver.playbackUri(
                PLAYBACK.replace("cncverse.dpdns.org", "cncverse.dpdns.org:443")));
    }
    @Test public void aliasResolutionUsesFreshDnsOncePerAttemptAndKeepsExactIdentity() throws Exception {
        FakeClient client = new FakeClient();
        List<String> dnsCalls = new ArrayList<>();
        List<URI> validated = new ArrayList<>();
        CncVerseStreamResolver resolver = new CncVerseStreamResolver(null, client,
                (uri, h, ctx, p) -> validated.add(uri), host -> {
                    dnsCalls.add(host);
                    return Collections.singletonList(InetAddress.getByName(dnsCalls.size() == 1 ? "1.1.1.1" : "8.8.8.8"));
                });
        for (String ip : Arrays.asList("1.1.1.1", "8.8.8.8")) {
            client.streams = payload("TNT Sports 1", PLAYBACK.replace("cncverse.dpdns.org", ip));
            ResolvedPlaybackSource source = resolver.resolve(channel());
            assertEquals(PLAYBACK, source.getPlaybackUri().toString());
            assertEquals("TNTSports1.uk@CNCVerse", source.getStableSourceId());
        }
        assertEquals(Arrays.asList("cncverse.dpdns.org", "cncverse.dpdns.org"), dnsCalls);
        assertEquals(2, validated.size());
    }
    @Test public void canonicalDomainNeedsNoAliasLookup() throws Exception {
        FakeClient client = new FakeClient();
        new CncVerseStreamResolver(null, client, (u, h, c, p) -> {}, host -> {
            throw new AssertionError("Alias DNS must not run for canonical URLs");
        }).resolve(channel());
    }
    @Test public void foreignAliasNeverReachesValidation() throws Exception {
        FakeClient client = new FakeClient();
        client.streams = payload("TNT Sports 1", PLAYBACK.replace("cncverse.dpdns.org", "8.8.8.8"));
        int[] validations = {0};
        assertThrows(IOException.class, () -> new CncVerseStreamResolver(null, client,
                (u, h, c, p) -> validations[0]++, host -> Collections.singletonList(InetAddress.getByName("1.1.1.1")))
                .resolve(channel()));
        assertEquals(0, validations[0]);
    }
    @Test public void dnsFailureCanStillUseSameSignalCanonicalAlternative() throws Exception {
        FakeClient client = new FakeClient();
        org.json.JSONArray rows = new JSONObject(payload("TNT Sports 1", PLAYBACK.replace("cncverse.dpdns.org", "1.1.1.1")))
                .getJSONArray("streams");
        rows.put(new JSONObject(payload("TNT Sports 1", PLAYBACK)).getJSONArray("streams").getJSONObject(0));
        client.streams = new JSONObject().put("streams", rows).toString();
        ResolvedPlaybackSource source = new CncVerseStreamResolver(null, client, (u, h, c, p) -> {},
                host -> { throw new java.net.UnknownHostException("private-DNS-detail"); }).resolve(channel());
        assertEquals(PLAYBACK, source.getPlaybackUri().toString());
    }
    @Test public void cancellationDuringDnsStopsWithoutValidationOrLeakingDetails() throws Exception {
        FakeClient client = new FakeClient();
        client.streams = payload("TNT Sports 1", PLAYBACK.replace("cncverse.dpdns.org", "1.1.1.1"));
        ResolutionContext parent = new ResolutionContext(1000);
        int[] validations = {0};
        try (ResolutionContext.Scope ignored = parent.activate()) {
            IOException error = assertThrows(IOException.class, () -> new CncVerseStreamResolver(null, client,
                    (u, h, c, p) -> validations[0]++, host -> {
                        parent.cancel();
                        return Collections.singletonList(InetAddress.getByName("1.1.1.1"));
                    }).resolve(channel()));
            assertFalse(error.toString().contains("clearkey"));
        }
        assertEquals(0, validations[0]);
    }
    @Test public void stalledDnsCannotResetParentDeadline() throws Exception {
        FakeClient client = new FakeClient();
        client.streams = payload("TNT Sports 1", PLAYBACK.replace("cncverse.dpdns.org", "1.1.1.1"));
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ResolutionContext parent = new ResolutionContext(150);
        long started = System.nanoTime();
        try (ResolutionContext.Scope ignored = parent.activate()) {
            assertThrows(IOException.class, () -> new CncVerseStreamResolver(null, client, (u, h, c, p) -> {
                throw new AssertionError("Expired DNS cannot validate");
            }, host -> {
                try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                return Collections.singletonList(InetAddress.getByName("1.1.1.1"));
            }).resolve(channel()));
        } finally { release.countDown(); }
        assertTrue("DNS must stay inside the parent budget", (System.nanoTime() - started) / 1_000_000L < 1000);
    }
    @Test public void normalizationDoesNotSkipFailedHlsValidation() throws Exception {
        FakeClient client = new FakeClient();
        client.streams = payload("TNT Sports 1", PLAYBACK.replace("cncverse.dpdns.org", "1.1.1.1"));
        IOException error = assertThrows(IOException.class, () -> new CncVerseStreamResolver(null, client,
                (u, h, c, p) -> { throw new IOException("secret-source-url"); },
                host -> Collections.singletonList(InetAddress.getByName("1.1.1.1"))).resolve(channel()));
        assertFalse(error.toString().contains("secret-source-url"));
        assertNull(error.getCause());
    }
    @Test public void chileProxyUsesSameVerifiedAliasPolicyAsSports() throws Exception {
        for (boolean trusted : Arrays.asList(true, false)) {
            FakeClient client = chileClient();
            client.streams = payload("13C (1080p)", PLAYBACK.replace("cncverse.dpdns.org", "1.1.1.1"));
            int[] checked = {0};
            CncVerseStreamResolver resolver = new CncVerseStreamResolver(null, client, (u, h, c, p) -> {
                checked[0]++; assertEquals("cncverse.dpdns.org", u.getHost());
            }, host -> Collections.singletonList(InetAddress.getByName(trusted ? "1.1.1.1" : "8.8.8.8")));
            if (trusted) assertEquals(PLAYBACK, resolver.resolve(chileChannel()).getPlaybackUri().toString());
            else assertThrows(IOException.class, () -> resolver.resolve(chileChannel()));
            assertEquals(trusted ? 1 : 0, checked[0]);
        }
    }

    private Channel channel() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tvg-id", "TNTSports1.uk@CNCVerse");
        attrs.put("x-resolver", "cncverse");
        attrs.put("x-resolver-id", REF);
        return new Channel("Nombre personalizado", DynamicSourceReference.create("cncverse", REF),
                null, "Deportes", attrs);
    }
    private static JSONObject object(String value) {
        try { return new JSONObject(value); }
        catch (org.json.JSONException invalid) { throw new AssertionError("Invalid test fixture", invalid); }
    }
    private static String payload(String label, String uri) {
        try { return new JSONObject().put("streams", new org.json.JSONArray().put(new JSONObject()
                .put("title", "🍿 TNT Sports UK\n🏷️ " + label + "\n🎞️ Auto • DASH")
                .put("url", uri))).toString(); }
        catch (org.json.JSONException invalid) { throw new AssertionError("Invalid test fixture", invalid); }
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

    private Channel chileChannel() {
        String ref = "chiletv|13C (1080p)|auto";
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tvg-id", "Chile13C@CNCVerse"); attrs.put("x-resolver", "cncverse");
        attrs.put("x-resolver-id", ref);
        return new Channel("Personalizado", DynamicSourceReference.create("cncverse", ref), null, "Chile", attrs);
    }
    private FakeClient chileClient() {
        FakeClient client = new FakeClient();
        client.catalog = "{\"metas\":[{\"name\":\"13C (1080p)\",\"id\":\"fresh-chile\"}]}";
        client.streams = payload("13C (1080p)", "https://1.1.1.1/master.m3u8");
        return client;
    }
    @Test public void chileUsesExactMetadataOwnCatalogAndPublicMaster() throws Exception {
        FakeClient client = chileClient();
        List<URI> checked = new ArrayList<>();
        ResolvedPlaybackSource source = new CncVerseStreamResolver(null, client,
                (uri, headers, context, progress) -> checked.add(uri)).resolve(chileChannel());
        assertTrue(client.requests.get(0).endsWith("/catalog/tv/cnc_CHILETV_tv.json"));
        assertEquals(Collections.singletonList(URI.create("https://1.1.1.1/master.m3u8")), checked);
        assertEquals("Chile13C@CNCVerse", source.getStableSourceId());
        assertEquals("13C (1080p)", source.getVariantId());
        assertEquals("application/x-mpegURL", source.getMimeType());
    }
    @Test public void chilePrivateTargetsAreRejectedBeforeValidation() {
        for (String uri : Arrays.asList("http://127.0.0.1/a.m3u8", "http://10.0.0.1/a.m3u8",
                "http://169.254.169.254/a.m3u8", "http://localhost/a.m3u8", "http://[::1]/a.m3u8")) {
            FakeClient client = chileClient(); client.streams = payload("13C", uri);
            int[] attempts = {0};
            assertThrows(IOException.class, () -> new CncVerseStreamResolver(null, client,
                    (url, headers, context, progress) -> attempts[0]++).resolve(chileChannel()));
            assertEquals(0, attempts[0]);
        }
    }
    @Test public void chileModeNeverFallsBackToNeighbouringMetadata() {
        FakeClient client = chileClient();
        client.catalog = "{\"metas\":[{\"name\":\"13C\",\"id\":\"other\"}]}";
        assertThrows(IOException.class, () -> resolver(client).resolve(chileChannel()));
        assertEquals(1, client.requests.size());
        FakeClient ambiguous = chileClient();
        ambiguous.catalog = "{\"metas\":[{\"name\":\"13C (1080p)\",\"id\":\"a\"},"
                + "{\"name\":\"13C (1080p)\",\"id\":\"b\"}]}";
        assertThrows(IOException.class, () -> resolver(ambiguous).resolve(chileChannel()));
    }
    @Test public void chileFallsBackOnlyAmongItsOwnValidatedHlsSources() throws Exception {
        FakeClient client = chileClient();
        org.json.JSONArray streams = new org.json.JSONArray();
        streams.put(new JSONObject().put("url", "https://1.1.1.1/dead.m3u8"));
        streams.put(new JSONObject().put("url", "https://1.1.1.1/good.m3u8"));
        client.streams = new JSONObject().put("streams", streams).toString();
        List<URI> attempts = new ArrayList<>();
        ResolvedPlaybackSource source = new CncVerseStreamResolver(null, client, (uri, headers, context, progress) -> {
            attempts.add(uri); if (uri.getPath().contains("dead")) throw new IOException("private-detail");
        }).resolve(chileChannel());
        assertEquals(2, attempts.size()); assertEquals("/good.m3u8", source.getPlaybackUri().getPath());
    }
    @Test public void chileRejectsRawDrmAndNonHls() throws Exception {
        FakeClient client = chileClient();
        JSONObject stream = new JSONObject().put("url", "https://1.1.1.1/a.m3u8")
                .put("behaviorHints", new JSONObject().put("drmConfiguration", "dummy"));
        client.streams = new JSONObject().put("streams", new org.json.JSONArray().put(stream)).toString();
        int[] attempts = {0};
        assertThrows(IOException.class, () -> new CncVerseStreamResolver(null, client,
                (url, headers, context, progress) -> attempts[0]++).resolve(chileChannel()));
        assertEquals(0, attempts[0]);
        for (String uri : Arrays.asList("file:///a.m3u8", "https://1.1.1.1/a.mpd", "https://1.1.1.1/a.m3u8#x",
                "https://user:password@1.1.1.1/a.m3u8", "http://1.1.1.1/a.m3u8?token=fake")) {
            assertNull(CncVerseStreamResolver.chilePlaybackUri(uri));
        }
        assertEquals("http", CncVerseStreamResolver.chilePlaybackUri("http://1.1.1.1/a.m3u8").getScheme());
    }
    @Test public void chileReferenceModeIsClosedAndNot247AnnotationIsLiteral() throws Exception {
        assertEquals("chiletv", CncVerseStreamResolver.referenceParts("chiletv|TV [Not 24/7]|auto")[0]);
        for (String ref : Arrays.asList("chiletv|TV|custom", "chiletv|http://local|auto",
                "unknown|TV|auto", "sportsworld|TV [Not 24/7]|auto", "chiletv|TV?secret=x|auto")) {
            assertThrows(IOException.class, () -> CncVerseStreamResolver.referenceParts(ref));
        }
    }
    @Test public void not247ChannelSurvivesRealPlaylistParserAndDiskCache() throws Exception {
        String ref = "chiletv|Holvoet TV (720p) [Not 24/7]|auto";
        URI uri = DynamicSourceReference.create("cncverse", ref);
        assertNotNull(uri); assertTrue(DynamicSourceReference.isAppOnly(uri));
        String list = "#EXTM3U\n#EXTINF:-1 tvg-id=\"Holvoet@CNCVerse\" x-resolver=\"cncverse\""
                + " x-resolver-id=\"" + ref + "\",Holvoet\n" + uri + "\n";
        List<Channel> parsed = M3uParser.parse(M3uCacheSanitizer.forDisk(list), URI.create("https://example.org/list.m3u"));
        assertEquals(1, parsed.size());
        assertEquals(ref, DynamicSourceReference.stableId(parsed.get(0).getStreamUri()));
        assertNull(DynamicSourceReference.create("tvn", ref));
        assertNull(DynamicSourceReference.create("cncverse", "chiletv|a/b|auto"));
    }
}

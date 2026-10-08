package cl.streambox.tv;

import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Fake transport: no network, credentials or live provider payloads. */
public final class MeganoticiasStreamResolverTest {
    private static final URI MASTER = URI.create("https://8.8.8.8/live/dummy-mega.m3u8?access_token=dummy-token-1");
    private static Channel channel() {
        return new Channel("Meganoticias", URI.create("vibem3u://resolver/meganoticias/Meganoticias.cl"),
                null, "Noticias", Collections.singletonMap("tvg-id", "Meganoticias.cl"));
    }
    private static MeganoticiasStreamResolver resolver(FakeClient client) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("playlistTemplate", "https://8.8.8.8/live/{streamId}.m3u8");
        ResolverDefinition definition = new ResolverDefinition("meganoticias", "Meganoticias", "meganoticias",
                true, 0, Collections.singleton("Meganoticias.cl"), Collections.emptyList(),
                Collections.emptySet(), config, Collections.emptyMap());
        return new MeganoticiasStreamResolver(definition, client);
    }
    @Test public void requiresVideoBeforeAcceptingAndHandsOffExactPlaylists() throws Exception {
        FakeClient client = new FakeClient();
        // This test checks HLS authorization/handoff, not cold JVM TLS initialization.
        SharedHttpClient.get();
        ResolutionContext context = new ResolutionContext(1000);
        List<ResolutionStage> stages = new ArrayList<>();
        try (ResolutionContext.Scope ignored = context.activate()) {
            ResolvedPlaybackSource source = resolver(client).resolve(channel(), p -> stages.add(p.getStage()));
            assertEquals(MASTER, source.getPlaybackUri());
            assertEquals(TokenHttpClient.BROWSER_USER_AGENT, source.getUserAgent());
        }
        assertEquals(1, client.tokens);
        assertEquals(1, client.segments);
        assertNotNull(context.manifests().peek(MASTER));
        assertNotNull(context.manifests().peek(URI.create("https://8.8.8.8/live/child.m3u8?session=dummy-1")));
        assertTrue(stages.indexOf(ResolutionStage.HLS_SEGMENT) < stages.indexOf(ResolutionStage.SOURCE_FOUND));
        assertEquals("https://www.meganoticias.cl", client.mediaHeaders.get("Origin"));
        assertTrue(client.mediaHeaders.get("Referer").endsWith("/meganoticias/"));
        assertEquals("bytes=0-32767", client.range);
    }
    @Test public void master200Segment401RenewsOnceBeforeAccepting() throws Exception {
        FakeClient client = new FakeClient(); client.rejectedStatus = 401;
        client.failFirstSession = true;
        ResolutionContext context = new ResolutionContext(1000);
        try (ResolutionContext.Scope ignored = context.activate()) {
            ResolvedPlaybackSource source = resolver(client).resolve(channel());
            assertTrue(source.getPlaybackUri().getQuery().contains("dummy-token-2"));
        }
        assertEquals(2, client.tokens);
        assertEquals(2, client.segments);
        assertNull(context.manifests().peek(MASTER));
    }
    @Test public void segment403AlsoRenewsOnce() throws Exception {
        FakeClient client = new FakeClient(); client.rejectedStatus = 403; client.failFirstSession = true;
        resolver(client).resolve(channel());
        assertEquals(2, client.tokens);
    }
    @Test public void persistent401FailsWithoutCachingOrUnboundedRetries() throws Exception {
        FakeClient client = new FakeClient(); client.rejectedStatus = 401; client.failAllSessions = true;
        ResolutionContext context = new ResolutionContext(1000);
        try (ResolutionContext.Scope ignored = context.activate()) {
            expectFailure(client);
        }
        assertEquals(2, client.tokens);
        assertEquals(2, client.segments);
        assertNull(context.manifests().peek(MASTER));
        assertNull(context.manifests().peek(URI.create("https://8.8.8.8/live/dummy-mega.m3u8?access_token=dummy-token-2")));
    }
    @Test public void missingSegmentDoesNotRenewAuthorization() throws Exception {
        FakeClient client = new FakeClient(); client.rejectedStatus = 404; client.failAllSessions = true;
        expectFailure(client);
        assertEquals(1, client.tokens);
    }
    @Test public void invalidMediaDoesNotReturnAFalsePositive() throws Exception {
        FakeClient client = new FakeClient(); client.invalidMedia = true;
        expectFailure(client);
        assertEquals(1, client.tokens);
    }
    @Test public void cancellationDuring401DoesNotStartAnotherSession() throws Exception {
        FakeClient client = new FakeClient(); client.rejectedStatus = 401; client.failAllSessions = true;
        ResolutionContext parent = new ResolutionContext(1000); client.cancelOnSegment = parent;
        try (ResolutionContext.Scope ignored = parent.activate()) { expectFailure(client); }
        assertEquals(1, client.tokens);
    }
    @Test public void expiredParentBudgetNeverResetsForRetry() throws Exception {
        FakeClient client = new FakeClient(); client.rejectedStatus = 401; client.failAllSessions = true;
        client.segmentDelay = 80;
        try (ResolutionContext.Scope ignored = new ResolutionContext(30).activate()) { expectFailure(client); }
        assertTrue(client.tokens <= 1);
    }
    @Test public void providerExpiryAndSessionCachePolicyArePreserved() throws Exception {
        FakeClient client = new FakeClient();
        long expiry = System.currentTimeMillis() + 60000; client.expiry = expiry;
        MeganoticiasStreamResolver resolver = resolver(client);
        ResolvedPlaybackSource source = resolver.resolve(channel());
        assertTrue(source.getExpiresAtMillis() <= expiry);
        assertTrue(source.getExpiresAtMillis() > System.currentTimeMillis());
        assertTrue(resolver.keepSessionSourceOnPlaybackPause());
        assertEquals(Long.MAX_VALUE, resolver.cacheTtlMillis());
    }
    private static void expectFailure(FakeClient client) throws Exception {
        try { resolver(client).resolve(channel()); fail("Must reject unauthorized/non-video media"); }
        catch (IOException expected) { /* provider failure, not acceptance */ }
    }
    private static final class FakeClient extends TokenHttpClient {
        int tokens, segments, rejectedStatus; long expiry, segmentDelay;
        boolean failFirstSession, failAllSessions, invalidMedia;
        ResolutionContext cancelOnSegment;
        Map<String, String> mediaHeaders; String range;
        @Override public String getText(String url, Map<String, String> headers) throws IOException {
            if (url.contains("/api/v1/mdstrm")) {
                assertTrue(url.contains("ua=" + java.net.URLEncoder.encode(TokenHttpClient.BROWSER_USER_AGENT, StandardCharsets.UTF_8)));
                tokens++;
                return "{\"access_token\":\"dummy-token-" + tokens + "\",\"expires_at\":" + expiry + "}";
            }
            return "var VideoSenalEnVivo={id:'dummy-mega',serverKey:'dummy-server-key'};";
        }
        @Override public Response getPublic(String url, Map<String, String> headers, int maximum, String range) {
            String content = url.contains("/child.m3u8")
                    ? "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts?session=dummy-" + tokens + "\n"
                    : "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\nchild.m3u8?session=dummy-" + tokens + "\n";
            return new Response(200, URI.create(url), "application/vnd.apple.mpegurl", Collections.emptyMap(), content.getBytes(StandardCharsets.UTF_8));
        }
        @Override public Response getPublicPrefix(String url, Map<String, String> headers, int maximum, String range) throws IOException {
            segments++; mediaHeaders = new LinkedHashMap<>(headers); this.range = range;
            if (cancelOnSegment != null) cancelOnSegment.cancel();
            if (segmentDelay > 0) {
                try { Thread.sleep(segmentDelay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled", e); }
            }
            if (failAllSessions || (failFirstSession && tokens == 1)) throw new HttpStatusException(rejectedStatus);
            byte[] media = new byte[512];
            if (!invalidMedia) { media[0] = 0x47; media[188] = 0x47; }
            return new Response(206, URI.create(url), "video/mp2t", Collections.emptyMap(), media);
        }
    }
}

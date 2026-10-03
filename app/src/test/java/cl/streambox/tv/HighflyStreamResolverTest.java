package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HighflyStreamResolverTest {
    @Test
    public void resolvesTheStreamResourceFromTheConfiguredManifest() throws Exception {
        List<String> requested = new java.util.ArrayList<>();
        TokenHttpClient http = new TokenHttpClient() {
            @Override
            public Response getPublicOnHosts(
                    String url,
                    Map<String, String> headers,
                    int maxResponseBytes,
                    String range,
                    java.util.Set<String> allowedHosts
            ) {
                requested.add(url);
                String payload = url.endsWith("/manifest.json")
                        ? "{\"resources\":[{\"name\":\"stream\"}]}"
                        : "{\"streams\":[{\"title\":\"1920x1080 · ~5.0 Mbps\","
                        + "\"url\":\"https://papacito.cfd/high.m3u8\"}]}";
                return new Response(
                        200,
                        URI.create(url),
                        "application/json",
                        Collections.emptyMap(),
                        payload.getBytes(StandardCharsets.UTF_8)
                );
            }
        };
        HighflyStreamResolver resolver = new HighflyStreamResolver(
                definition(Collections.emptyMap()),
                "https://sports.highfly.to/manifest.json",
                http,
                (uri, headers, listener) -> { }
        );

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertEquals("https://sports.highfly.to/manifest.json", requested.get(0));
        assertEquals(
                "https://sports.highfly.to/stream/sport/leaf:now-sky-sports-tennis.json",
                requested.get(1)
        );
        assertEquals("https://papacito.cfd/high.m3u8", source.getPlaybackUri().toString());
    }

    @Test
    public void requestsTheStableSlugEndpointAndUsesTheHighestBitrateCandidate() throws Exception {
        StubHttpClient http = new StubHttpClient(
                "{\"streams\":["
                        + "{\"title\":\"1920x1080 · ~3.8 Mbps\","
                        + "\"url\":\"https://leaf.highfly.dev/low.m3u8\"},"
                        + "{\"title\":\"1920x1080 · ~4.9 Mbps\","
                        + "\"url\":\"https://papacito.cfd/high.m3u8\"}]}"
        );
        List<URI> attempts = new java.util.ArrayList<>();
        HighflyStreamResolver resolver = new HighflyStreamResolver(
                definition(Collections.emptyMap()),
                http,
                (uri, headers, listener) -> attempts.add(uri)
        );

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertTrue(source.isDynamicallyResolved());
        assertEquals("https://papacito.cfd/high.m3u8", source.getPlaybackUri().toString());
        assertEquals(1, attempts.size());
        assertEquals(
                "https://sports.highfly.to/stream/sport/leaf:now-sky-sports-tennis.json",
                http.requestedUrls.get(0)
        );
        assertFalse(resolver.cacheResolvedSource());
    }

    @Test
    public void exposesAllValidatedStreamsToTheSourceSelectorInQualityOrder() throws Exception {
        StubHttpClient http = new StubHttpClient(
                "{\"streams\":["
                        + "{\"title\":\"1920x1080 · ~3.8 Mbps\","
                        + "\"url\":\"https://leaf.highfly.dev/low.m3u8\"},"
                        + "{\"title\":\"1920x1080 · ~4.9 Mbps\","
                        + "\"url\":\"https://papacito.cfd/high.m3u8\"}]}"
        );
        List<URI> attempts = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>()
        );
        HighflyStreamResolver resolver = new HighflyStreamResolver(
                definition(Collections.emptyMap()),
                http,
                (uri, headers, listener) -> attempts.add(uri)
        );

        List<ResolvedPlaybackCandidate> candidates = resolver.resolvePlaybackCandidates(
                channel(),
                ResolutionProgressListener.NONE
        );

        assertEquals(2, candidates.size());
        assertEquals("1920x1080 · ~4.9 Mbps", candidates.get(0).getLabel());
        assertEquals("1920x1080 · ~3.8 Mbps", candidates.get(1).getLabel());
        assertEquals(
                "https://papacito.cfd/high.m3u8",
                candidates.get(0).getSource().getPlaybackUri().toString()
        );
        assertEquals(
                "https://leaf.highfly.dev/low.m3u8",
                candidates.get(1).getSource().getPlaybackUri().toString()
        );
        assertEquals(2, attempts.size());
        assertTrue(candidates.get(0).getSource().isDynamicallyResolved());
        assertTrue(candidates.get(1).getSource().isDynamicallyResolved());
    }

    @Test(timeout = 5000)
    public void selectorWaitsForFirstValidationBeyondOnePollInterval() throws Exception {
        HighflyStreamResolver resolver = selectorResolver(Collections.emptyMap(),
                (uri, headers, listener) -> waitForValidation(650));

        List<ResolvedPlaybackCandidate> candidates = resolver.resolvePlaybackCandidates(
                channel(), ResolutionProgressListener.NONE);

        assertEquals(2, candidates.size());
        assertEquals("~4.9 Mbps", candidates.get(0).getLabel());
        assertEquals("~3.8 Mbps", candidates.get(1).getLabel());
    }

    @Test(timeout = 5000)
    public void selectorKeepsWaitingAfterAnEarlyValidSource() throws Exception {
        HighflyStreamResolver resolver = selectorResolver(Collections.emptyMap(),
                (uri, headers, listener) -> {
                    if (uri.getPath().contains("low")) waitForValidation(650);
                });

        List<ResolvedPlaybackCandidate> candidates = resolver.resolvePlaybackCandidates(
                channel(), ResolutionProgressListener.NONE);

        assertEquals(2, candidates.size());
        assertEquals("~3.8 Mbps", candidates.get(1).getLabel());
    }

    @Test(timeout = 5000)
    public void selectorKeepsWaitingAfterAnEarlyRejectedSource() throws Exception {
        HighflyStreamResolver resolver = selectorResolver(Collections.emptyMap(),
                (uri, headers, listener) -> {
                    if (uri.getPath().contains("high")) {
                        throw new IOException("synthetic HLS failure");
                    }
                    waitForValidation(650);
                });

        List<ResolvedPlaybackCandidate> candidates = resolver.resolvePlaybackCandidates(
                channel(), ResolutionProgressListener.NONE);

        assertEquals(1, candidates.size());
        assertEquals("~3.8 Mbps", candidates.get(0).getLabel());
    }

    @Test(timeout = 5000)
    public void selectorReturnsValidatedSourcesAtDeadlineAndCancelsPendingWork()
            throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowCancelled = new CountDownLatch(1);
        Map<String, String> config = new LinkedHashMap<>();
        config.put("resolutionBudgetMs", "1000");
        HighflyStreamResolver resolver = selectorResolver(config,
                (uri, headers, listener) -> {
                    if (uri.getPath().contains("high")) {
                        try {
                            if (!slowStarted.await(2, TimeUnit.SECONDS)) {
                                throw new IOException("Pending validator did not start");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted test validator", error);
                        }
                    } else {
                        slowStarted.countDown();
                        try {
                            waitForValidation(5000);
                        } finally {
                            slowCancelled.countDown();
                        }
                    }
                });
        long started = System.nanoTime();

        List<ResolvedPlaybackCandidate> candidates = resolver.resolvePlaybackCandidates(
                channel(), ResolutionProgressListener.NONE);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(1, candidates.size());
        assertEquals("~4.9 Mbps", candidates.get(0).getLabel());
        assertTrue("Must wait for the real deadline, not one poll", elapsedMs >= 800);
        assertTrue("Must remain bounded", elapsedMs < 3000);
        assertTrue("Pending validation must be cancelled",
                slowCancelled.await(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 5000)
    public void selectorFailsAtRealDeadlineWhenNoSourceHasValidated() throws Exception {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("resolutionBudgetMs", "1000");
        HighflyStreamResolver resolver = selectorResolver(config,
                (uri, headers, listener) -> waitForValidation(5000));
        long started = System.nanoTime();

        assertThrows(IOException.class, () -> resolver.resolvePlaybackCandidates(
                channel(), ResolutionProgressListener.NONE));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("Must not fail after one empty poll", elapsedMs >= 800);
        assertTrue("Must remain bounded", elapsedMs < 3000);
    }

    @Test(timeout = 5000)
    public void selectorHonorsParentCancellationWhileValidationsArePending() throws Exception {
        ResolutionContext parent = new ResolutionContext(12000);
        HighflyStreamResolver resolver = selectorResolver(Collections.emptyMap(),
                (uri, headers, listener) -> {
                    parent.cancel();
                    waitForValidation(5000);
                });
        long started = System.nanoTime();

        try (ResolutionContext.Scope ignored = parent.activate()) {
            assertThrows(IOException.class, () -> resolver.resolvePlaybackCandidates(
                    channel(), ResolutionProgressListener.NONE));
        }

        assertTrue(parent.isCancelled());
        assertTrue("Cancellation must not wait for the full budget",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
    }

    private static HighflyStreamResolver selectorResolver(
            Map<String, String> config,
            HighflyStreamResolver.HlsCandidateValidator validator
    ) {
        return new HighflyStreamResolver(definition(config), new StubHttpClient(
                "{\"streams\":["
                        + "{\"title\":\"~4.9 Mbps\","
                        + "\"url\":\"https://papacito.cfd/high.m3u8\"},"
                        + "{\"title\":\"~3.8 Mbps\","
                        + "\"url\":\"https://leaf.highfly.dev/low.m3u8\"}]}"
        ), validator);
    }

    private static void waitForValidation(long milliseconds) throws IOException {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted test validator", error);
        }
    }

    @Test
    public void triesTheNextCandidateAndThenFallsBackToTheM3uUrl() throws Exception {
        StubHttpClient http = new StubHttpClient(
                "{\"streams\":["
                        + "{\"title\":\"~4.9 Mbps\","
                        + "\"url\":\"https://leaf.highfly.dev/high.m3u8\"},"
                        + "{\"title\":\"~3.8 Mbps\","
                        + "\"url\":\"https://papacito.cfd/low.m3u8\"}]}"
        );
        List<URI> attempts = new java.util.ArrayList<>();
        HighflyStreamResolver resolver = new HighflyStreamResolver(
                definition(Collections.emptyMap()),
                http,
                (uri, headers, listener) -> {
                    attempts.add(uri);
                    throw new IOException("synthetic HLS failure");
                }
        );

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertFalse(source.isDynamicallyResolved());
        assertEquals(
                "https://leaf.highfly.dev/m3u/now-sky-sports-tennis/live.m3u8",
                source.getPlaybackUri().toString()
        );
        assertEquals(2, attempts.size());
        assertEquals("https://leaf.highfly.dev/high.m3u8", attempts.get(0).toString());
        assertEquals("https://papacito.cfd/low.m3u8", attempts.get(1).toString());
    }

    @Test
    public void rejectsAnUnallowlistedApiTemplateWithoutLeavingTheM3uFallback() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(
                "streamApiTemplate",
                "https://attacker.example/stream/sport/leaf:{slug}.json"
        );
        StubHttpClient http = new StubHttpClient(
                "{\"streams\":[{\"url\":\"http://attacker.example/live.m3u8\"}]}"
        );
        List<URI> attempts = new java.util.ArrayList<>();
        HighflyStreamResolver resolver = new HighflyStreamResolver(
                definition(config),
                http,
                (uri, headers, listener) -> attempts.add(uri)
        );

        ResolvedPlaybackSource source;
        try {
            source = resolver.resolve(channel());
        } catch (IOException error) {
            throw new AssertionError("A malformed endpoint should use the M3U fallback", error);
        }

        assertFalse(source.isDynamicallyResolved());
        assertEquals(0, http.requestedUrls.size());
        assertEquals(0, attempts.size());
        assertEquals(
                "https://leaf.highfly.dev/m3u/now-sky-sports-tennis/live.m3u8",
                source.getPlaybackUri().toString()
        );
    }

    @Test
    public void rejectsAnUnallowlistedOrNonHttpsCandidateBeforeHlsValidation() throws Exception {
        StubHttpClient http = new StubHttpClient(
                "{\"streams\":["
                        + "{\"url\":\"http://attacker.example/live.m3u8\"},"
                        + "{\"url\":\"https://attacker.example/other.m3u8\"}]}"
        );
        List<URI> attempts = new java.util.ArrayList<>();
        HighflyStreamResolver resolver = new HighflyStreamResolver(
                definition(Collections.emptyMap()),
                http,
                (uri, headers, listener) -> attempts.add(uri)
        );

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertFalse(source.isDynamicallyResolved());
        assertEquals(0, attempts.size());
        assertEquals(
                "https://leaf.highfly.dev/m3u/now-sky-sports-tennis/live.m3u8",
                source.getPlaybackUri().toString()
        );
    }

    @Test
    public void triesOtherPublishedLeavesWhenTheConfiguredSlugPublishesNoHls()
            throws Exception {
        List<String> requested = new java.util.ArrayList<>();
        TokenHttpClient http = new TokenHttpClient() {
            @Override
            public Response getPublicOnHosts(
                    String url,
                    Map<String, String> headers,
                    int maxResponseBytes,
                    String range,
                    java.util.Set<String> allowedHosts
            ) {
                requested.add(url);
                String payload;
                if (url.endsWith("/manifest.json")) {
                    payload = "{\"resources\":[{\"name\":\"stream\"}]}";
                } else if (url.endsWith("/catalog/sport/sports_live.json")) {
                    payload = "{\"metas\":[{\"id\":\"leaf:alt-tennis\","
                            + "\"name\":\"Sky Sports Tennis\"}]}";
                } else if (url.contains("leaf:now-sky-sports-tennis")) {
                    payload = "{\"streams\":[{\"url\":"
                            + "\"https://www.google.com/accounts/upgrade\"}]}";
                } else {
                    payload = "{\"streams\":[{\"url\":"
                            + "\"https://papacito.cfd/m3u/alt-tennis/live.m3u8\"}]}";
                }
                return new Response(
                        200,
                        URI.create(url),
                        "application/json",
                        Collections.emptyMap(),
                        payload.getBytes(StandardCharsets.UTF_8)
                );
            }
        };
        HighflyStreamResolver resolver = new HighflyStreamResolver(
                definition(Collections.emptyMap()),
                "https://sports.highfly.to/manifest.json",
                http,
                (uri, headers, listener) -> { }
        );

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertTrue(source.isDynamicallyResolved());
        assertEquals(
                "https://papacito.cfd/m3u/alt-tennis/live.m3u8",
                source.getPlaybackUri().toString()
        );
        assertTrue(requested.stream().anyMatch(url -> url.contains("leaf:alt-tennis")));
    }

    private static Channel channel() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "SkySportsTennis.uk");
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-id", "now-sky-sports-tennis");
        attributes.put("x-resolver-refresh", "on_play");
        return new Channel(
                "Sky Sports Tennis",
                URI.create("https://leaf.highfly.dev/m3u/now-sky-sports-tennis/live.m3u8"),
                null,
                "Sports",
                attributes
        );
    }

    private static ResolverDefinition definition(Map<String, String> extraConfig) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("streamApiTemplate", HighflyStreamResolver.DEFAULT_STREAM_API_TEMPLATE);
        config.put("streamArrayPath", "streams");
        config.put("maxStreams", "16");
        config.put("maxPayloadBytes", "262144");
        config.put("resolutionBudgetMs", "12000");
        config.putAll(extraConfig);
        return new ResolverDefinition(
                "highfly",
                "Highfly",
                "highfly",
                true,
                300_000L,
                Collections.emptySet(),
                Collections.emptyList(),
                new LinkedHashSet<>(java.util.Arrays.asList(
                        "leaf.highfly.dev", "papacito.cfd"
                )),
                config,
                Collections.emptyMap()
        );
    }

    private static final class StubHttpClient extends TokenHttpClient {
        private final String payload;
        private final List<String> requestedUrls = new java.util.ArrayList<>();

        StubHttpClient(String payload) {
            this.payload = payload;
        }

        @Override
        public Response getPublicOnHosts(
                String url,
                Map<String, String> headers,
                int maxResponseBytes,
                String range,
                java.util.Set<String> allowedHosts
        ) {
            requestedUrls.add(url);
            return new Response(
                    200,
                    URI.create(url),
                    "application/json",
                    Collections.emptyMap(),
                    payload.getBytes(StandardCharsets.UTF_8)
            );
        }
    }
}

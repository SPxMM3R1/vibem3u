package cl.streambox.tv;

import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ResolverCoordinatorTest {
    @Test
    public void reusesOnlySessionMemoryAndForceRefreshAlwaysReplacesIt() throws Exception {
        ResolverCoordinator coordinator = new ResolverCoordinator();
        AtomicInteger calls = new AtomicInteger();
        Channel channel = new Channel(
                "Test",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Test",
                Collections.singletonMap("tvg-id", "test")
        );
        StreamResolver resolver = new StreamResolver() {
            @Override public String getId() { return "test"; }
            @Override public boolean supports(Channel value) { return true; }
            @Override public long cacheTtlMillis() { return 60_000L; }
            @Override public ResolvedPlaybackSource resolve(Channel value) {
                int call = calls.incrementAndGet();
                return ResolvedPlaybackSource.dynamic(
                        "test",
                        "test",
                        URI.create("https://example.org/session-" + call + ".m3u8"),
                        Collections.emptyMap(),
                        "test-agent",
                        System.currentTimeMillis() + 60_000L
                );
            }
        };
        AtomicReference<ResolutionStage> cachedStage = new AtomicReference<>();

        assertEquals(
                "https://example.org/session-1.m3u8",
                coordinator.resolve(channel, resolver, false).getPlaybackUri().toString()
        );
        assertEquals(
                "https://example.org/session-1.m3u8",
                coordinator.resolve(
                        channel,
                        resolver,
                        false,
                        progress -> cachedStage.set(progress.getStage())
                ).getPlaybackUri().toString()
        );
        assertEquals(ResolutionStage.CACHE_REUSED, cachedStage.get());
        assertEquals(
                "https://example.org/session-2.m3u8",
                coordinator.resolve(channel, resolver, true).getPlaybackUri().toString()
        );
        coordinator.clear();
        assertEquals(0, coordinator.cachedSourceCount());
    }

    @Test
    public void zeroTtlNeverKeepsAProviderToken() throws Exception {
        ResolverCoordinator coordinator = new ResolverCoordinator();
        AtomicInteger calls = new AtomicInteger();
        Channel channel = new Channel(
                "TVN",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Chile",
                Collections.singletonMap("tvg-id", "0104")
        );
        StreamResolver resolver = new StreamResolver() {
            @Override public String getId() { return "tvn"; }
            @Override public boolean supports(Channel value) { return true; }
            @Override public ResolvedPlaybackSource resolve(Channel value) {
                calls.incrementAndGet();
                return ResolvedPlaybackSource.dynamic(
                        "tvn",
                        "0104",
                        URI.create("https://example.org/fresh.m3u8"),
                        Collections.emptyMap(),
                        "test-agent",
                        0L
                );
            }
        };

        coordinator.resolve(channel, resolver, false);
        coordinator.resolve(channel, resolver, false);

        assertEquals(2, calls.get());
        assertEquals(0, coordinator.cachedSourceCount());
    }

    @Test
    public void expiredSessionSourceIsResolvedAgainBeforeReuse() throws Exception {
        ResolverCoordinator coordinator = new ResolverCoordinator();
        AtomicInteger calls = new AtomicInteger();
        Channel channel = new Channel(
                "Expired",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Test",
                Collections.singletonMap("tvg-id", "expired")
        );
        StreamResolver resolver = new StreamResolver() {
            @Override public String getId() { return "expired"; }
            @Override public boolean supports(Channel value) { return true; }
            @Override public long cacheTtlMillis() { return 60_000L; }
            @Override public ResolvedPlaybackSource resolve(Channel value) {
                int call = calls.incrementAndGet();
                return ResolvedPlaybackSource.dynamic(
                        "expired",
                        "expired",
                        URI.create("https://example.org/session-" + call + ".m3u8"),
                        Collections.emptyMap(),
                        "test-agent",
                        System.currentTimeMillis() - 1L
                );
            }
        };

        coordinator.resolve(channel, resolver, false);
        coordinator.resolve(channel, resolver, false);

        assertEquals(2, calls.get());
        assertEquals(0, coordinator.cachedSourceCount());
    }

    @Test
    public void officialTokenResolversUseBoundedSessionCacheByDefault() {
        TvnStreamResolver tvn = new TvnStreamResolver();
        MeganoticiasStreamResolver mega = new MeganoticiasStreamResolver();

        assertTrue(tvn.cacheResolvedSource());
        assertEquals(5L * 60L * 1000L, tvn.cacheTtlMillis());
        assertTrue(mega.cacheResolvedSource());
        assertEquals(5L * 60L * 1000L, mega.cacheTtlMillis());
    }

    @Test
    public void legacyZeroTtlCatalogDoesNotDisableOfficialTokenCache() {
        TvnStreamResolver tvn = new TvnStreamResolver(definition("tvn", "tvn", 0L,
                Collections.emptyMap()));
        MeganoticiasStreamResolver mega = new MeganoticiasStreamResolver(
                definition("meganoticias", "meganoticias", 0L, Collections.emptyMap())
        );

        assertTrue(tvn.cacheResolvedSource());
        assertEquals(5L * 60L * 1000L, tvn.cacheTtlMillis());
        assertTrue(mega.cacheResolvedSource());
        assertEquals(5L * 60L * 1000L, mega.cacheTtlMillis());
    }

    @Test
    public void explicitCatalogOptOutStillDisablesOfficialTokenCache() {
        TvnStreamResolver tvn = new TvnStreamResolver(definition(
                "tvn", "tvn", 300_000L,
                Collections.singletonMap("cacheEnabled", "false")
        ));

        assertFalse(tvn.cacheResolvedSource());
        assertEquals(0L, tvn.cacheTtlMillis());
    }

    @Test
    public void invalidationRemovesTheCachedTokenBeforeTheNextOpen() throws Exception {
        ResolverCoordinator coordinator = new ResolverCoordinator();
        AtomicInteger calls = new AtomicInteger();
        Channel channel = new Channel(
                "TVN",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Chile",
                Collections.singletonMap("tvg-id", "0104")
        );
        StreamResolver resolver = new StreamResolver() {
            @Override public String getId() { return "tvn"; }
            @Override public boolean supports(Channel value) { return true; }
            @Override public long cacheTtlMillis() { return 60_000L; }
            @Override public ResolvedPlaybackSource resolve(Channel value) {
                int call = calls.incrementAndGet();
                return ResolvedPlaybackSource.dynamic(
                        "tvn",
                        "0104",
                        URI.create("https://example.org/token-" + call + ".m3u8"),
                        Collections.singletonMap("Referer", "https://live.tvn.cl/"),
                        "test-agent",
                        System.currentTimeMillis() + 60_000L
                );
            }
        };

        assertEquals(
                "https://example.org/token-1.m3u8",
                coordinator.resolve(channel, resolver, false).getPlaybackUri().toString()
        );
        coordinator.invalidate(channel, resolver);
        assertEquals(
                "https://example.org/token-2.m3u8",
                coordinator.resolve(channel, resolver, false).getPlaybackUri().toString()
        );
        assertEquals(2, calls.get());
    }

    @Test
    public void explicitNoCachePolicyWinsEvenWhenResolverReportsATtl() throws Exception {
        ResolverCoordinator coordinator = new ResolverCoordinator();
        AtomicInteger calls = new AtomicInteger();
        Channel channel = new Channel(
                "Vavoo",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Test",
                Collections.singletonMap("tvg-id", "vavoo-test")
        );
        StreamResolver resolver = new StreamResolver() {
            @Override public String getId() { return "vavoo"; }
            @Override public boolean supports(Channel value) { return true; }
            @Override public long cacheTtlMillis() { return 60_000L; }
            @Override public boolean cacheResolvedSource() { return false; }

            @Override
            public ResolvedPlaybackSource resolve(Channel value) {
                int call = calls.incrementAndGet();
                return source("https://example.org/fresh-" + call + ".m3u8");
            }
        };

        assertEquals(
                "https://example.org/fresh-1.m3u8",
                coordinator.resolve(channel, resolver, false).getPlaybackUri().toString()
        );
        assertEquals(
                "https://example.org/fresh-2.m3u8",
                coordinator.resolve(channel, resolver, false).getPlaybackUri().toString()
        );
        assertEquals(2, calls.get());
        assertEquals(0, coordinator.cachedSourceCount());
    }

    @Test
    public void forceRefreshNeverJoinsOrCachesAnOlderInFlightToken() throws Exception {
        ResolverCoordinator coordinator = new ResolverCoordinator();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        Channel channel = new Channel(
                "Dynamic",
                URI.create("https://example.org/fallback.m3u8"),
                null,
                "Test",
                Collections.singletonMap("tvg-id", "dynamic")
        );
        StreamResolver resolver = new StreamResolver() {
            @Override public String getId() { return "dynamic"; }
            @Override public boolean supports(Channel value) { return true; }
            @Override public long cacheTtlMillis() { return 60_000L; }
            @Override public ResolvedPlaybackSource resolve(Channel value) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    oldStarted.countDown();
                    try {
                        releaseOld.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
                return source("https://example.org/session-" + call + ".m3u8");
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResolvedPlaybackSource> old = executor.submit(() ->
                    coordinator.resolve(channel, resolver, false));
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS));

            ResolvedPlaybackSource fresh = coordinator.resolve(channel, resolver, true);
            releaseOld.countDown();
            old.get(5, TimeUnit.SECONDS);

            assertEquals("https://example.org/session-2.m3u8", fresh.getPlaybackUri().toString());
            assertEquals(
                    "https://example.org/session-2.m3u8",
                    coordinator.resolve(channel, resolver, false).getPlaybackUri().toString()
            );
        } finally {
            releaseOld.countDown();
            executor.shutdownNow();
        }
    }

    private static ResolvedPlaybackSource source(String url) {
        return ResolvedPlaybackSource.dynamic(
                "dynamic",
                "dynamic",
                URI.create(url),
                Collections.emptyMap(),
                "test-agent",
                System.currentTimeMillis() + 60_000L
        );
    }

    private static ResolverDefinition definition(
            String id,
            String engine,
            long cacheTtlMillis,
            java.util.Map<String, String> config
    ) {
        return new ResolverDefinition(
                id,
                id,
                engine,
                true,
                cacheTtlMillis,
                Collections.emptySet(),
                Collections.emptyList(),
                Collections.emptySet(),
                config,
                Collections.emptyMap()
        );
    }
}

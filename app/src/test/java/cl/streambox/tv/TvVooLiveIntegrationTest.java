package cl.streambox.tv;

import org.junit.Assume;
import org.junit.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Opt-in network validation; skipped by normal and CI unit-test runs. */
public final class TvVooLiveIntegrationTest {
    @Test(timeout = 120_000L)
    public void resolvesCnnThroughDirectVavooWhenTvVooEndpointFails() throws Exception {
        Assume.assumeTrue("1".equals(System.getenv("VIBEM3U_LIVE_RESOLVER_TESTS")));

        ResolverDefinition definition = ResolverCatalog.parse(catalogJson()).getById("tvvoo");
        URI fallback = URI.create("https://example.invalid/fallback.m3u8");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "CNN.uk@TvVoo");
        attributes.put("tvg-name", "CNN");
        attributes.put("tvg-country", "GB");
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-ids", "vavoo_CNN%7Cgroup%3Auk");
        Channel channel = new Channel("CNN", fallback, null, "News", attributes);

        ResolvedPlaybackSource source = new TvVooStreamResolver(definition).resolve(channel);

        assertTrue(source.isDynamicallyResolved());
        assertEquals("tvvoo", source.getResolverId());
        assertNotEquals(fallback, source.getPlaybackUri());
        assertEquals(
                TvVooStreamResolver.PLAYBACK_USER_AGENT,
                source.getRequestHeaders().get("User-Agent")
        );
    }

    @Test(timeout = 120_000L)
    public void directVavooFallsBackToHttpOnlyAfterExpiredTls() throws Exception {
        Assume.assumeTrue("1".equals(System.getenv("VIBEM3U_LIVE_RESOLVER_TESTS")));

        ResolverDefinition definition = ResolverCatalog.parse(catalogJson()).getById("tvvoo");
        URI fallback = URI.create("https://example.invalid/fallback.m3u8");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "TNTSports1.uk@TvVoo");
        attributes.put("tvg-name", "TNT Sports 1");
        attributes.put("tvg-country", "GB");
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-ids", "vavoo_TNT%20SPORTS%201%7Cgroup%3Auk");
        Channel channel = new Channel("TNT Sports 1", fallback, null, "Sports", attributes);

        ResolvedPlaybackSource source = new VavooStreamResolver(definition).resolve(channel);

        assertTrue(source.isDynamicallyResolved());
        assertEquals("tvvoo", source.getResolverId());
        assertNotEquals(fallback, source.getPlaybackUri());
        assertEquals(
                TvVooStreamResolver.PLAYBACK_USER_AGENT,
                source.getRequestHeaders().get("User-Agent")
        );
    }

    private static String catalogJson() {
        return "{\"schemaVersion\":1,\"catalogVersion\":\"live-test\",\"providers\":[{"
                + "\"id\":\"tvvoo\",\"name\":\"TvVoo\",\"engine\":\"tvvoo\","
                + "\"cacheTtlSeconds\":0,\"match\":{\"tvgIdSuffixes\":[\"@TvVoo\"]},"
                + "\"config\":{"
                + "\"endpointBase\":\"https://tvvoo.hayd.uk/stream/tv-missing\","
                + "\"maxAliases\":1,\"maxCandidates\":1,\"directFallback\":true,"
                + "\"pingUrl\":\"https://www.vavoo.tv/api/app/ping\","
                + "\"fallbackPingUrl\":\"https://www.vypn.net/api/app/ping\","
                + "\"catalogBase\":\"https://vavoo.to\","
                + "\"fallbackCatalogBase\":\"https://kool.to\","
                + "\"catalogPath\":\"mediahubmx-catalog.json\","
                + "\"resolvePath\":\"mediahubmx-resolve.json\","
                + "\"maxSearchTargets\":1,\"maxSearchPages\":1,"
                + "\"maxSearchItems\":20,\"maxResolveCandidates\":4}}]}";
    }
}

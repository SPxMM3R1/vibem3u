package cl.streambox.tv;

import org.junit.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.*;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import static org.junit.Assert.*;

public class ResolutionTransportTest {
    @Test public void handoffIsSingleUseExactAndInvalidatedByCancellation() {
        ResolutionContext root = new ResolutionContext(10_000);
        ManifestHandoffCache cache = root.manifests();
        URI signed = URI.create("https://example.org/live.m3u8?a=x%26b%3Dy");
        URI different = URI.create("https://example.org/live.m3u8?a=x&b=y");
        byte[] body = "#EXTM3U\n".getBytes(StandardCharsets.UTF_8);
        cache.put(signed, signed, Collections.emptyMap(), body);
        assertNull(cache.consume(different));
        assertArrayEquals(body, cache.consume(signed).getRawBytes());
        assertNull(cache.consume(signed));
        cache.put(signed, signed, Collections.emptyMap(), body);
        root.child(1000).cancel();
        assertNotNull(cache.peek(signed));
        root.cancel();
        assertNull(cache.consume(signed));
        assertEquals(0, cache.size());
    }

    @Test(timeout=7000) public void cancellingContextClosesBlockedHttpCall() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            String url = server.url("/stalled.m3u8").toString();
            ResolutionContext context = new ResolutionContext(20_000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> request = executor.submit(() -> {
                    try (ResolutionContext.Scope ignored = context.activate()) {
                        new TokenHttpClient().getText(url, Collections.emptyMap());
                        return false;
                    } catch (java.io.IOException cancelled) { return true; }
                });
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                context.cancel();
                assertTrue(request.get(2, TimeUnit.SECONDS));
            } finally { context.cancel(); executor.shutdownNow(); }
        }
    }

    @Test public void expiryHonoursMarginAndNeverTurnsExpiredTokenIntoUnlimitedSource() {
        TokenExpiryPolicy policy = new TokenExpiryPolicy(300_000, 15_000);
        assertEquals(185_000, policy.effectiveExpiryAtMillis(100_000, 200_000));
        assertEquals(400_000, policy.effectiveExpiryAtMillis(100_000, 0));
        assertEquals(1, policy.effectiveExpiryAtMillis(100_000, 10_000));
        assertTrue(policy.isExpired(100_000, 10_000, 100_000));
        assertEquals(0, ProviderStreamParsers.parseTvnExpiryMillis(
                "var ad={expiration:1700000000};var player={access_token:'test-token'};", "test-token"));
    }
}

package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.security.cert.CertificateExpiredException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class TvVooResolutionModeTest {
    @Test
    public void unknownStoredValueFallsBackToBoth() {
        assertEquals(TvVooResolutionMode.BOTH, TvVooResolutionMode.fromPreference(null));
        assertEquals(TvVooResolutionMode.BOTH, TvVooResolutionMode.fromPreference("broken"));
        assertEquals(
                TvVooResolutionMode.DIRECT_ONLY,
                TvVooResolutionMode.fromPreference("DIRECT_ONLY")
        );
        assertEquals(
                TvVooResolutionMode.EXTERNAL_ONLY,
                TvVooResolutionMode.fromPreference(" external_only ")
        );
    }

    @Test
    public void routingPolicyKeepsTheThreeModesIndependent() {
        assertTrue(TvVooResolutionMode.BOTH.usesExternalResolver());
        assertTrue(TvVooResolutionMode.BOTH.usesDirectResolver());

        assertFalse(TvVooResolutionMode.DIRECT_ONLY.usesExternalResolver());
        assertTrue(TvVooResolutionMode.DIRECT_ONLY.usesDirectResolver());

        assertTrue(TvVooResolutionMode.EXTERNAL_ONLY.usesExternalResolver());
        assertFalse(TvVooResolutionMode.EXTERNAL_ONLY.usesDirectResolver());
    }

    @Test
    public void directOnlyBypassesAnInvalidExternalEndpoint() throws Exception {
        FakeDirectResolver direct = new FakeDirectResolver();
        TvVooStreamResolver resolver = resolver(TvVooResolutionMode.DIRECT_ONLY, direct);

        ResolvedPlaybackSource source = resolver.resolve(channel());

        assertEquals(1, direct.resolveCalls);
        assertEquals(URI.create("https://example.com/direct.m3u8"), source.getPlaybackUri());
    }

    @Test
    public void externalOnlyNeverCallsTheDirectResolver() throws Exception {
        FakeDirectResolver direct = new FakeDirectResolver();
        TvVooStreamResolver resolver = resolver(TvVooResolutionMode.EXTERNAL_ONLY, direct);

        assertThrows(IOException.class, () -> resolver.resolve(channel()));
        assertEquals(0, direct.resolveCalls);
    }

    @Test
    public void httpFallbackAcceptsOnlyAnExpiredCertificate() {
        IOException expired = new IOException(
                "TLS",
                new CertificateExpiredException("NotAfter: expired")
        );
        IOException unrelatedHandshake = new IOException(
                "TLS",
                new javax.net.ssl.SSLHandshakeException("certificate_unknown")
        );

        assertTrue(TvVooStreamResolver.isExpiredCertificateFailure(expired));
        assertFalse(TvVooStreamResolver.isExpiredCertificateFailure(unrelatedHandshake));
    }

    private static TvVooStreamResolver resolver(
            TvVooResolutionMode mode,
            StreamResolver direct
    ) throws Exception {
        ResolverDefinition definition = ResolverCatalog.parse(
                "{\"schemaVersion\":1,\"catalogVersion\":\"test\",\"providers\":[{"
                        + "\"id\":\"tvvoo\",\"name\":\"TvVoo\",\"engine\":\"tvvoo\","
                        + "\"cacheTtlSeconds\":0,"
                        + "\"match\":{\"tvgIdSuffixes\":[\"@TvVoo\"]},"
                        + "\"config\":{\"directFallback\":true}}]}"
        ).getById("tvvoo");
        TokenHttpClient client = new TokenHttpClient(1_000, 1_000);
        return new TvVooStreamResolver(
                definition,
                client,
                new HlsStreamValidator(client),
                direct,
                mode
        );
    }

    private static Channel channel() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tvg-id", "CNN.uk@TvVoo");
        attributes.put("x-resolver", "tvvoo");
        attributes.put("x-resolver-endpoint", "invalid endpoint");
        return new Channel(
                "CNN",
                URI.create("https://example.com/fallback.m3u8"),
                null,
                "News",
                attributes
        );
    }

    private static final class FakeDirectResolver implements StreamResolver {
        private int resolveCalls;

        @Override public String getId() { return "vavoo"; }
        @Override public boolean supports(Channel channel) { return true; }

        @Override
        public ResolvedPlaybackSource resolve(Channel channel) {
            resolveCalls++;
            return ResolvedPlaybackSource.dynamic(
                    "tvvoo",
                    URI.create("https://example.com/direct.m3u8"),
                    Collections.emptyMap(),
                    TvVooStreamResolver.PLAYBACK_USER_AGENT
            );
        }
    }
}

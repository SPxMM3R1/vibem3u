package cl.streambox.tv;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HighflyPremiumTest {
    private static final String TOKEN = "0f3c9a7e-1b2d-4c5e-8f90-a1b2c3d4e5f6";

    @Test
    public void acceptsTheRawTokenAndTheConfiguredManifestLinks() throws IOException {
        assertEquals(TOKEN, HighflyPremiumTokenRules.parseInput(" " + TOKEN + " ").getToken());
        assertNull(HighflyPremiumTokenRules.parseInput(TOKEN).getRegion());

        HighflyPremiumTokenRules.ParsedInput plain = HighflyPremiumTokenRules.parseInput(
                "https://premium-eu1.highfly.to/" + TOKEN + "/manifest.json");
        assertEquals(TOKEN, plain.getToken());
        assertEquals(HighflyPremiumRegion.EU1, plain.getRegion());

        // El configurador nuevo agrega un tramo con las opciones: se descarta.
        HighflyPremiumTokenRules.ParsedInput configured = HighflyPremiumTokenRules.parseInput(
                "stremio://premium.highfly.to/" + TOKEN + "/eyJvbmx5TGl2ZSI6dHJ1ZX0/manifest.json");
        assertEquals(TOKEN, configured.getToken());
        assertEquals(HighflyPremiumRegion.MAIN, configured.getRegion());

        // El dominio anterior todavía se reconoce, pero la región apunta al .to.
        assertEquals(HighflyPremiumRegion.US2, HighflyPremiumTokenRules.parseInput(
                "https://premium-us2.highfly.dev/" + TOKEN + "/manifest.json").getRegion());
    }

    @Test
    public void rejectsLinksOutsideHighflyAndUnsafeTokens() {
        assertThrows(IOException.class, () -> HighflyPremiumTokenRules.parseInput(
                "https://evil.example/" + TOKEN + "/manifest.json"));
        assertThrows(IOException.class, () -> HighflyPremiumTokenRules.parseInput(
                "http://premium.highfly.to/" + TOKEN + "/manifest.json"));
        assertThrows(IOException.class, () -> HighflyPremiumTokenRules.parseInput(
                "https://premium.highfly.to:8443/" + TOKEN + "/manifest.json"));
        assertFalse(HighflyPremiumTokenRules.isValid("abc/def/ghi"));
        assertFalse(HighflyPremiumTokenRules.isValid("short"));
        assertFalse(HighflyPremiumTokenRules.isValid("token%2Fescape"));
    }

    @Test
    public void readsThePlanLikeTheConfigurePage() throws IOException {
        long now = 1_790_000_000_000L;
        long inAYear = now / 1000L + 360L * 86_400L;
        HighflyPremiumAccount yearly = HighflyPremiumAccount.parse(
                "{\"active\":true,\"plan\":1,\"expires_at\":" + inAYear + "}", now);
        assertEquals(HighflyPremiumAccount.Plan.YEARLY, yearly.getPlan());
        assertTrue(yearly.isActive());
        assertFalse(yearly.isExpired(now));

        HighflyPremiumAccount monthly = HighflyPremiumAccount.parse(
                "{\"active\":true,\"plan\":1,\"expires_at\":" + (now / 1000L + 20L * 86_400L) + "}", now);
        assertEquals(HighflyPremiumAccount.Plan.MONTHLY, monthly.getPlan());

        HighflyPremiumAccount revoked = HighflyPremiumAccount.parse("{\"active\":false,\"plan\":3}", now);
        assertFalse(revoked.isActive());
        assertEquals(HighflyPremiumAccount.Plan.PRO, revoked.getPlan());

        HighflyPremiumAccount expired = HighflyPremiumAccount.parse(
                "{\"active\":true,\"plan\":2,\"expires_at\":" + (now / 1000L - 60L) + "}", now);
        assertTrue(expired.isExpired(now));
    }

    @Test
    public void verifySendsTheTokenOnlyToPremiumHostsAndMaps401ToRejected() throws IOException {
        RecordingHttp http = new RecordingHttp(401, "{}");
        HighflyPremiumClient client = new HighflyPremiumClient(http);
        assertThrows(HighflyPremiumClient.RejectedException.class,
                () -> client.verify(TOKEN, HighflyPremiumRegion.AUTO));
        // Un rechazo corta: no se prueba el token en las demás regiones.
        assertEquals(1, http.urls.size());
        assertEquals("https://premium.highfly.to/" + TOKEN + "/verify.json", http.urls.get(0));
        assertEquals(HighflyPremiumRegion.HOSTS, http.lastHosts);
    }

    @Test
    public void autoRegionFallsBackWhenAServerIsDown() throws IOException {
        RecordingHttp http = new RecordingHttp(503, "{}");
        http.successAfter = 1;
        http.successBody = "{\"streams\":[{\"title\":\"~6 Mbps\",\"url\":\"https://cdn.example/live.m3u8\"}]}";
        List<ResolverPayloadParsers.HighflyCandidate> streams = new HighflyPremiumClient(http)
                .streams(TOKEN, HighflyPremiumRegion.AUTO, "now-sky-sports-f1");
        assertEquals(1, streams.size());
        assertEquals("https://premium-eu1.highfly.to/" + TOKEN + "/stream/sport/leaf:now-sky-sports-f1.json",
                http.urls.get(1));
    }

    @Test
    public void errorsNeverCarryTheToken() {
        RecordingHttp http = new RecordingHttp(500, "{}");
        IOException error = assertThrows(IOException.class, () -> new HighflyPremiumClient(http)
                .verify(TOKEN, HighflyPremiumRegion.MAIN));
        assertFalse(String.valueOf(error.getMessage()).contains(TOKEN));
    }

    @Test(timeout = 10_000)
    public void pairingServerNeedsTheCodeAndClosesAfterLinking() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        boolean[] linked = {false};
        PremiumPairingServer server = new PremiumPairingServer(new PremiumPairingServer.Listener() {
            @Override
            public PremiumPairingServer.Outcome onToken(HighflyPremiumTokenRules.ParsedInput input) {
                received.set(input.getToken());
                return new PremiumPairingServer.Outcome(true, "Token verificado.");
            }

            @Override
            public void onClosed(boolean ok, boolean tooManyAttempts) {
                linked[0] = ok;
                closed.countDown();
            }
        });
        int port = server.start();
        String page = request(port, "GET / HTTP/1.1\r\nHost: tv\r\n\r\n");
        assertTrue(page.startsWith("HTTP/1.1 200"));
        assertTrue(page.contains("name=\"token\""));

        String wrongCode = server.code().equals("0000") ? "1111" : "0000";
        String rejected = post(port, "token=" + TOKEN + "&code=" + wrongCode);
        assertTrue(rejected.startsWith("HTTP/1.1 403"));
        assertNull(received.get());

        String accepted = post(port, "token=" + java.net.URLEncoder.encode(
                "https://premium.highfly.to/" + TOKEN + "/manifest.json", "UTF-8")
                + "&code=" + server.code());
        assertTrue(accepted.startsWith("HTTP/1.1 200"));
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertTrue(linked[0]);
        assertEquals(TOKEN, received.get());
    }

    @Test(timeout = 10_000)
    public void pairingServerLocksAfterFiveWrongCodes() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        boolean[] locked = {false};
        PremiumPairingServer server = new PremiumPairingServer(new PremiumPairingServer.Listener() {
            @Override
            public PremiumPairingServer.Outcome onToken(HighflyPremiumTokenRules.ParsedInput input) {
                throw new AssertionError("No debe aceptar sin el código");
            }

            @Override
            public void onClosed(boolean ok, boolean tooManyAttempts) {
                locked[0] = tooManyAttempts;
                closed.countDown();
            }
        });
        int port = server.start();
        String wrongCode = server.code().equals("0000") ? "1111" : "0000";
        for (int attempt = 0; attempt < 5; attempt++) {
            post(port, "token=" + TOKEN + "&code=" + wrongCode);
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertTrue(locked[0]);
    }

    @Test
    public void pageEscapesMessages() {
        assertEquals("&lt;b&gt;&amp;&quot;", PremiumPairingPage.escape("<b>&\""));
        assertEquals("a b", PremiumPairingServer.formValue("x=1&token=a+b", "token"));
    }

    @Test
    public void linkedPremiumIsTriedBeforeTheFreeSignal() throws IOException {
        FakeLink link = new FakeLink();
        RoutedHttp http = new RoutedHttp();
        http.premium = "{\"streams\":[{\"title\":\"~8 Mbps\",\"url\":\"https://premium-cdn.example/a.m3u8\"}]}";
        try {
            HighflyPremiumLink.install(link);
            ResolvedPlaybackSource source = resolver(http).resolve(channel());
            assertEquals("https://premium-cdn.example/a.m3u8", source.getPlaybackUri().toString());
            assertTrue(http.urls.get(0).startsWith("https://premium.highfly.to/" + TOKEN + "/stream/sport/leaf:"));
        } finally {
            HighflyPremiumLink.install(null);
        }
    }

    @Test
    public void unavailablePremiumFallsBackToTheFreeSignal() throws IOException {
        FakeLink link = new FakeLink();
        RoutedHttp http = new RoutedHttp();
        http.premiumStatus = 503;
        try {
            HighflyPremiumLink.install(link);
            ResolvedPlaybackSource source = resolver(http).resolve(channel());
            assertEquals("https://leaf.highfly.dev/free.m3u8", source.getPlaybackUri().toString());
            assertFalse(link.rejected);
        } finally {
            HighflyPremiumLink.install(null);
        }
    }

    @Test
    public void rejectedTokenStopsAndIsRemembered() {
        FakeLink link = new FakeLink();
        RoutedHttp http = new RoutedHttp();
        http.premiumStatus = 401;
        try {
            HighflyPremiumLink.install(link);
            assertThrows(HighflyPremiumClient.RejectedException.class,
                    () -> resolver(http).resolve(channel()));
            assertTrue(link.rejected);
            HighflyPremiumSession.useFreeFor("now-sky-sports-f1");
            // «Ver señal gratuita»: el mismo canal ya no consulta Premium.
            int before = http.urls.size();
            assertEquals("https://leaf.highfly.dev/free.m3u8",
                    resolver(http).resolve(channel()).getPlaybackUri().toString());
            assertFalse(http.urls.subList(before, http.urls.size()).stream()
                    .anyMatch(url -> url.contains("premium")));
        } catch (IOException error) {
            throw new AssertionError(error);
        } finally {
            HighflyPremiumSession.reset();
            HighflyPremiumLink.install(null);
        }
    }

    private static HighflyStreamResolver resolver(TokenHttpClient http) {
        Map<String, String> config = new java.util.LinkedHashMap<>();
        config.put("streamApiTemplate", HighflyStreamResolver.DEFAULT_STREAM_API_TEMPLATE);
        config.put("streamArrayPath", "streams");
        config.put("maxStreams", "16");
        config.put("maxPayloadBytes", "262144");
        config.put("resolutionBudgetMs", "12000");
        ResolverDefinition definition = new ResolverDefinition(
                "highfly", "Highfly", "highfly", true, 300_000L,
                Collections.emptySet(), Collections.emptyList(),
                new java.util.LinkedHashSet<>(java.util.Arrays.asList("leaf.highfly.dev", "papacito.cfd")),
                config, Collections.emptyMap());
        return new HighflyStreamResolver(definition, http,
                (HighflyStreamResolver.HlsCandidateValidator) (uri, headers, listener) -> { });
    }

    private static Channel channel() {
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("x-resolver", "highfly");
        attributes.put("x-resolver-id", "now-sky-sports-f1");
        return new Channel("Sky Sports F1",
                URI.create("https://leaf.highfly.dev/m3u/now-sky-sports-f1/live.m3u8"),
                null, "Deportes", attributes);
    }

    private static final class FakeLink implements HighflyPremiumLink.Source {
        boolean rejected;

        @Override public boolean linked() { return true; }
        @Override public boolean rejected() { return rejected; }
        @Override public String token() { return TOKEN; }
        @Override public HighflyPremiumRegion region() { return HighflyPremiumRegion.MAIN; }
        @Override public void markRejected() { rejected = true; }
    }

    private static final class RoutedHttp extends TokenHttpClient {
        final List<String> urls = new ArrayList<>();
        int premiumStatus = 200;
        String premium = "{\"streams\":[]}";

        @Override
        public Response getPublicOnHosts(String url, Map<String, String> headers, int maxResponseBytes,
                                         String range, Set<String> allowedHosts) throws IOException {
            urls.add(url);
            String body;
            if (url.contains("premium")) {
                if (premiumStatus != 200) throw new HttpStatusException(premiumStatus);
                body = premium;
            } else {
                body = "{\"streams\":[{\"title\":\"~5 Mbps\",\"url\":\"https://leaf.highfly.dev/free.m3u8\"}]}";
            }
            return new Response(200, URI.create(url), "application/json",
                    Collections.emptyMap(), body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String post(int port, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return request(port, "POST /pair HTTP/1.1\r\nHost: tv\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: " + bytes.length + "\r\n\r\n" + body);
    }

    private static String request(int port, String raw) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) response.write(buffer, 0, count);
            return response.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class RecordingHttp extends TokenHttpClient {
        private final int status;
        private final String body;
        final List<String> urls = new ArrayList<>();
        Set<String> lastHosts = Collections.emptySet();
        int successAfter = Integer.MAX_VALUE;
        String successBody = "{}";

        RecordingHttp(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public Response getPublicOnHosts(
                String url,
                Map<String, String> headers,
                int maxResponseBytes,
                String range,
                Set<String> allowedHosts
        ) throws IOException {
            urls.add(url);
            lastHosts = allowedHosts;
            if (urls.size() > successAfter) {
                return new Response(200, URI.create(url), "application/json",
                        Collections.emptyMap(), successBody.getBytes(StandardCharsets.UTF_8));
            }
            if (status != 200) throw new HttpStatusException(status);
            return new Response(200, URI.create(url), "application/json",
                    Collections.emptyMap(), body.getBytes(StandardCharsets.UTF_8));
        }
    }
}

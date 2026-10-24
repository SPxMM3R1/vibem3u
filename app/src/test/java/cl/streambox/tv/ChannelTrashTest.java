package cl.streambox.tv;

import org.json.JSONObject;
import org.junit.Test;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ocultar y papelera desde la TV (0.5.90): pedido a GitHub y estado local. */
public final class ChannelTrashTest {
    @Test
    public void trashRequestCarriesOnlyTheEditorRowKeyAndAName() throws Exception {
        JSONObject request = GitHubChannelTrash.requestDocument(
                "provider:tvvoo:spain|vavoo_EUROSPORT%202%7Cgroup%3Aes", "EUROSPORT 2", "0.5.90", 0L);
        assertEquals(1, request.getInt("schema"));
        assertEquals("trash", request.getString("action"));
        assertEquals("provider:tvvoo:spain|vavoo_EUROSPORT%202%7Cgroup%3Aes", request.getString("rowKey"));
        assertEquals("EUROSPORT 2", request.getString("name"));
        assertEquals("1970-01-01T00:00:00Z", request.getString("requestedAt"));
        assertEquals("VibeM3U 0.5.90", request.getString("app"));
        assertEquals(6, request.length());
    }

    @Test
    public void requestFilesAreSortableAndUnique() {
        String first = GitHubChannelTrash.requestFileName(1_760_000_000_000L, 0xabc);
        String later = GitHubChannelTrash.requestFileName(1_760_000_001_000L, 0x001);
        assertTrue(first.matches("\\d{8}T\\d{6}Z-[0-9a-f]{6}\\.json"));
        assertTrue(first.compareTo(later) < 0);
    }

    @Test
    public void namesAreCleanedBeforeLeavingTheTv() {
        assertEquals("TVN  HD", GitHubChannelTrash.safeName("TVN\n\tHD"));
        assertEquals(120, GitHubChannelTrash.safeName(String.join("", Collections.nCopies(200, "a"))).length());
    }

    @Test
    public void visibilityEntriesSurviveSerializationAndIgnoreGarbage() {
        String text = ChannelVisibility.serialize(Arrays.asList(
                new ChannelVisibility.Entry("m3u:0104", "TVN", ChannelVisibility.HIDDEN, 10L),
                new ChannelVisibility.Entry("provider:tvvoo:x", "EUROSPORT 2", ChannelVisibility.TRASHED, 20L)));
        Map<String, ChannelVisibility.Entry> parsed = ChannelVisibility.parse(text);
        assertEquals(2, parsed.size());
        assertEquals("TVN", parsed.get("m3u:0104").name);
        assertEquals(ChannelVisibility.TRASHED, parsed.get("provider:tvvoo:x").state);
        assertTrue(ChannelVisibility.parse("{roto").isEmpty());
        assertTrue(ChannelVisibility.parse("[{\"key\":\"m3u:x\",\"state\":\"borrar\"}]").isEmpty());
    }

    @Test
    public void githubTokensMustLookLikeTokens() {
        assertTrue(GitHubCredentialStore.isPlausibleToken("gho_0123456789abcdefABCDEF0123456789abcd"));
        assertFalse(GitHubCredentialStore.isPlausibleToken("corto"));
        assertFalse(GitHubCredentialStore.isPlausibleToken("gho_0123456789 abcdefABCDEF0123456789"));
    }

    @Test
    public void deviceFlowRequestsOfflineAccessForRefreshableTokens() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"device_code\":\"device-code\",\"user_code\":\"ABCD-EFGH\","
                    + "\"expires_in\":900,\"interval\":5}"));

            github(server).requestDeviceCode();

            String form = server.takeRequest().getBody().readUtf8();
            assertTrue(form, form.matches("(?s).*scope=public_repo(?:\\+|%20)offline_access(?:&.*)?"));
        }
    }

    @Test
    public void deviceCodeExpiryIsNotMistakenForAccessTokenExpiry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"access_token\":\"gho_accessToken0123456789ABCDEFGHIJ\","
                    + "\"refresh_token\":\"ghr_refreshToken0123456789ABCDEFGHIJ\","
                    + "\"expires_in\":28800,\"refresh_token_expires_in\":15897600}"));
            GitHubChannelTrash github = github(server);
            long now = System.currentTimeMillis();
            GitHubChannelTrash.DeviceCode device = new GitHubChannelTrash.DeviceCode(
                    "device-code", "ABCD-EFGH", now + 15 * 60_000L, 5_000L);

            GitHubCredentialStore.Credentials credentials = github.pollCredentials(device, new long[]{5_000L});

            assertEquals("gho_accessToken0123456789ABCDEFGHIJ", credentials.accessToken);
            assertEquals("ghr_refreshToken0123456789ABCDEFGHIJ", credentials.refreshToken);
            assertTrue(credentials.accessExpiresAtMillis >= now + 28_000_000L);
            assertTrue(credentials.accessExpiresAtMillis <= now + 29_000_000L);
        }
    }

    @Test
    public void forbiddenResponsePreservesCredentialsAndDoesNotStartDeviceFlow() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"message\":\"Forbidden\"}"));
            AtomicReference<GitHubCredentialStore.Credentials> persisted = new AtomicReference<>();

            try {
                github(server).requestTrash(credentials("gho_accessToken0123456789ABCDEFGHIJ",
                                "ghr_refreshToken0123456789ABCDEFGHIJ"),
                        "row:1", "Canal", "0.6.32", persisted::set);
                throw new AssertionError("Un 403 debe informarse como permisos/límite, no como login vencido.");
            } catch (GitHubChannelTrash.PermissionDeniedException expected) {
                assertTrue(expected.getMessage().contains("Conservé la autorización"));
            }

            assertEquals(1, server.getRequestCount());
            assertEquals("Bearer gho_accessToken0123456789ABCDEFGHIJ",
                    server.takeRequest().getHeader("Authorization"));
            assertEquals(null, persisted.get());
        }
    }

    @Test
    public void notFoundResponseIsNotTreatedAsExpiredAuthorization() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}"));
            try {
                github(server).requestTrash(credentials("gho_accessToken0123456789ABCDEFGHIJ", null),
                        "row:1", "Canal", "0.6.32", ignored -> {});
                throw new AssertionError("Un 404 debe indicar recurso/ruta, no pedir autorización.");
            } catch (GitHubChannelTrash.ResourceUnavailableException expected) {
                assertTrue(expected.getMessage().contains("Conservé la autorización"));
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void expiredAccessTokenRefreshesAndPersistsRotatedPairBeforeRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"Bad credentials\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"access_token\":\"gho_newAccessToken0123456789ABCDEFGHI\","
                    + "\"refresh_token\":\"ghr_newRefreshToken0123456789ABCDEFGHI\","
                    + "\"expires_in\":28800,\"refresh_token_expires_in\":15897600}"));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            AtomicReference<GitHubCredentialStore.Credentials> persisted = new AtomicReference<>();

            GitHubCredentialStore.Credentials result = github(server).requestTrash(credentials(
                    "gho_accessToken0123456789ABCDEFGHIJ", "ghr_refreshToken0123456789ABCDEFGHIJ"),
                    "row:1", "Canal", "0.6.32", persisted::set);

            assertEquals("gho_newAccessToken0123456789ABCDEFGHI", result.accessToken);
            assertEquals("ghr_newRefreshToken0123456789ABCDEFGHI", result.refreshToken);
            assertEquals(result.accessToken, persisted.get().accessToken);
            assertEquals(result.refreshToken, persisted.get().refreshToken);
            assertEquals(3, server.getRequestCount());
            assertEquals("Bearer gho_accessToken0123456789ABCDEFGHIJ",
                    server.takeRequest().getHeader("Authorization"));
            String refreshBody = server.takeRequest().getBody().readUtf8();
            assertTrue(refreshBody.contains("refresh_token=ghr_refreshToken0123456789ABCDEFGHIJ"));
            assertTrue(refreshBody.contains("grant_type=refresh_token"));
            assertEquals("Bearer gho_newAccessToken0123456789ABCDEFGHI",
                    server.takeRequest().getHeader("Authorization"));
        }
    }

    @Test
    public void rejectedRefreshTokenRequiresRelinkOnlyAfterRefreshIsConfirmedInvalid() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"error\":\"bad_refresh_token\"}"));
            AtomicReference<GitHubCredentialStore.Credentials> persisted = new AtomicReference<>();
            try {
                github(server).requestTrash(credentials("gho_accessToken0123456789ABCDEFGHIJ",
                                "ghr_refreshToken0123456789ABCDEFGHIJ"),
                        "row:1", "Canal", "0.6.32", persisted::set);
                throw new AssertionError("Solo un refresh token rechazado debe exigir nueva vinculación.");
            } catch (GitHubChannelTrash.ReauthorizationRequiredException expected) {
                assertTrue(expected.getMessage().contains("vincular"));
            }
            assertEquals(2, server.getRequestCount());
            assertEquals(null, persisted.get());
        }
    }

    @Test
    public void incorrectOAuthClientConfigurationPreservesSavedCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"error\":\"incorrect_client_credentials\"}"));
            AtomicReference<GitHubCredentialStore.Credentials> persisted = new AtomicReference<>();
            try {
                github(server).requestTrash(credentials("gho_accessToken0123456789ABCDEFGHIJ",
                                "ghr_refreshToken0123456789ABCDEFGHIJ"),
                        "row:1", "Canal", "0.6.31", persisted::set);
                throw new AssertionError("Un Client ID incorrecto no significa que el usuario revocó su sesión.");
            } catch (GitHubChannelTrash.ReauthorizationRequiredException unexpected) {
                throw new AssertionError("No borrar credenciales por una configuración OAuth inválida.", unexpected);
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("Se conservó la sesión"));
            }
            assertEquals(2, server.getRequestCount());
            assertEquals(null, persisted.get());
        }
    }

    private static GitHubChannelTrash github(MockWebServer server) {
        String base = server.url("/").toString();
        return new GitHubChannelTrash(new OkHttpClient(), base + "device", base + "oauth", base + "contents/");
    }

    private static GitHubCredentialStore.Credentials credentials(String accessToken, String refreshToken) {
        return new GitHubCredentialStore.Credentials(accessToken, refreshToken, 0L);
    }
}

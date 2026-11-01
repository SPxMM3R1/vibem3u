package cl.streambox.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Papelera desde la TV (0.5.90).
 *
 * <p>La TV no edita el catálogo: deja un pedido pequeño en {@code data/app-requests/} de Lista
 * M3U. El workflow «Pedidos de la app» lo aplica con las mismas funciones del editor web
 * ({@code setRowState} y los documentos derivados), lo borra y relanza la actualización de
 * canales. Así la TV y el editor no pueden divergir.</p>
 *
 * <p>Vinculación: GitHub Device Flow con el Client ID público de la app OAuth de VibeM3U (el
 * mismo que usó el administrador de canales de la 0.5.1x).</p>
 */
final class GitHubChannelTrash {
    static final String REPOSITORY = "SPxMM3R1/lista-m3u";
    static final String VERIFICATION_URI = "https://github.com/login/device";
    // offline_access opts into expiring tokens with a rotatable refresh token; without it a
    // Device Flow response may contain only an access token that cannot be renewed by the app.
    static final String SCOPE = "public_repo offline_access";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String CONTENTS_URL =
            "https://api.github.com/repos/" + REPOSITORY + "/contents/data/app-requests/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** GitHub confirmó que el access token no es válido y no se pudo renovar. */
    static final class ReauthorizationRequiredException extends IOException {
        ReauthorizationRequiredException() {
            super("La autorización de GitHub venció o fue revocada; vuelve a vincular esta TV.");
        }

        ReauthorizationRequiredException(String message) {
            super(message);
        }
    }

    /** 401 antes de intentar la renovación: no implica que el refresh token también haya vencido. */
    private static final class InvalidAccessTokenException extends IOException {
        InvalidAccessTokenException() {
            super("GitHub rechazó el access token.");
        }
    }

    /** Un 403 suele ser alcance, permisos, política de la organización o límite; no borra credenciales. */
    static final class PermissionDeniedException extends IOException {
        PermissionDeniedException() {
            super("GitHub no autorizó la escritura (permisos, política del repositorio o límite temporal). "
                    + "Conservé la autorización guardada.");
        }
    }

    /** El repo/ruta no existe o GitHub no permite verla; no es evidencia de token vencido. */
    static final class ResourceUnavailableException extends IOException {
        ResourceUnavailableException() {
            super("GitHub no encontró el repositorio o la ruta de solicitudes. "
                    + "Conservé la autorización guardada.");
        }
    }

    interface CredentialPersister {
        void save(GitHubCredentialStore.Credentials credentials) throws IOException;
    }

    static final class DeviceCode {
        final String deviceCode;
        final String userCode;
        final long expiresAtMillis;
        final long intervalMillis;

        DeviceCode(String deviceCode, String userCode, long expiresAtMillis, long intervalMillis) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.expiresAtMillis = expiresAtMillis;
            this.intervalMillis = intervalMillis;
        }
    }

    private final OkHttpClient client;
    private final String deviceCodeUrl;
    private final String accessTokenUrl;
    private final String contentsUrl;

    GitHubChannelTrash() {
        this(SharedHttpClient.get().newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .build());
    }

    GitHubChannelTrash(OkHttpClient client) {
        this(client, DEVICE_CODE_URL, ACCESS_TOKEN_URL, CONTENTS_URL);
    }

    GitHubChannelTrash(OkHttpClient client, String deviceCodeUrl, String accessTokenUrl, String contentsUrl) {
        this.client = client;
        this.deviceCodeUrl = deviceCodeUrl;
        this.accessTokenUrl = accessTokenUrl;
        this.contentsUrl = contentsUrl;
    }

    static String clientId() {
        String value = BuildConfig.GITHUB_DEVICE_CLIENT_ID;
        return value == null ? "" : value.trim();
    }

    static boolean isConfigured() {
        return clientId().matches("[A-Za-z0-9._-]{8,64}");
    }

    DeviceCode requestDeviceCode() throws IOException {
        JSONObject response = postForm(deviceCodeUrl, new FormBody.Builder()
                .add("client_id", clientId())
                .add("scope", SCOPE)
                .build());
        String deviceCode = response.optString("device_code", "").trim();
        String userCode = response.optString("user_code", "").trim();
        long expiresIn = response.optLong("expires_in", 0L);
        if (deviceCode.isEmpty() || !userCode.matches("[A-Z0-9-]{4,16}") || expiresIn <= 0L) {
            throw new IOException("GitHub no entregó un código de vinculación.");
        }
        return new DeviceCode(deviceCode, userCode, System.currentTimeMillis() + expiresIn * 1000L,
                Math.max(5L, response.optLong("interval", 5L)) * 1000L);
    }

    /**
     * Una consulta de la autorización: devuelve las credenciales, null si todavía no se autoriza,
     * o lanza IOException si se canceló o venció. {@code slowDown} recibe el nuevo intervalo.
     */
    GitHubCredentialStore.Credentials pollCredentials(DeviceCode code, long[] intervalMillis) throws IOException {
        JSONObject response = postForm(accessTokenUrl, new FormBody.Builder()
                .add("client_id", clientId())
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build());
        String token = response.optString("access_token", "").trim();
        if (!token.isEmpty()) return credentialsFromOAuthResponse(response, System.currentTimeMillis());
        String error = response.optString("error", "");
        if ("authorization_pending".equals(error)) return null;
        if ("slow_down".equals(error)) {
            intervalMillis[0] = Math.max(intervalMillis[0] + 5_000L,
                    response.optLong("interval", 10L) * 1000L);
            return null;
        }
        if ("access_denied".equals(error)) throw new IOException("La vinculación se canceló en GitHub.");
        if ("expired_token".equals(error)) throw new IOException("El código de GitHub venció.");
        throw new IOException("GitHub rechazó la vinculación.");
    }

    /** Intercambia el refresh token de un access token vencido; GitHub rota ambos tokens. */
    GitHubCredentialStore.Credentials refreshAccessToken(String refreshToken) throws IOException {
        if (!GitHubCredentialStore.isPlausibleToken(refreshToken)) {
            throw new ReauthorizationRequiredException("GitHub ya no acepta la renovación; vuelve a vincular esta TV.");
        }
        JSONObject response = postForm(accessTokenUrl, new FormBody.Builder()
                .add("client_id", clientId())
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build());
        String token = response.optString("access_token", "").trim();
        if (token.isEmpty()) {
            String error = response.optString("error", "");
            if ("bad_refresh_token".equals(error)) {
                throw new ReauthorizationRequiredException(
                        "GitHub ya no acepta la renovación; vuelve a vincular esta TV.");
            }
            throw new IOException("GitHub no pudo renovar la autorización. Se conservó la sesión guardada.");
        }
        GitHubCredentialStore.Credentials credentials = credentialsFromOAuthResponse(response,
                System.currentTimeMillis());
        if (AppStrings.isBlank(credentials.refreshToken)) {
            throw new IOException("GitHub renovó el acceso sin entregar el siguiente refresh token.");
        }
        return credentials;
    }

    /** Intenta el pedido; ante 401 renueva y persiste el par rotado antes del segundo intento. */
    GitHubCredentialStore.Credentials requestTrash(GitHubCredentialStore.Credentials credentials,
            String rowKey, String channelName, String appVersion, CredentialPersister persister)
            throws IOException {
        if (credentials == null || !GitHubCredentialStore.isPlausibleToken(credentials.accessToken)) {
            throw new ReauthorizationRequiredException();
        }
        try {
            requestTrash(credentials.accessToken, rowKey, channelName, appVersion);
            return credentials;
        } catch (InvalidAccessTokenException invalidAccessToken) {
            if (AppStrings.isBlank(credentials.refreshToken)) throw new ReauthorizationRequiredException();
            GitHubCredentialStore.Credentials refreshed = refreshAccessToken(credentials.refreshToken);
            if (persister == null) throw new IOException("No se pudo guardar la renovación de GitHub.");
            // GitHub invalida el refresh token anterior al rotarlo: guardar sincrónicamente antes
            // del segundo pedido evita perder la nueva sesión si la app se interrumpe.
            persister.save(refreshed);
            try {
                requestTrash(refreshed.accessToken, rowKey, channelName, appVersion);
                return refreshed;
            } catch (InvalidAccessTokenException secondRejection) {
                throw new ReauthorizationRequiredException();
            }
        }
    }

    static GitHubCredentialStore.Credentials credentialsFromOAuthResponse(JSONObject response, long nowMillis)
            throws IOException {
        String accessToken = response.optString("access_token", "").trim();
        if (!GitHubCredentialStore.isPlausibleToken(accessToken)) {
            throw new IOException("GitHub entregó una autorización no válida.");
        }
        String refreshToken = response.optString("refresh_token", "").trim();
        if (refreshToken.isEmpty()) refreshToken = null;
        else if (!GitHubCredentialStore.isPlausibleToken(refreshToken)) {
            throw new IOException("GitHub entregó una renovación no válida.");
        }
        long expiresInSeconds = response.optLong("expires_in", 0L);
        long expiresAtMillis = 0L;
        if (expiresInSeconds > 0L) {
            long maxSeconds = (Long.MAX_VALUE - nowMillis) / 1000L;
            expiresAtMillis = expiresInSeconds > maxSeconds
                    ? Long.MAX_VALUE : nowMillis + expiresInSeconds * 1000L;
        }
        return new GitHubCredentialStore.Credentials(accessToken, refreshToken, expiresAtMillis);
    }

    /** Deja el pedido de papelera para la fila del editor {@code rowKey}. */
    void requestTrash(String token, String rowKey, String channelName, String appVersion)
            throws IOException {
        if (AppStrings.isBlank(rowKey) || rowKey.length() > 600) throw new IOException("Canal sin fila en el editor.");
        String fileName = requestFileName(System.currentTimeMillis(), new SecureRandom().nextInt(1 << 24));
        String payload;
        try {
            payload = requestDocument(rowKey, channelName, appVersion, System.currentTimeMillis()).toString(2) + "\n";
        } catch (JSONException error) {
            throw new IOException("No se pudo armar el pedido.", error);
        }
        JSONObject body;
        try {
            body = new JSONObject()
                    .put("message", "Papelera desde la app: " + safeName(channelName))
                    .put("content", Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)))
                    .put("branch", "main");
        } catch (JSONException error) {
            throw new IOException("No se pudo armar el pedido.", error);
        }
        Request request = new Request.Builder()
                .url(contentsUrl + fileName)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .put(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 401) throw new InvalidAccessTokenException();
            if (response.code() == 403) throw new PermissionDeniedException();
            if (response.code() == 404) throw new ResourceUnavailableException();
            if (response.code() != 201 && response.code() != 200) {
                throw new IOException("GitHub respondió HTTP " + response.code() + ".");
            }
        }
    }

    static String requestFileName(long nowMillis, int random) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(nowMillis)) + "-" + String.format(Locale.ROOT, "%06x", random & 0xFFFFFF) + ".json";
    }

    static JSONObject requestDocument(String rowKey, String channelName, String appVersion, long nowMillis)
            throws JSONException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return new JSONObject()
                .put("schema", 1)
                .put("action", "trash")
                .put("rowKey", rowKey)
                .put("name", safeName(channelName))
                .put("requestedAt", format.format(new Date(nowMillis)))
                .put("app", "VibeM3U " + (appVersion == null ? "" : appVersion));
    }

    static String safeName(String name) {
        String value = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private JSONObject postForm(String url, FormBody form) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(form)
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("GitHub no respondió.");
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw new IOException("Respuesta de GitHub demasiado grande.");
            byte[] bytes = body.bytes();
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Respuesta de GitHub demasiado grande.");
            try {
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException invalid) {
                throw new IOException("GitHub devolvió una respuesta no válida.");
            }
        }
    }
}

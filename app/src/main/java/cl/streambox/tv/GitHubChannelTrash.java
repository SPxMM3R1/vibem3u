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
    static final String SCOPE = "public_repo";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String CONTENTS_URL =
            "https://api.github.com/repos/" + REPOSITORY + "/contents/data/app-requests/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** El token ya no sirve (revocado o sin permiso): hay que vincular de nuevo. */
    static final class UnauthorizedException extends IOException {
        UnauthorizedException() {
            super("GitHub rechazó el acceso de esta TV.");
        }
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

    GitHubChannelTrash() {
        this(SharedHttpClient.get().newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .build());
    }

    GitHubChannelTrash(OkHttpClient client) {
        this.client = client;
    }

    static String clientId() {
        String value = BuildConfig.GITHUB_DEVICE_CLIENT_ID;
        return value == null ? "" : value.trim();
    }

    static boolean isConfigured() {
        return clientId().matches("[A-Za-z0-9._-]{8,64}");
    }

    DeviceCode requestDeviceCode() throws IOException {
        JSONObject response = postForm(DEVICE_CODE_URL, new FormBody.Builder()
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
     * Una consulta de la autorización: devuelve el token, null si todavía no se autoriza, o
     * lanza IOException si se canceló o venció. {@code slowDown} recibe el nuevo intervalo.
     */
    String pollToken(DeviceCode code, long[] intervalMillis) throws IOException {
        JSONObject response = postForm(ACCESS_TOKEN_URL, new FormBody.Builder()
                .add("client_id", clientId())
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build());
        String token = response.optString("access_token", "").trim();
        if (!token.isEmpty()) return token;
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
                    .put("content", android.util.Base64.encodeToString(
                            payload.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP))
                    .put("branch", "main");
        } catch (JSONException error) {
            throw new IOException("No se pudo armar el pedido.", error);
        }
        Request request = new Request.Builder()
                .url(CONTENTS_URL + fileName)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .put(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403 || response.code() == 404) {
                throw new UnauthorizedException();
            }
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

package cl.streambox.tv;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GitHub OAuth Device Flow used by Android TV, where typing a long token is
 * inconvenient. The client id is public configuration; access and refresh
 * tokens are returned only to the caller and must be stored with Keystore.
 */
public final class GitHubDeviceAuthorization {
    public static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    public static final String ACCESS_TOKEN_URL =
            "https://github.com/login/oauth/access_token";
    public static final String VERIFICATION_URI = "https://github.com/login/device";
    public static final String OAUTH_SCOPE = "public_repo offline_access";

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Set<String> ALLOWED_HOSTS = Collections.singleton("github.com");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "vibem3u-github-device-auth");
        thread.setDaemon(true);
        return thread;
    });

    private GitHubDeviceAuthorization() {}

    /** Returns the public client id supplied by the release environment. */
    public static String configuredClientId() {
        return BuildConfig.GITHUB_DEVICE_CLIENT_ID == null
                ? ""
                : BuildConfig.GITHUB_DEVICE_CLIENT_ID.trim();
    }

    public static boolean isConfigured() {
        return !AppStrings.isBlank(configuredClientId());
    }

    /** Starts one cancellable device authorization session. */
    public static Handle begin(String clientId, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        Handle handle = new Handle();
        handle.future = EXECUTOR.submit(() -> run(clientId, callback, handle));
        return handle;
    }

    /** Refreshes an expiring OAuth access token outside the UI thread. */
    public static TokenPair refreshAccessToken(String clientId, String refreshToken)
            throws IOException {
        validateClientId(clientId);
        if (AppStrings.isBlank(refreshToken)) {
            throw new IOException("No hay refresh token de GitHub.");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId.trim());
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken.trim());
        return parseTokenResponse(postForm(ACCESS_TOKEN_URL, form));
    }

    private static void run(String clientId, Callback callback, Handle handle) {
        try {
            validateClientId(clientId);
            DeviceCode device = requestDeviceCode(clientId);
            if (handle.isCancelled()) return;
            callback.onDeviceCode(device);

            long deadline = device.getExpiresAtMillis();
            long intervalMillis = device.getIntervalSeconds() * 1000L;
            while (!handle.isCancelled() && System.currentTimeMillis() < deadline) {
                sleep(intervalMillis, handle);
                if (handle.isCancelled()) return;
                callback.onStatus("Esperando autorización de GitHub…");

                Map<String, String> form = new LinkedHashMap<>();
                form.put("client_id", clientId.trim());
                form.put("device_code", device.getDeviceCode());
                form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                JSONObject response = postForm(ACCESS_TOKEN_URL, form);
                String accessToken = response.optString("access_token", "").trim();
                if (!accessToken.isEmpty()) {
                    callback.onSuccess(parseTokenResponse(response));
                    return;
                }

                String error = response.optString("error", "").trim();
                if ("authorization_pending".equals(error)) continue;
                if ("slow_down".equals(error)) {
                    intervalMillis = Math.max(intervalMillis + 5_000L,
                            response.optLong("interval", device.getIntervalSeconds()) * 1000L);
                    continue;
                }
                if ("expired_token".equals(error) || "token_expired".equals(error)) {
                    throw new IOException("El código de GitHub expiró.");
                }
                if ("access_denied".equals(error)) {
                    throw new IOException("La autorización de GitHub fue cancelada.");
                }
                throw new IOException("GitHub rechazó la autorización.");
            }
            if (!handle.isCancelled()) throw new IOException("El código de GitHub expiró.");
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!handle.isCancelled()) callback.onError(safeMessage(error));
        }
    }

    private static DeviceCode requestDeviceCode(String clientId) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId.trim());
        form.put("scope", OAUTH_SCOPE);
        return parseDeviceCode(postForm(DEVICE_CODE_URL, form));
    }

    private static JSONObject postForm(String url, Map<String, String> form) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "VibeM3U-Android-TV");
        TokenHttpClient.Response response = new TokenHttpClient(8_000, 20_000)
                .postFormOnHosts(url, headers, form, MAX_RESPONSE_BYTES, ALLOWED_HOSTS);
        try {
            return new JSONObject(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IOException("GitHub devolvió una respuesta no válida.", error);
        }
    }

    static DeviceCode parseDeviceCode(JSONObject response) throws IOException {
        if (response == null) throw new IOException("GitHub no entregó un código.");
        String deviceCode = response.optString("device_code", "").trim();
        String userCode = response.optString("user_code", "").trim();
        String verificationUri = response.optString("verification_uri", VERIFICATION_URI).trim();
        long expiresIn = response.optLong("expires_in", 0L);
        long interval = response.optLong("interval", 5L);
        if (deviceCode.isEmpty() || userCode.isEmpty() || expiresIn <= 0L) {
            throw new IOException("GitHub no entregó un código de autorización completo.");
        }
        if (!VERIFICATION_URI.equals(verificationUri)) verificationUri = VERIFICATION_URI;
        return new DeviceCode(
                deviceCode,
                userCode,
                verificationUri,
                System.currentTimeMillis() + expiresIn * 1000L,
                Math.max(5L, interval)
        );
    }

    static TokenPair parseTokenResponse(JSONObject response) throws IOException {
        if (response == null) throw new IOException("GitHub no entregó un token.");
        String accessToken = response.optString("access_token", "").trim();
        if (accessToken.isEmpty()) throw new IOException("GitHub no entregó un token de acceso.");
        String refreshToken = response.optString("refresh_token", "").trim();
        long expiresIn = response.optLong("expires_in", 0L);
        long expiresAt = expiresIn <= 0L
                ? 0L
                : System.currentTimeMillis() + expiresIn * 1000L;
        return new TokenPair(accessToken, refreshToken, expiresAt);
    }

    private static void validateClientId(String clientId) throws IOException {
        if (AppStrings.isBlank(clientId) || clientId.length() > 256
                || containsControlCharacter(clientId)) {
            throw new IOException("Esta compilación no tiene un Client ID de GitHub configurado.");
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static void sleep(long millis, Handle handle) throws InterruptedException {
        long remaining = Math.max(1L, millis);
        while (remaining > 0L && !handle.isCancelled()) {
            long start = System.currentTimeMillis();
            Thread.sleep(Math.min(remaining, 1_000L));
            remaining -= Math.max(1L, System.currentTimeMillis() - start);
        }
    }

    private static String safeMessage(Exception error) {
        if (error instanceof TokenHttpClient.HttpStatusException) {
            return "GitHub respondió HTTP "
                    + ((TokenHttpClient.HttpStatusException) error).getStatusCode() + ".";
        }
        String message = error.getMessage();
        return AppStrings.isBlank(message) ? "No se pudo autorizar GitHub." : message;
    }

    public interface Callback {
        void onDeviceCode(DeviceCode deviceCode);
        void onStatus(String status);
        void onSuccess(TokenPair tokens);
        void onError(String message);
    }

    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Future<?> future;

        public void cancel() {
            cancelled.set(true);
            Future<?> current = future;
            if (current != null) current.cancel(true);
        }

        boolean isCancelled() { return cancelled.get(); }
    }

    public static final class DeviceCode {
        private final String deviceCode;
        private final String userCode;
        private final String verificationUri;
        private final long expiresAtMillis;
        private final long intervalSeconds;

        DeviceCode(
                String deviceCode,
                String userCode,
                String verificationUri,
                long expiresAtMillis,
                long intervalSeconds
        ) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresAtMillis = expiresAtMillis;
            this.intervalSeconds = intervalSeconds;
        }

        String getDeviceCode() { return deviceCode; }
        public String getUserCode() { return userCode; }
        public String getVerificationUri() { return verificationUri; }
        public long getExpiresAtMillis() { return expiresAtMillis; }
        public long getIntervalSeconds() { return intervalSeconds; }
    }

    public static final class TokenPair {
        private final String accessToken;
        private final String refreshToken;
        private final long accessExpiresAtMillis;

        TokenPair(String accessToken, String refreshToken, long accessExpiresAtMillis) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.accessExpiresAtMillis = accessExpiresAtMillis;
        }

        public String getAccessToken() { return accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public long getAccessExpiresAtMillis() { return accessExpiresAtMillis; }
    }
}

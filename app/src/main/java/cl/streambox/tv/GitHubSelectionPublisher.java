package cl.streambox.tv;

import android.content.Context;

import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Publishes the token-free app selection through the GitHub Contents API. */
public final class GitHubSelectionPublisher {
    public static final long RETRY_COOLDOWN_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final String OWNER = "SPxMM3R1";
    private static final String REPOSITORY = "lista-m3u";
    private static final String BRANCH = "main";
    private static final String PATH = "data/vibem3u-selection.json";
    private static final URI CONTENT_URI = URI.create(
            "https://api.github.com/repos/" + OWNER + "/" + REPOSITORY
                    + "/contents/" + PATH
    );
    private static final Set<String> ALLOWED_HOSTS = Collections.singleton("api.github.com");
    private static final Object QUEUE_LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vibem3u-github-selection");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean queued;

    private GitHubSelectionPublisher() {}

    /** Queues a best-effort automatic publication and never blocks playback/UI. */
    public static void enqueue(Context context, String reason) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        synchronized (QUEUE_LOCK) {
            if (queued) return;
            queued = true;
        }
        EXECUTOR.execute(() -> {
            try {
                publishNow(application, false);
            } finally {
                synchronized (QUEUE_LOCK) {
                    queued = false;
                }
            }
        });
    }

    public static void publishAsync(
            Context context,
            boolean force,
            Listener listener
    ) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Result result = publishNow(application, force);
            if (listener != null) listener.onComplete(result);
        });
    }

    /** Performs one bounded Contents API update. Call it outside the UI thread. */
    public static Result publishNow(Context context, boolean force) {
        if (context == null) return Result.failed("Contexto inválido.");
        GitHubPublicationPreferences preferences = new GitHubPublicationPreferences(context);
        if (!force && !preferences.isAutoPublishEnabled()) {
            return Result.skipped("Publicación automática desactivada.");
        }

        String token;
        try {
            token = usableToken(preferences);
        } catch (Exception error) {
            preferences.markFailure("Vuelve a vincular GitHub desde Opciones.");
            return Result.failed("La autorización de GitHub expiró.");
        }
        if (AppStrings.isBlank(token)) {
            return Result.needsConfiguration("Configura un token de GitHub.");
        }

        AppSelectionManifest.Snapshot snapshot;
        try {
            snapshot = AppSelectionManifest.build(context);
        } catch (RuntimeException error) {
            preferences.markFailure("No se pudo construir la selección.");
            return Result.failed("No se pudo construir la selección.");
        }

        long now = System.currentTimeMillis();
        if (!preferences.shouldAttempt(snapshot.getSignature(), now, force)) {
            return Result.skipped("La selección ya está publicada.");
        }
        preferences.markAttempt(snapshot.getSignature(), now);

        try {
            TokenHttpClient httpClient = new TokenHttpClient(8_000, 20_000);
            String sha = readExistingSha(httpClient, token);
            JSONObject request = new JSONObject();
            request.put("message", "chore(vibem3u): sync app channel selection");
            request.put(
                    "content",
                    Base64.getEncoder().encodeToString(
                            snapshot.getJson().getBytes(StandardCharsets.UTF_8)
                    )
            );
            request.put("branch", BRANCH);
            if (!AppStrings.isBlank(sha)) request.put("sha", sha);

            httpClient.putJsonOnHosts(
                    CONTENT_URI.toString(),
                    githubHeaders(token),
                    request.toString(),
                    MAX_RESPONSE_BYTES,
                    ALLOWED_HOSTS
            );
            preferences.markPublished(snapshot.getSignature());
            return Result.published("Selección publicada en GitHub.");
        } catch (TokenHttpClient.HttpStatusException error) {
            String message = "GitHub respondió HTTP " + error.getStatusCode() + ".";
            preferences.markFailure(message);
            return Result.failed(message);
        } catch (Exception error) {
            // Never expose an HTTP request, Authorization header or response
            // body in the UI/status preference.
            preferences.markFailure("No se pudo publicar en GitHub.");
            return Result.failed("No se pudo publicar en GitHub.");
        }
    }

    private static String readExistingSha(TokenHttpClient httpClient, String token)
            throws Exception {
        String url = CONTENT_URI + "?ref=" + BRANCH;
        try {
            TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                    url,
                    githubHeaders(token),
                    MAX_RESPONSE_BYTES,
                    null,
                    ALLOWED_HOSTS
            );
            JSONObject body = new JSONObject(new String(
                    response.getBody(),
                    StandardCharsets.UTF_8
            ));
            return body.optString("sha", "").trim();
        } catch (TokenHttpClient.HttpStatusException error) {
            if (error.getStatusCode() == 404) return "";
            throw error;
        }
    }

    private static String usableToken(GitHubPublicationPreferences preferences)
            throws Exception {
        String token = preferences.getToken();
        if (!preferences.isAccessTokenExpired(System.currentTimeMillis() + 30_000L)) {
            return token;
        }
        String refresh = preferences.getRefreshToken();
        if (AppStrings.isBlank(refresh)) {
            throw new java.io.IOException("No hay refresh token de GitHub.");
        }
        GitHubDeviceAuthorization.TokenPair refreshed =
                GitHubDeviceAuthorization.refreshAccessToken(
                        GitHubDeviceAuthorization.configuredClientId(),
                        refresh
                );
        String refreshedRefresh = AppStrings.isBlank(refreshed.getRefreshToken())
                ? refresh
                : refreshed.getRefreshToken();
        preferences.setOAuthTokens(
                refreshed.getAccessToken(),
                refreshedRefresh,
                refreshed.getAccessExpiresAtMillis()
        );
        return refreshed.getAccessToken();
    }

    private static Map<String, String> githubHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "VibeM3U-Android-TV");
        headers.put("Authorization", "Bearer " + token);
        return Collections.unmodifiableMap(headers);
    }

    public interface Listener {
        void onComplete(Result result);
    }

    public static final class Result {
        public enum Outcome { PUBLISHED, SKIPPED, NEEDS_CONFIGURATION, FAILED }

        private final Outcome outcome;
        private final String message;

        private Result(Outcome outcome, String message) {
            this.outcome = outcome;
            this.message = message == null ? "" : message;
        }

        static Result published(String message) {
            return new Result(Outcome.PUBLISHED, message);
        }

        static Result skipped(String message) {
            return new Result(Outcome.SKIPPED, message);
        }

        static Result needsConfiguration(String message) {
            return new Result(Outcome.NEEDS_CONFIGURATION, message);
        }

        static Result failed(String message) {
            return new Result(Outcome.FAILED, message);
        }

        public Outcome getOutcome() { return outcome; }
        public String getMessage() { return message; }
        public boolean isPublished() { return outcome == Outcome.PUBLISHED; }
    }
}

package cl.streambox.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Small cancellable HTTP client for provider pages and token APIs. */
public class TokenHttpClient {
    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 12_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PUBLIC_REDIRECTS = 4;
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public TokenHttpClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * Kept non-final and public so provider tests and integrations can supply
     * a small-timeout/subclassed client without changing resolver signatures.
     */
    public TokenHttpClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = Math.max(1, connectTimeoutMs);
        this.readTimeoutMs = Math.max(1, readTimeoutMs);
    }

    public String getText(String url, Map<String, String> headers) throws IOException {
        return new String(
                get(url, headers, MAX_RESPONSE_BYTES, null).getBody(),
                StandardCharsets.UTF_8
        );
    }

    public Response get(
            String url,
            Map<String, String> headers,
            int maxResponseBytes,
            String range
    ) throws IOException {
        return getInternal(url, headers, maxResponseBytes, range, false);
    }

    /** GET whose initial URL and every redirect must remain on the public Internet. */
    public Response getPublic(
            String url,
            Map<String, String> headers,
            int maxResponseBytes,
            String range
    ) throws IOException {
        return getPublicInternal(url, headers, maxResponseBytes, range, false, null);
    }

    /**
     * GET whose initial URL and every redirect must remain on a known host.
     * This is used for credential-bearing provider endpoints. The allowlist is
     * deliberately checked before every connection, including the final URL.
     */
    public Response getPublicOnHosts(
            String url,
            Map<String, String> headers,
            int maxResponseBytes,
            String range,
            Set<String> allowedHosts
    ) throws IOException {
        return getPublicInternal(url, headers, maxResponseBytes, range, false, allowedHosts);
    }

    /** Reads at most {@code maxResponseBytes}; useful for a media probe. */
    public Response getPrefix(
            String url,
            Map<String, String> headers,
            int maxResponseBytes,
            String range
    ) throws IOException {
        return getInternal(url, headers, maxResponseBytes, range, true);
    }

    /** Prefix GET with the same public-network policy applied to every redirect. */
    public Response getPublicPrefix(
            String url,
            Map<String, String> headers,
            int maxResponseBytes,
            String range
    ) throws IOException {
        return getPublicInternal(url, headers, maxResponseBytes, range, true, null);
    }

    private Response getInternal(
            String url,
            Map<String, String> headers,
            int maxResponseBytes,
            String range,
            boolean prefixOnly
    ) throws IOException {
        URI uri = parseHttpUri(url);
        ResolutionContext context = ResolutionContext.current();
        check(context);
        OkHttpClient client = clientFor(context, true);
        Request request = getRequest(uri.toString(), headers, range);
        return execute(client, request, maxResponseBytes, prefixOnly, context);
    }

    private Response getPublicInternal(
            String url,
            Map<String, String> headers,
            int maxResponseBytes,
            String range,
            boolean prefixOnly,
            Set<String> allowedHosts
    ) throws IOException {
        URI currentUri = parseHttpUri(url);
        ResolutionContext context = ResolutionContext.current();
        for (int redirects = 0; redirects <= MAX_PUBLIC_REDIRECTS; redirects++) {
            check(context);
            PublicStreamPolicy.requirePublicHttp(currentUri);
            requireAllowedHost(currentUri, allowedHosts);
            OkHttpClient client = clientFor(context, false);
            Request request = getRequest(currentUri.toString(), headers, range);
            Call call = client.newCall(request);
            ResolutionContext.Registration registration = register(context, call);
            okhttp3.Response networkResponse = null;
            try {
                networkResponse = call.execute();
                check(context);
                int responseCode = networkResponse.code();
                if (isRedirect(responseCode)) {
                    if (redirects >= MAX_PUBLIC_REDIRECTS) {
                        throw new IOException("Demasiadas redirecciones del stream.");
                    }
                    String location = networkResponse.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirección del stream sin destino.");
                    }
                    try {
                        currentUri = currentUri.resolve(location);
                    } catch (IllegalArgumentException error) {
                        throw new IOException("Redirección del stream no válida.", error);
                    }
                    continue;
                }
                if (responseCode < 200 || responseCode >= 300) {
                    throw new HttpStatusException(responseCode);
                }
                return readResponse(networkResponse, maxResponseBytes, prefixOnly, context);
            } catch (java.io.InterruptedIOException error) {
                if (context != null && context.isCancelled()) {
                    throw new IOException("Solicitud cancelada.", error);
                }
                throw error;
            } finally {
                if (networkResponse != null) networkResponse.close();
                if (registration != null) registration.close();
            }
        }
        throw new IOException("Demasiadas redirecciones del stream.");
    }

    private Response execute(
            OkHttpClient client,
            Request request,
            int maxResponseBytes,
            boolean prefixOnly,
            ResolutionContext context
    ) throws IOException {
        Call call = client.newCall(request);
        ResolutionContext.Registration registration = register(context, call);
        okhttp3.Response response = null;
        try {
            response = call.execute();
            check(context);
            int responseCode = response.code();
            if (responseCode < 200 || responseCode >= 300) {
                throw new HttpStatusException(responseCode);
            }
            return readResponse(response, maxResponseBytes, prefixOnly, context);
        } catch (java.io.InterruptedIOException error) {
            if (context != null && context.isCancelled()) {
                throw new IOException("Solicitud cancelada.", error);
            }
            throw error;
        } finally {
            if (response != null) response.close();
            if (registration != null) registration.close();
        }
    }

    private Response readResponse(
            okhttp3.Response response,
            int maxResponseBytes,
            boolean prefixOnly,
            ResolutionContext context
    ) throws IOException {
        byte[] body = new byte[0];
        if (response.body() != null) {
            try (InputStream input = response.body().byteStream()) {
                body = prefixOnly
                        ? readPrefix(input, Math.max(1, maxResponseBytes), context)
                        : readLimited(input, Math.max(1, maxResponseBytes), context);
            }
        }
        URI finalUri;
        try {
            finalUri = response.request().url().uri();
        } catch (RuntimeException error) {
            throw new IOException("El servidor devolvió una URL inválida.", error);
        }
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        for (String name : response.headers().names()) {
            String value = response.header(name);
            if (value != null) responseHeaders.put(name, value);
        }
        return new Response(
                response.code(),
                finalUri,
                response.header("Content-Type"),
                responseHeaders,
                body
        );
    }

    private OkHttpClient clientFor(ResolutionContext context, boolean followRedirects) {
        long remaining = context == null ? readTimeoutMs : Math.max(1L, context.remainingMillis());
        int connect = (int) Math.min((long) connectTimeoutMs, remaining);
        int read = (int) Math.min((long) readTimeoutMs, remaining);
        return SharedHttpClient.forResolution(context, connect, read, read, followRedirects);
    }

    private static Request getRequest(
            String url,
            Map<String, String> headers,
            String range
    ) throws IOException {
        try {
            Request.Builder builder = new Request.Builder().url(url).get();
            addHeaders(builder, headers);
            if (range != null && !range.isBlank()) builder.header("Range", range);
            if (headers == null || !containsHeader(headers, "User-Agent")) {
                builder.header("User-Agent", BROWSER_USER_AGENT);
            }
            return builder.build();
        } catch (IllegalArgumentException error) {
            throw new IOException("URL no válida.", error);
        }
    }

    private static void addHeaders(Request.Builder builder, Map<String, String> headers)
            throws IOException {
        builder.header("Cache-Control", "no-store, no-cache, max-age=0");
        builder.header("Pragma", "no-cache");
        builder.header("Expires", "0");
        if (headers == null) return;
        try {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("Cabecera HTTP no válida.", error);
        }
    }

    private static boolean containsHeader(Map<String, String> headers, String name) {
        if (headers == null || name == null) return false;
        for (String key : headers.keySet()) {
            if (key != null && name.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static ResolutionContext.Registration register(
            ResolutionContext context,
            Call call
    ) {
        return context == null ? null : context.register(call);
    }

    private static void check(ResolutionContext context) throws IOException {
        if (context != null) context.check();
        else if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Solicitud cancelada.");
        }
    }

    private static URI parseHttpUri(String value) throws IOException {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("URL no válida.", error);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IOException("URL no válida.");
        }
        return uri;
    }

    private static void requireAllowedHost(URI uri, Set<String> allowedHosts)
            throws IOException {
        if (allowedHosts == null) return;
        String host = uri == null || uri.getHost() == null
                ? ""
                : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = false;
        for (String candidate : allowedHosts) {
            if (candidate != null && candidate.trim().equalsIgnoreCase(host)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) throw new IOException("El host del endpoint no está permitido.");
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    public static String buildUrl(String baseUrl, Map<String, String> parameters)
            throws IOException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IOException("URL no válida.");
        }
        if (parameters == null || parameters.isEmpty()) {
            return baseUrl;
        }

        StringBuilder result = new StringBuilder(baseUrl);
        result.append(baseUrl.indexOf('?') >= 0 ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (parameter.getKey() == null || parameter.getValue() == null) {
                continue;
            }
            if (!first) result.append('&');
            first = false;
            result.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8.name()));
            result.append('=');
            result.append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8.name()));
        }
        return result.toString();
    }

    private static byte[] readLimited(
            InputStream input,
            int maximumBytes,
            ResolutionContext context
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            check(context);
            total += count;
            if (total > maximumBytes) {
                throw new IOException("Respuesta demasiado grande.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static byte[] readPrefix(InputStream input, int maximumBytes) throws IOException {
        return readPrefix(input, maximumBytes, ResolutionContext.current());
    }

    private static byte[] readPrefix(
            InputStream input,
            int maximumBytes,
            ResolutionContext context
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(16 * 1024, Math.max(1, maximumBytes))
        );
        int bufferSize = Math.min(16 * 1024, Math.max(1, maximumBytes));
        byte[] buffer = new byte[bufferSize];
        int remaining = Math.max(1, maximumBytes);
        while (remaining > 0) {
            check(context);
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count == -1) break;
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return output.toByteArray();
    }

    public String postJsonText(
            String url,
            Map<String, String> headers,
            String json,
            int maxResponseBytes
    ) throws IOException {
        return new String(
                postJson(url, headers, json, maxResponseBytes).getBody(),
                StandardCharsets.UTF_8
        );
    }

    public Response postJson(
            String url,
            Map<String, String> headers,
            String json,
            int maxResponseBytes
    ) throws IOException {
        URI uri = parseHttpUri(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("URL HTTPS no válida.");
        }
        byte[] requestBody = (json == null ? "{}" : json)
                .getBytes(StandardCharsets.UTF_8);
        if (requestBody.length > 256 * 1024) {
            throw new IOException("Solicitud demasiado grande.");
        }
        ResolutionContext context = ResolutionContext.current();
        check(context);
        OkHttpClient client = clientFor(context, false);
        Request request;
        try {
            Request.Builder builder = new Request.Builder()
                    .url(uri.toString())
                    .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE));
            addHeaders(builder, headers);
            builder.header("Content-Type", "application/json; charset=utf-8");
            if (headers == null || !containsHeader(headers, "User-Agent")) {
                builder.header("User-Agent", BROWSER_USER_AGENT);
            }
            request = builder.build();
        } catch (IllegalArgumentException error) {
            throw new IOException("URL o cabecera no válida.", error);
        }
        try {
            return execute(client, request, maxResponseBytes, false, context);
        } finally {
            java.util.Arrays.fill(requestBody, (byte) 0);
        }
    }

    public static final class Response {
        private final int statusCode;
        private final URI finalUri;
        private final String contentType;
        private final Map<String, String> headers;
        private final byte[] body;

        Response(
                int statusCode,
                URI finalUri,
                String contentType,
                Map<String, String> headers,
                byte[] body
        ) {
            this.statusCode = statusCode;
            this.finalUri = finalUri;
            this.contentType = contentType == null ? "" : contentType;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                    headers == null ? Collections.emptyMap() : headers
            ));
            this.body = body == null ? new byte[0] : body;
        }

        public int getStatusCode() { return statusCode; }
        public URI getFinalUri() { return finalUri; }
        public String getContentType() { return contentType; }
        public Map<String, String> getHeaders() { return headers; }
        public byte[] getBody() { return body; }
    }

    public static class HttpStatusException extends IOException {
        private final int statusCode;

        public HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int getStatusCode() { return statusCode; }
    }
}

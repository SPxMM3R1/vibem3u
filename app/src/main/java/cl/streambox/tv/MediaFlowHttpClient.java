package cl.streambox.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Small origin-pinned HTTP client for MediaFlow's JSON extractor endpoint. */
final class MediaFlowHttpClient {
    private static final int MAX_REDIRECTS = 4;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    MediaFlowHttpClient() {
        this(4_000, 12_000);
    }

    MediaFlowHttpClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = Math.max(1, connectTimeoutMs);
        this.readTimeoutMs = Math.max(1, readTimeoutMs);
    }

    TokenHttpClient.Response get(
            URI initial,
            URI configuredOrigin,
            Map<String, String> headers
    ) throws IOException {
        return getInternal(initial, configuredOrigin, headers, null, false);
    }

    /** Bounded probe used only when MediaFlow's JSON omits the media type. */
    TokenHttpClient.Response getPrefix(
            URI initial,
            URI configuredOrigin,
            Map<String, String> headers
    ) throws IOException {
        return getInternal(initial, configuredOrigin, headers, "bytes=0-65535", true);
    }

    private TokenHttpClient.Response getInternal(
            URI initial,
            URI configuredOrigin,
            Map<String, String> headers,
            String range,
            boolean prefixOnly
    ) throws IOException {
        URI current = initial;
        ResolutionContext context = ResolutionContext.current();
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (context != null) context.check();
            MediaFlowRequestPolicy.requireConfiguredOrigin(current, configuredOrigin);
            OkHttpClient client = SharedHttpClient.forResolution(
                    context,
                    connectTimeoutMs,
                    readTimeoutMs,
                    readTimeoutMs,
                    false
            );
            Request request = request(current, headers, range);
            Call call = client.newCall(request);
            ResolutionContext.Registration registration = context == null
                    ? null
                    : context.register(call);
            okhttp3.Response response = null;
            try {
                response = call.execute();
                if (context != null) context.check();
                int code = response.code();
                if (code >= 300 && code < 400) {
                    if (redirect >= MAX_REDIRECTS) {
                        throw new IOException("Demasiadas redirecciones MediaFlow.");
                    }
                    String location = response.header("Location");
                    current = MediaFlowRequestPolicy.resolveRedirect(
                            response.request().url().uri(),
                            location,
                            configuredOrigin
                    );
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new TokenHttpClient.HttpStatusException(code);
                }
                byte[] body = readBody(
                        response.body() == null ? null : response.body().byteStream(),
                        prefixOnly ? 64 * 1024 : MAX_RESPONSE_BYTES,
                        prefixOnly,
                        context
                );
                URI finalUri = response.request().url().uri();
                MediaFlowRequestPolicy.requireConfiguredOrigin(finalUri, configuredOrigin);
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (String name : response.headers().names()) {
                    String value = response.header(name);
                    if (value != null) responseHeaders.put(name, value);
                }
                return new TokenHttpClient.Response(
                        code,
                        finalUri,
                        response.header("Content-Type"),
                        responseHeaders,
                        body
                );
            } finally {
                if (response != null) response.close();
                if (registration != null) registration.close();
            }
        }
        throw new IOException("Demasiadas redirecciones MediaFlow.");
    }

    private static Request request(URI uri, Map<String, String> headers, String range)
            throws IOException {
        try {
            Request.Builder builder = new Request.Builder().url(uri.toString()).get()
                    .header("Cache-Control", "no-store, no-cache, max-age=0")
                    .header("Pragma", "no-cache")
                    .header("Expires", "0");
            boolean userAgent = false;
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) continue;
                    builder.header(header.getKey(), header.getValue());
                    if ("User-Agent".equalsIgnoreCase(header.getKey())) userAgent = true;
                }
            }
            if (!userAgent) builder.header("User-Agent", TokenHttpClient.BROWSER_USER_AGENT);
            if (range != null && !range.isEmpty()) builder.header("Range", range);
            return builder.build();
        } catch (IllegalArgumentException error) {
            throw new IOException("URL MediaFlow inválida.", error);
        }
    }

    private static byte[] readBody(
            InputStream input,
            int maximumBytes,
            boolean prefixOnly,
            ResolutionContext context
    ) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (context != null) context.check();
                total += count;
                if (total > maximumBytes) {
                    if (prefixOnly) break;
                    throw new IOException("Respuesta MediaFlow demasiado grande.");
                }
                output.write(buffer, 0, count);
                if (prefixOnly && total >= maximumBytes) break;
            }
            return output.toByteArray();
        }
    }
}

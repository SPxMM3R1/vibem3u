package cl.streambox.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small cancellable HTTP client for provider pages and token APIs. */
public final class TokenHttpClient {
    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

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
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Solicitud cancelada.");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IOException("URL no válida.");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("URL no válida.");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            // Resolver pages and token responses are credentials, never
            // reusable application resources. These headers also prevent an
            // intermediary HTTP cache from serving a previous response.
            connection.setRequestProperty("Cache-Control", "no-store, no-cache, max-age=0");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("Expires", "0");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }
            if (range != null && !range.isBlank()) {
                connection.setRequestProperty("Range", range);
            }
            if (connection.getRequestProperty("User-Agent") == null) {
                connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new HttpStatusException(responseCode);
            }
            try (InputStream input = connection.getInputStream()) {
                URI finalUri;
                try {
                    finalUri = connection.getURL().toURI();
                } catch (URISyntaxException error) {
                    throw new IOException("El servidor devolvió una URL inválida.", error);
                }
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (Map.Entry<String, java.util.List<String>> entry
                        : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null
                            && !entry.getValue().isEmpty()) {
                        responseHeaders.put(entry.getKey(), entry.getValue().get(0));
                    }
                }
                return new Response(
                        responseCode,
                        finalUri,
                        connection.getContentType(),
                        responseHeaders,
                        readLimited(input, Math.max(1, maxResponseBytes))
                );
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    private static byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Solicitud cancelada.");
            }
            total += count;
            if (total > maximumBytes) {
                throw new IOException("Respuesta demasiado grande.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
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
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body;
        }

        public int getStatusCode() { return statusCode; }
        public URI getFinalUri() { return finalUri; }
        public String getContentType() { return contentType; }
        public Map<String, String> getHeaders() { return headers; }
        public byte[] getBody() { return body; }
    }

    public static final class HttpStatusException extends IOException {
        private final int statusCode;

        HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int getStatusCode() { return statusCode; }
    }
}

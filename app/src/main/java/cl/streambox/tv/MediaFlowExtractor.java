package cl.streambox.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Calls MediaFlow's Vavoo extractor and converts its response to playback metadata. */
final class MediaFlowExtractor {
    static final String HLS_MIME = "application/x-mpegURL";
    static final String TS_MIME = "video/mp2t";
    private static final int MAX_PREFIX_BYTES = 64 * 1024;

    private final MediaFlowHttpClient httpClient;

    MediaFlowExtractor() {
        this(new MediaFlowHttpClient());
    }

    MediaFlowExtractor(MediaFlowHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    Result extract(URI origin, URI originalVavoo, String apiPassword) throws IOException {
        MediaFlowOriginPolicy.requireSameOrigin(origin, origin);
        MediaFlowVavooUrl.requireOriginal(originalVavoo);
        if (AppStrings.isBlank(apiPassword)) {
            throw new IOException("Falta la contraseña API de MediaFlow.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("host", "Vavoo");
        params.put("d", originalVavoo.toString());
        params.put("redirect_stream", "false");
        params.put("api_password", apiPassword);
        URI extractorUri = appendParameters(origin.resolve("/extractor/video"), params);
        TokenHttpClient.Response response = httpClient.get(
                extractorUri,
                origin,
                java.util.Collections.singletonMap("Accept", "application/json")
        );
        byte[] body = response.getBody();
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            return parseJson(origin, originalVavoo, apiPassword, response, text);
        }
        URI finalUri = withPassword(response.getFinalUri(), apiPassword);
        MediaFlowRequestPolicy.requireConfiguredOrigin(finalUri, origin);
        String mime = detectMime(response.getContentType(), finalUri, body);
        return new Result(finalUri, mime, java.util.Collections.emptyMap());
    }

    private Result parseJson(
            URI origin,
            URI originalVavoo,
            String apiPassword,
            TokenHttpClient.Response response,
            String text
    ) throws IOException {
        final JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException("Respuesta JSON de MediaFlow inválida.", error);
        }
        String destination = json.optString("destination_url", "").trim();
        URI destinationUri = originalVavoo;
        if (!destination.isEmpty()) {
            try {
                destinationUri = URI.create(destination);
                // MediaFlow may replace the original Vavoo URL with a
                // short-lived public CDN destination. Keep the original `d`
                // strict; accept only a public HTTP(S) destination returned
                // by the trusted extractor.
                PublicStreamPolicy.requirePublicHttp(destinationUri);
            } catch (IllegalArgumentException error) {
                throw new IOException("Destino MediaFlow inválido.", error);
            }
        }

        String proxyValue = json.optString("mediaflow_proxy_url", "").trim();
        URI proxy = resolveProxy(origin, proxyValue);
        String endpointValue = json.optString("endpoint", "").trim();
        if (endpointValue.isEmpty()) {
            endpointValue = json.optString("mediaflow_endpoint", "").trim();
        }
        if (!endpointValue.isEmpty() && (proxyValue.isEmpty() || proxy.getPath().isEmpty()
                || "/".equals(proxy.getPath()) || "/proxy".equals(proxy.getPath()))) {
            proxy = resolveProxy(proxy, endpointValue);
        }
        MediaFlowRequestPolicy.requireConfiguredOrigin(proxy, origin);
        if (!isProxyPath(proxy.getPath())) {
            throw new IOException("MediaFlow publicó una ruta proxy no autorizada.");
        }

        Map<String, String> query = queryParameters(proxy.getRawQuery());
        query.remove("api_password");
        query.remove("d");
        query.remove("redirect_stream");
        JSONObject queryObject = json.optJSONObject("query_params");
        if (queryObject != null) {
            java.util.Iterator<String> keys = queryObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!safeParameterName(key)) continue;
                Object value = queryObject.opt(key);
                if (value != null && value != JSONObject.NULL) {
                    query.put(key, String.valueOf(value));
                }
            }
        }
        query.put("d", destinationUri.toString());
        JSONObject headersObject = json.optJSONObject("request_headers");
        if (headersObject != null) {
            java.util.Iterator<String> keys = headersObject.keys();
            while (keys.hasNext()) {
                String header = keys.next();
                if (!safeHeaderName(header)
                        || "range".equalsIgnoreCase(header)
                        || "if-range".equalsIgnoreCase(header)) continue;
                Object value = headersObject.opt(header);
                if (value != null && value != JSONObject.NULL) {
                    query.put("h_" + header, String.valueOf(value));
                }
            }
        }
        query.put("api_password", apiPassword);
        URI playback = appendParameters(proxyWithoutQuery(proxy), query);
        MediaFlowRequestPolicy.requirePlaybackUri(playback, origin);
        String mime = detectJsonMime(json, endpointValue, playback);
        if (mime.isEmpty()) {
            mime = detectMime(response.getContentType(), playback, response.getBody());
        }
        if (mime.isEmpty()) {
            // /proxy/stream is intentionally format-neutral in MediaFlow.
            // Read only a bounded prefix so a HLS body can be identified
            // without assuming TS from the endpoint name or consuming live
            // media indefinitely.
            try {
                TokenHttpClient.Response probe = httpClient.getPrefix(
                        playback,
                        origin,
                        java.util.Collections.emptyMap()
                );
                mime = detectMime(probe.getContentType(), probe.getFinalUri(), probe.getBody());
            } catch (IOException ignored) {
                // Keep the source with an empty MIME; Media3 may sniff a
                // progressive/TS response while opening it.
            }
        }
        return new Result(playback, mime, java.util.Collections.emptyMap());
    }

    private static URI resolveProxy(URI origin, String value) throws IOException {
        URI proxy;
        try {
            proxy = AppStrings.isBlank(value) ? origin : origin.resolve(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("URL proxy MediaFlow inválida.", error);
        }
        MediaFlowRequestPolicy.requireConfiguredOrigin(proxy, origin);
        return proxy;
    }

    private static URI proxyWithoutQuery(URI value) throws IOException {
        String authority = value.getRawAuthority();
        String path = value.getRawPath();
        if (authority == null || path == null) {
            throw new IOException("URL proxy MediaFlow inválida.");
        }
        try {
            return URI.create(value.getScheme() + "://" + authority + path);
        } catch (IllegalArgumentException error) {
            throw new IOException("URL proxy MediaFlow inválida.", error);
        }
    }

    private static URI appendParameters(URI base, Map<String, String> parameters)
            throws IOException {
        StringBuilder result = new StringBuilder(base.toString());
        String existing = base.getRawQuery();
        if (existing != null && !existing.isEmpty()) result.append('&');
        else result.append('?');
        boolean first = existing == null || existing.isEmpty();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!safeParameterName(entry.getKey()) || entry.getValue() == null) continue;
            if (!first) result.append('&');
            first = false;
            try {
                result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.name()))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8.name()));
            } catch (Exception error) {
                throw new IOException("Parámetro MediaFlow inválido.", error);
            }
        }
        try {
            return URI.create(result.toString());
        } catch (IllegalArgumentException error) {
            throw new IOException("URL MediaFlow inválida.", error);
        }
    }

    private static Map<String, String> queryParameters(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String item : query.split("&", -1)) {
            if (item.isEmpty()) continue;
            int equals = item.indexOf('=');
            String key = equals < 0 ? item : item.substring(0, equals);
            String value = equals < 0 ? "" : item.substring(equals + 1);
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8.name());
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                continue;
            }
            if (safeParameterName(key)) result.put(key, value);
        }
        return result;
    }

    private static URI withPassword(URI value, String password) throws IOException {
        Map<String, String> query = queryParameters(value.getRawQuery());
        query.remove("api_password");
        query.put("api_password", password);
        return appendParameters(proxyWithoutQuery(value), query);
    }

    private static boolean isProxyPath(String path) {
        if (path == null) return false;
        String value = path.toLowerCase(Locale.ROOT);
        return value.startsWith("/proxy/") || value.equals("/stream") || value.equals("/proxy");
    }

    private static String detectJsonMime(JSONObject json, String endpoint, URI playback) {
        if (json.optBoolean("is_hls", false)) return HLS_MIME;
        String format = json.optString("format", "") + " "
                + json.optString("media_type", "") + " "
                + json.optString("content_type", "") + " " + endpoint;
        String lower = format.toLowerCase(Locale.ROOT);
        if (lower.contains("m3u8") || lower.contains("mpegurl") || lower.contains("hls")) {
            return HLS_MIME;
        }
        if (lower.contains("mpegts") || lower.contains("mp2t")) {
            return TS_MIME;
        }
        return detectMime("", playback, new byte[0]);
    }

    private static String detectMime(String contentType, URI uri, byte[] prefix) {
        String content = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String path = uri == null || uri.getPath() == null
                ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (content.contains("mpegurl") || content.contains("m3u8")
                || path.contains("m3u8") || path.contains("/proxy/hls/")) return HLS_MIME;
        if (content.contains("mp2t") || content.contains("mpegts")) return TS_MIME;
        if (prefix != null && prefix.length > 0) {
            String text = new String(prefix, 0, Math.min(prefix.length, MAX_PREFIX_BYTES),
                    StandardCharsets.UTF_8).trim();
            if (text.startsWith("#EXTM3U")) return HLS_MIME;
        }
        if (prefix != null && prefix.length >= 376) {
            int max = Math.min(prefix.length - 188, 3);
            for (int offset = 0; offset <= max; offset++) {
                if ((prefix[offset] & 0xff) == 0x47
                        && (prefix[offset + 188] & 0xff) == 0x47) return TS_MIME;
            }
        }
        return "";
    }

    private static boolean safeParameterName(String value) {
        return value != null && value.length() <= 96
                && value.matches("[A-Za-z0-9_.-]+(?:[A-Za-z0-9_.-]+)?");
    }

    private static boolean safeHeaderName(String value) {
        return value != null && value.length() <= 96
                && value.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+");
    }

    static final class Result {
        private final URI playbackUri;
        private final String mimeType;
        private final Map<String, String> requestHeaders;

        Result(URI playbackUri, String mimeType, Map<String, String> requestHeaders) {
            this.playbackUri = playbackUri;
            this.mimeType = mimeType;
            this.requestHeaders = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(requestHeaders)
            );
        }

        URI getPlaybackUri() { return playbackUri; }
        String getMimeType() { return mimeType; }
        Map<String, String> getRequestHeaders() { return requestHeaders; }
    }
}

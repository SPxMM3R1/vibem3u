package cl.streambox.tv.local;

import cl.streambox.tv.ResolvedPlaybackSource;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loopback-only HLS relay; temporary URLs and request headers stay in memory. */
final class HlsProxy {
    private static final long SESSION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(25);
    private static final int MAX_SESSIONS = 32;
    private static final int MAX_ASSETS_PER_SESSION = 8192;
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?i)(URI\\s*=\\s*\")([^\"]+)(\")");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final boolean allowPrivateAddresses;

    HlsProxy() {
        this(false);
    }

    HlsProxy(boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    Session open(ResolvedPlaybackSource source) throws IOException {
        if (source == null || source.getPlaybackUri() == null) throw new IOException("El resolutor no entregó una fuente reproducible.");
        validatePublicUri(source.getPlaybackUri());
        removeExpired();
        if (sessions.size() >= MAX_SESSIONS) throw new IOException("Hay demasiadas pruebas de señal abiertas; cierra una y vuelve a intentar.");
        Session session = new Session(UUID.randomUUID().toString().replace("-", ""), source);
        sessions.put(session.id, session);
        return session;
    }

    void close(String id) {
        Session session = sessions.remove(id);
        if (session != null) session.assets.clear();
    }

    void stream(String sessionId, String assetId, String range, OutputStream output, ResponseWriter writer) throws IOException {
        Session session = sessions.get(sessionId);
        if (session == null || session.expiresAtMillis <= System.currentTimeMillis()) {
            close(sessionId);
            throw new IOException("La sesión de prueba caducó. Vuelve a probar el canal.");
        }
        URI uri = "root".equals(assetId)
                ? session.source.getPlaybackUri()
                : session.assets.get(assetId);
        if (uri == null) throw new IOException("El recurso de vídeo ya no está disponible.");
        fetchAndWrite(session, uri, range, output, writer, "root".equals(assetId));
    }

    private void fetchAndWrite(
            Session session,
            URI initialUri,
            String range,
            OutputStream output,
            ResponseWriter writer,
            boolean root
    ) throws IOException {
        URI current = initialUri;
        for (int redirects = 0; redirects <= 4; redirects++) {
            validatePublicUri(current);
            Request.Builder request = new Request.Builder().url(current.toString()).get();
            boolean sameOrigin = sameOrigin(session.source.getPlaybackUri(), current);
            for (Map.Entry<String, String> header : session.headers.entrySet()) {
                if (!sameOrigin && isSensitiveHeader(header.getKey())) continue;
                request.header(header.getKey(), header.getValue());
            }
            if (!session.headers.containsKey("User-Agent") && !session.userAgent.isBlank()) {
                request.header("User-Agent", session.userAgent);
            }
            if (range != null && range.matches("bytes=[0-9]+-[0-9]*")) request.header("Range", range);

            try (Response response = HTTP.newCall(request.build()).execute()) {
                if (isRedirect(response.code())) {
                    if (redirects == 4) throw new IOException("El proveedor redirigió demasiadas veces.");
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) throw new IOException("La redirección del proveedor no tiene destino.");
                    current = current.resolve(location);
                    continue;
                }
                if (!response.isSuccessful()) throw new IOException("El proveedor respondió HTTP " + response.code() + ".");
                ResponseBody body = response.body();
                if (body == null) throw new IOException("El proveedor respondió sin contenido.");
                String contentType = response.header("Content-Type", "application/octet-stream");
                boolean manifest = root || isManifest(current, contentType);
                if (manifest) {
                    byte[] bytes;
                    try (InputStream input = body.byteStream()) {
                        bytes = readLimited(input, MAX_PLAYLIST_BYTES);
                    }
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (!text.stripLeading().startsWith("#EXTM3U")) throw new IOException("La respuesta no es una playlist HLS.");
                    String rewritten = rewriteManifest(session, current, text);
                    writer.headers(response.code(), "application/vnd.apple.mpegurl; charset=utf-8", rewritten.getBytes(StandardCharsets.UTF_8).length, null, null);
                    output.write(rewritten.getBytes(StandardCharsets.UTF_8));
                    return;
                }

                long contentLength = body.contentLength();
                writer.headers(
                        response.code(),
                        contentType,
                        contentLength,
                        response.header("Content-Range"),
                        response.header("Accept-Ranges")
                );
                try (InputStream input = body.byteStream()) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                }
                return;
            }
        }
        throw new IOException("No se pudo abrir el recurso del proveedor.");
    }

    private String rewriteManifest(Session session, URI base, String manifest) throws IOException {
        StringBuilder rewritten = new StringBuilder(manifest.length() + 256);
        for (String line : manifest.split("\\r?\\n", -1)) {
            String value = line.trim();
            if (value.isEmpty()) {
                rewritten.append(line).append('\n');
                continue;
            }
            if (value.startsWith("#")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer replaced = new StringBuffer();
                while (matcher.find()) {
                    String proxied = localAsset(session, base.resolve(matcher.group(2)));
                    matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group(1) + proxied + matcher.group(3)));
                }
                matcher.appendTail(replaced);
                rewritten.append(replaced).append('\n');
            } else {
                rewritten.append(localAsset(session, base.resolve(value))).append('\n');
            }
        }
        return rewritten.toString();
    }

    private String localAsset(Session session, URI uri) throws IOException {
        validatePublicUri(uri);
        if (session.assets.size() >= MAX_ASSETS_PER_SESSION) throw new IOException("La playlist contiene demasiados recursos.");
        String id = UUID.randomUUID().toString().replace("-", "");
        session.assets.put(id, uri);
        return "/api/preview/" + session.id + "/asset/" + id;
    }

    private static boolean isManifest(URI uri, String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".m3u8") || type.contains("mpegurl") || type.contains("vnd.apple.mpegurl");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI first, URI second) {
        if (first == null || second == null || first.getScheme() == null || second.getScheme() == null
                || first.getHost() == null || second.getHost() == null) return false;
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isSensitiveHeader(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("authorization")
                || normalized.equals("cookie")
                || normalized.equals("proxy-authorization")
                || normalized.contains("token")
                || normalized.contains("api-key")
                || normalized.endsWith("-key")
                || normalized.contains("secret")
                || normalized.contains("credential");
    }

    private static byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > maximumBytes) throw new IOException("La playlist HLS supera el límite seguro.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void validatePublicUri(URI uri) throws IOException {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("El proveedor publicó una dirección no compatible.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowPrivateAddresses && (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal"))) {
            throw new IOException("El proveedor publicó un destino local no permitido.");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException invalidHost) {
            throw new IOException("No se pudo localizar el servidor del proveedor.");
        }
        if (addresses.length == 0) throw new IOException("No se pudo localizar el servidor del proveedor.");
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress() || isCarrierGradeNat(address.getAddress())) {
                if (!allowPrivateAddresses) throw new IOException("El proveedor publicó un destino local no permitido.");
            }
        }
    }

    private static boolean isCarrierGradeNat(byte[] address) {
        return address.length == 4
                && (address[0] & 0xff) == 100
                && (address[1] & 0xc0) == 64;
    }

    private void removeExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis <= now);
    }

    interface ResponseWriter {
        void headers(int status, String contentType, long contentLength, String contentRange, String acceptRanges) throws IOException;
    }

    static final class Session {
        final String id;
        final ResolvedPlaybackSource source;
        final long expiresAtMillis;
        final String userAgent;
        final Map<String, String> headers;
        final Map<String, URI> assets = new ConcurrentHashMap<>();

        Session(String id, ResolvedPlaybackSource source) {
            this.id = id;
            this.source = source;
            this.expiresAtMillis = System.currentTimeMillis() + SESSION_TTL_MILLIS;
            this.userAgent = source.getUserAgent() == null ? "" : source.getUserAgent();
            this.headers = new LinkedHashMap<>(source.getRequestHeaders());
            this.headers.remove("Host");
            this.headers.remove("Content-Length");
            this.headers.remove("Connection");
        }
    }
}

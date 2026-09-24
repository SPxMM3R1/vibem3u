package cl.streambox.tv.local;

import cl.streambox.tv.Channel;
import cl.streambox.tv.HighflyCatalogChannel;
import cl.streambox.tv.M3uParser;
import cl.streambox.tv.Playlist;
import cl.streambox.tv.ResolverCatalog;
import cl.streambox.tv.ResolverDefinition;
import cl.streambox.tv.ResolvedPlaybackSource;
import cl.streambox.tv.StreamResolver;
import cl.streambox.tv.TokenHttpClient;
import cl.streambox.tv.TvnStreamResolver;
import cl.streambox.tv.TvVooCatalogChannel;
import cl.streambox.tv.TvVooStreamResolver;
import cl.streambox.tv.HighflyStreamResolver;
import cl.streambox.tv.MeganoticiasStreamResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Local-only bridge between the Lista M3U editor and VibeM3U resolvers. */
public final class LocalCatalogServer {
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final Pattern SAFE_ASSET = Pattern.compile("[A-Za-z0-9._-]{1,160}");

    private final Path webRoot;
    private final Path listaRoot;
    private final ResolverCatalog resolverCatalog;
    private final GitHubPublisher publisher = new GitHubPublisher();
    private final HlsProxy hlsProxy = new HlsProxy();
    private HttpServer server;

    private LocalCatalogServer(Path webRoot, Path listaRoot, Path vibeRoot, int port) throws IOException {
        this.webRoot = webRoot.toRealPath();
        this.listaRoot = listaRoot.toRealPath();
        Path catalogPath = vibeRoot.resolve("app/src/main/assets/resolver_catalog.json");
        if (!Files.isRegularFile(catalogPath)) throw new IOException("No se encontró el catálogo de resolutores de VibeM3U.");
        String catalogJson = Files.readString(catalogPath, StandardCharsets.UTF_8);
        resolverCatalog = ResolverCatalog.parse(catalogJson);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(12, runnable -> {
            Thread thread = new Thread(runnable, "vibem3u-local-catalog");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path webRoot = requiredPath(options, "web-root");
        Path listaRoot = requiredPath(options, "lista-root");
        Path vibeRoot = requiredPath(options, "vibe-root");
        int port = Integer.parseInt(options.getOrDefault("port", "8787"));
        LocalCatalogServer local = new LocalCatalogServer(webRoot, listaRoot, vibeRoot, port);
        Runtime.getRuntime().addShutdownHook(new Thread(local::stop, "vibem3u-local-catalog-shutdown"));
        local.start();
    }

    private void start() {
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        System.out.println("Editor local: " + url);
        System.out.println("Este servicio solo escucha en localhost. Pulsa Ctrl+C para cerrarlo.");
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            System.out.println("No se pudo abrir el navegador automáticamente; abre la dirección local impresa arriba.");
        }
    }

    private void stop() {
        if (server != null) server.stop(1);
        removeTemporaryWebRoot();
    }

    private void removeTemporaryWebRoot() {
        try {
            Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            Path candidate = webRoot.toRealPath();
            Path name = candidate.getFileName();
            if (candidate.getParent() == null || !candidate.getParent().equals(tempRoot)
                    || name == null || !name.toString().matches("lista-m3u-editor-[a-f0-9]{32}")) return;
            try (Stream<Path> paths = Files.walk(candidate)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // The next OS temp cleanup can remove a file still held open by another process.
                    }
                });
            }
        } catch (IOException | SecurityException ignored) {
            // Cleanup is best-effort and restricted to this app's unique temp bundle.
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            requireLoopback(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                requireSameOrigin(exchange);
                if (handleApi(exchange, path)) return;
            } else if ("GET".equals(exchange.getRequestMethod()) || "HEAD".equals(exchange.getRequestMethod())) {
                serveStatic(exchange, path);
                return;
            } else {
                sendJson(exchange, 405, new JSONObject().put("error", "Método no permitido."));
                return;
            }
        } catch (GitHubPublisher.ConflictException conflict) {
            sendJsonIfOpen(exchange, 409, new JSONObject().put("error", conflict.getMessage()));
        } catch (IllegalArgumentException badRequest) {
            sendJsonIfOpen(exchange, 400, new JSONObject().put("error", safeMessage(badRequest.getMessage())));
        } catch (IOException failure) {
            sendJsonIfOpen(exchange, 502, new JSONObject().put("error", safeMessage(failure.getMessage())));
        } catch (RuntimeException failure) {
            sendJsonIfOpen(exchange, 500, new JSONObject().put("error", "No se pudo completar la operación local."));
        } finally {
            exchange.close();
        }
    }

    private boolean handleApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/api/status".equals(path)) {
            sendJson(exchange, 200, new JSONObject()
                    .put("local", true)
                    .put("githubAuthenticated", publisher.isAuthenticated())
                    .put("resolverCatalogVersion", resolverCatalog.getVersion()));
            return true;
        }
        if ("POST".equals(method) && "/api/auth/start".equals(path)) {
            publisher.startAuthentication();
            sendJson(exchange, 202, new JSONObject().put("started", true));
            return true;
        }
        if ("POST".equals(method) && "/api/publish".equals(path)) {
            GitHubPublisher.PublishResult result = publisher.publish(readJson(exchange));
            JSONObject shas = new JSONObject();
            result.blobShas.forEach(shas::put);
            sendJson(exchange, 200, new JSONObject()
                    .put("sha", result.commitSha)
                    .put("blobs", shas));
            return true;
        }
        if ("POST".equals(method) && "/api/resolve".equals(path)) {
            JSONObject payload = readJson(exchange);
            JSONObject channelPayload = payload.optJSONObject("channel");
            if (channelPayload == null) throw new IllegalArgumentException("Faltan los datos del canal.");
            Channel channel = channelFromPayload(channelPayload, payload.optString("sourceList", ""), payload.optString("tvgId", ""));
            StreamResolver resolver = resolverFor(channel);
            ResolvedPlaybackSource source = resolver == null
                    ? ResolvedPlaybackSource.direct(channel, TokenHttpClient.BROWSER_USER_AGENT)
                    : resolver.resolve(channel);
            HlsProxy.Session session = hlsProxy.open(source);
            String url = "/api/preview/" + session.id + "/asset/root";
            sendJson(exchange, 200, new JSONObject()
                    .put("sessionId", session.id)
                    .put("mediaUrl", url)
                    .put("resolver", resolver == null ? "direct" : resolver.getId())
                    .put("expiresAt", session.expiresAtMillis));
            return true;
        }
        if ("POST".equals(method) && "/api/preview/close".equals(path)) {
            hlsProxy.close(readJson(exchange).optString("sessionId", ""));
            sendJson(exchange, 200, new JSONObject().put("closed", true));
            return true;
        }
        if ("GET".equals(method) && path.startsWith("/api/preview/")) {
            String[] parts = path.split("/");
            if (parts.length != 6 || !"asset".equals(parts[4]) || !SAFE_ASSET.matcher(parts[5]).matches()) {
                sendJson(exchange, 404, new JSONObject().put("error", "El recurso de vídeo no existe."));
                return true;
            }
            String sessionId = parts[3];
            String assetId = parts[5];
            boolean[] sent = {false};
            String range = exchange.getRequestHeaders().getFirst("Range");
            hlsProxy.stream(sessionId, assetId, range, exchange.getResponseBody(), (status, type, length, contentRange, acceptRanges) -> {
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                if (contentRange != null) exchange.getResponseHeaders().set("Content-Range", contentRange);
                if (acceptRanges != null) exchange.getResponseHeaders().set("Accept-Ranges", acceptRanges);
                exchange.sendResponseHeaders(status, length < 0 ? 0 : length);
                sent[0] = true;
            });
            return true;
        }
        sendJson(exchange, 404, new JSONObject().put("error", "Ruta local no encontrada."));
        return true;
    }

    private Channel channelFromPayload(JSONObject row, String sourceList, String tvgId) throws IOException {
        if ("provider".equals(row.optString("kind", ""))) {
            String provider = row.optString("provider", "");
            String catalogKey = row.optString("catalogKey", "");
            String name = clean(row.optString("name", ""), 160);
            if (catalogKey.isBlank() || name.isBlank()) throw new IllegalArgumentException("La identidad catalogKey o el nombre están vacíos.");
            if ("highfly".equals(provider)) {
                String resourceId = clean(row.optString("providerResourceId", ""), 140);
                List<String> genres = stringArray(row.optJSONArray("genres"));
                return new HighflyCatalogChannel(
                        resourceId,
                        catalogKey,
                        name,
                        clean(row.optString("group", "Highfly"), 120),
                        clean(row.optString("category", ""), 120),
                        "",
                        genres
                ).toChannel();
            }
            if ("tvvoo".equals(provider)) {
                String alias = clean(row.optString("alias", ""), 256);
                String country = clean(row.optString("country", row.optString("countryKey", "")), 120);
                if (alias.isBlank() || country.isBlank()) throw new IllegalArgumentException("El canal TvVoo necesita alias y país.");
                List<String> aliases = stringArray(row.optJSONArray("aliases"));
                if (aliases.isEmpty()) aliases = stringArray(row.optJSONArray("resolverAliases"));
                return new TvVooCatalogChannel(
                        catalogKey,
                        alias,
                        name,
                        country,
                        clean(row.optString("group", country), 120),
                        clean(row.optString("category", ""), 120),
                        "",
                        stringArray(row.optJSONArray("genres")),
                        aliases
                ).toChannel();
            }
            throw new IllegalArgumentException("El catálogo no reconoce este proveedor.");
        }

        if (!"m3u".equals(row.optString("kind", ""))) throw new IllegalArgumentException("Tipo de canal no compatible.");
        if (!("1.m3u".equals(sourceList) || "2.m3u".equals(sourceList)) || tvgId.isBlank()) {
            throw new IllegalArgumentException("Para probar una fuente M3U se necesita su Lista y tvg-id.");
        }
        Path playlistPath = listaRoot.resolve(sourceList).normalize();
        if (!playlistPath.startsWith(listaRoot) || !Files.isRegularFile(playlistPath)) {
            throw new IOException("No se encontró " + sourceList + " en el repositorio local Lista M3U.");
        }
        String playlistText = Files.readString(playlistPath, StandardCharsets.UTF_8);
        URI base = playlistPath.toUri();
        Playlist playlist = M3uParser.parsePlaylist(playlistText, base);
        List<Channel> matches = new ArrayList<>();
        for (Channel channel : playlist.getChannels()) {
            if (tvgId.equals(channel.getTvgId())) matches.add(channel);
        }
        if (matches.size() != 1) {
            throw new IOException(matches.isEmpty()
                    ? "No se encontró esa identidad en la lista local. Actualiza el repositorio Lista M3U."
                    : "La identidad aparece más de una vez; no se eligió una fuente ambigua.");
        }
        return matches.get(0);
    }

    private StreamResolver resolverFor(Channel channel) throws IOException {
        ResolverDefinition definition = resolverCatalog.find(channel);
        if (definition == null) return null;
        return switch (definition.getEngine()) {
            case "tvn" -> new TvnStreamResolver(definition);
            case "meganoticias" -> new MeganoticiasStreamResolver(definition);
            case "highfly" -> new HighflyStreamResolver(definition);
            case "tvvoo" -> new TvVooStreamResolver(definition);
            default -> throw new IOException("VibeM3U no ofrece previsualización para este motor.");
        };
    }

    private void serveStatic(HttpExchange exchange, String rawPath) throws IOException {
        String path = rawPath == null || rawPath.equals("/") ? "/index.html" : rawPath;
        Path target = webRoot.resolve(path.substring(1)).normalize();
        if (!target.startsWith(webRoot) || !Files.isRegularFile(target)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        Path real = target.toRealPath();
        if (!real.startsWith(webRoot)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        String type = URLConnection.guessContentTypeFromName(real.getFileName().toString());
        if (path.endsWith(".mjs") || path.endsWith(".js")) type = "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) type = "text/css; charset=utf-8";
        if (path.endsWith(".json")) type = "application/json; charset=utf-8";
        if (path.endsWith(".svg")) type = "image/svg+xml";
        if (type == null) type = "application/octet-stream";
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        long length = Files.size(real);
        exchange.sendResponseHeaders(200, length);
        try (InputStream input = Files.newInputStream(real); OutputStream output = exchange.getResponseBody()) {
            input.transferTo(output);
        }
    }

    private void requireLoopback(HttpExchange exchange) throws IOException {
        InetAddress remote = exchange.getRemoteAddress().getAddress();
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (remote == null || !remote.isLoopbackAddress() || host == null
                || !(host.startsWith("127.0.0.1:") || host.startsWith("localhost:") || host.equals("127.0.0.1") || host.equals("localhost"))) {
            throw new IOException("El servicio solo acepta conexiones locales.");
        }
    }

    private void requireSameOrigin(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) return;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!(origin.equals("http://" + host)
                || origin.equals("http://127.0.0.1:" + server.getAddress().getPort())
                || origin.equals("http://localhost:" + server.getAddress().getPort()))) {
            throw new IOException("Origen de navegador no autorizado para este servicio local.");
        }
    }

    private static JSONObject readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) throw new IOException("La solicitud es demasiado grande.");
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("La solicitud JSON no es válida.");
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JSONObject payload) throws IOException {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendJsonIfOpen(HttpExchange exchange, int status, JSONObject payload) {
        try {
            sendJson(exchange, status, payload);
        } catch (IOException ignored) {
            // The connection closed while the failure response was being sent.
        }
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "No se pudo completar la operación.";
        return message.replaceAll("(?i)https?://[^\\s]+", "[dirección oculta]")
                .replaceAll("(?i)(access_token|token|signature|hdnts)=[^&\\s]+", "$1=[oculto]");
    }

    private static List<String> stringArray(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int index = 0; index < array.length() && index < 32; index++) {
            String value = clean(array.optString(index, ""), 256);
            if (!value.isBlank() && !values.contains(value)) values.add(value);
        }
        return values;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private static Path requiredPath(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Falta --" + key + ".");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            if (!key.startsWith("--") || index + 1 >= args.length) throw new IllegalArgumentException("Parámetros de inicio inválidos.");
            options.put(key.substring(2), args[++index]);
        }
        return options;
    }
}

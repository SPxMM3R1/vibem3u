package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.LongSupplier;

/** Independent Stremio HTTP client; no Bridge/Cloudstream implementation is bundled. */
public final class CncVerseStreamResolver implements StreamResolver {
    public static final String ID = "cncverse";
    public static final String DEFAULT_MANIFEST_URL =
            "https://cncverse.dpdns.org/u/p_0md89st1e5th/manifest.json";
    private static final Set<String> API_HOSTS = Collections.singleton("cncverse.dpdns.org");
    private static final String SPORTS_CATALOG = "/catalog/tv/cnc_SPORTSWORLD_tv.json";
    private static final String CHILE_CATALOG = "/catalog/tv/cnc_CHILETV_tv.json";
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final long TTL_MS = 120_000L;
    private static final long CATALOG_TTL_MS = 60_000L;
    private static final long GROUP_TTL_MS = 15_000L;
    private static final int MAX_CACHE_BYTES = 512 * 1024;

    private final ResolverDefinition definition;
    private final TokenHttpClient client;
    private final HlsCheck validation;
    private final okhttp3.Dns dns;
    private final LongSupplier clock;
    private final Map<String, CachedJson> jsonCache = new LinkedHashMap<>(8, 0.75f, true);
    private long cacheGeneration;
    private static final class CachedJson {
        final JSONObject data;
        final long expires;
        final int bytes;
        CachedJson(JSONObject data, long expires, int bytes) {
            this.data = data; this.expires = expires; this.bytes = bytes;
        }
    }
    interface HlsCheck {
        void validate(URI uri, Map<String, String> headers, ResolutionContext context,
                ResolutionProgressListener progress) throws IOException;
    }

    public CncVerseStreamResolver() { this(null); }
    public CncVerseStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient(6000, 8000));
    }
    CncVerseStreamResolver(ResolverDefinition definition, TokenHttpClient client) {
        this(definition, client, new HlsStreamValidator(client)::validate);
    }
    CncVerseStreamResolver(ResolverDefinition definition, TokenHttpClient client, HlsCheck validation) {
        this(definition, client, validation, SharedHttpClient.get().dns());
    }
    CncVerseStreamResolver(ResolverDefinition definition, TokenHttpClient client,
            HlsCheck validation, okhttp3.Dns dns) {
        this(definition, client, validation, dns, () -> System.nanoTime() / 1_000_000L);
    }
    CncVerseStreamResolver(ResolverDefinition definition, TokenHttpClient client,
            HlsCheck validation, okhttp3.Dns dns, LongSupplier clock) {
        this.definition = definition;
        this.client = client;
        this.validation = validation;
        this.dns = dns;
        this.clock = clock;
    }

    @Override public String getId() { return ID; }
    @Override public boolean supports(Channel channel) {
        return channel != null && (ID.equalsIgnoreCase(channel.getAttributes().get("x-resolver"))
                || ID.equals(DynamicSourceReference.provider(channel.getStreamUri())));
    }
    @Override public String stableSourceId(Channel channel) {
        // Public channel identity, never the opaque Stremio resource id.
        return channel == null ? "" : channel.getTvgId();
    }
    @Override public long cacheTtlMillis() { return TTL_MS; }
    @Override public void clearSensitiveState() {
        synchronized (jsonCache) { jsonCache.clear(); cacheGeneration++; }
    }
    @Override public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        return resolve(channel, ResolutionProgressListener.NONE);
    }
    @Override public ResolvedPlaybackSource resolve(Channel channel,
            ResolutionProgressListener listener) throws IOException {
        return lookup(channel, listener, false).get(0).getSource();
    }
    @Override public List<ResolvedPlaybackCandidate> resolvePlaybackCandidates(Channel channel,
            ResolutionProgressListener listener) throws IOException {
        return lookup(channel, listener, true);
    }

    private List<ResolvedPlaybackCandidate> lookup(Channel channel,
            ResolutionProgressListener listener, boolean all) throws IOException {
        if (!supports(channel)) throw new IOException("El canal no usa CNCVerse.");
        String reference = channel.getAttributes().get("x-resolver-id");
        if (reference == null) reference = DynamicSourceReference.stableId(channel.getStreamUri());
        String[] parts = referenceParts(reference);
        boolean chile = "chiletv".equals(parts[0]);
        String base = addonBase(definition == null ? DEFAULT_MANIFEST_URL
                : definition.getConfig("manifestUrl", DEFAULT_MANIFEST_URL));
        ResolutionProgressListener progress = listener == null ? ResolutionProgressListener.NONE : listener;
        ResolutionContext parent = ResolutionContext.current();
        int budget = definition == null ? 20000
                : definition.getIntConfig("resolutionBudgetMs", 20000, 1000, 20000);
        ResolutionContext context = parent == null ? new ResolutionContext(budget) : parent.child(budget);
        try (ResolutionContext.Scope ignored = context.activate()) {
            context.check();
            progress.onProgress(ResolutionProgress.of(ResolutionStage.PAGE_REQUEST,
                    "CNCVerse · consultando catálogo " + (chile ? "CHILE TV" : "SPORTS WORLD")));
            String catalogUrl = base + (chile ? CHILE_CATALOG : SPORTS_CATALOG);
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean[] reused = {false};
                try {
                    JSONObject catalog = json(catalogUrl, CATALOG_TTL_MS, attempt > 0, reused);
                    String resourceId = uniqueResource(catalog, parts[1]);
                    // Opaque ids and group responses are briefly reused in RAM, never persisted.
                    JSONObject payload = json(base + "/stream/tv/" + encode(resourceId) + ".json",
                            GROUP_TTL_MS, attempt > 0, reused);
                    return validateStreams(payload.optJSONArray("streams"), channel, parts, all, context, progress);
                } catch (IOException | JSONException stale) {
                    context.check();
                    // Refresh cached ids/links once; never reset the total deadline.
                    if (attempt != 0 || !reused[0]) throw stale;
                    clearSensitiveState();
                }
            }
            throw new IOException("CNCVerse no entregó la señal.");
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("La respuesta de CNCVerse no es compatible.");
        } catch (IOException error) {
            throw new IOException("No se pudo resolver la señal CNCVerse. Reintenta o revisa el proveedor.");
        }
    }

    private List<ResolvedPlaybackCandidate> validateStreams(JSONArray streams, Channel channel,
            String[] parts, boolean all, ResolutionContext context, ResolutionProgressListener progress) throws IOException {
            boolean chile = "chiletv".equals(parts[0]);
            int matching = 0;
            if (streams != null) for (int i = 0; i < Math.min(64, streams.length()); i++) {
                JSONObject stream = streams.optJSONObject(i);
                if (stream != null && (chile ? !hasRawDrm(stream) : parts[2].equals(sourceLabel(stream)))) matching++;
            }
            List<ResolvedPlaybackCandidate> result = new ArrayList<>();
            List<InetAddress> bridgeAddresses = null;
            Set<String> checked = new LinkedHashSet<>();
            int tried = 0;
            if (streams != null) for (int i = 0; i < Math.min(streams.length(), 64); i++) {
                if (!result.isEmpty() && context.remainingMillis() <= 0L) break;
                context.check();
                JSONObject stream = streams.optJSONObject(i);
                if (stream == null || (!chile && !parts[2].equals(sourceLabel(stream)))) continue;
                // CHILE TV metadata denotes one exact channel, not a multi-channel sports group.
                // Only its own public HLS renditions are candidates; raw DRM is never interpreted.
                if (chile && hasRawDrm(stream)) continue;
                String advertised = stream.optString("url", "");
                okhttp3.HttpUrl bridge = bridgeShape(advertised);
                if (bridge != null && !API_HOSTS.contains(bridge.host())
                        && isIpLiteral(bridge.host()) && bridgeAddresses == null) {
                    try { bridgeAddresses = bridgeAddresses(context); }
                    catch (IOException unavailable) {
                        context.check();
                        bridgeAddresses = Collections.emptyList();
                    }
                }
                URI normalized = playbackUri(advertised, bridgeAddresses);
                context.check();
                if (normalized == null && bridge != null && isIpLiteral(bridge.host())) continue;
                URI uri = normalized != null ? normalized : chile ? chilePlaybackUri(advertised) : null;
                if (uri == null) continue;
                Map<String, String> headers = headers(stream);
                if (!checked.add(uri.toString() + "\n" + headers) || tried++ >= 8) continue;
                ResolutionContext candidate = context.child(matching > 1
                        ? Math.min(6000L, context.remainingMillis()) : context.remainingMillis());
                try (ResolutionContext.Scope candidateScope = candidate.activate()) {
                    if (chile) PublicStreamPolicy.requirePublicHttp(uri);
                    // Keep the master intact: separate audio renditions must not be lost.
                    validation.validate(uri, headers, candidate, progress);
                    candidate.check();
                    ResolvedPlaybackSource source = ResolvedPlaybackSource.dynamic(
                            ID, stableSourceId(channel), uri, headers,
                            headers.getOrDefault("User-Agent", TokenHttpClient.BROWSER_USER_AGENT),
                            System.currentTimeMillis() + TTL_MS, chile ? parts[1] : parts[2],
                            "application/x-mpegURL", null);
                    String label = sourceLabel(stream);
                    result.add(new ResolvedPlaybackCandidate(chile ? (label.isEmpty() ? parts[1] : label) : parts[2],
                            chile ? "CNCVerse Chile · HLS validado" : "CNCVerse · HLS del puente", source));
                    if (!all || result.size() >= 8) break;
                } catch (IOException failed) {
                    // Never propagate an exception embedding a signed URL or JSON resource id.
                    if (context.isCancelled() || Thread.currentThread().isInterrupted()) break;
                } finally { candidate.cancel(); }
            }
            if (context.isCancelled() || Thread.currentThread().isInterrupted()) context.check();
            if (result.isEmpty()) throw new IOException("CNCVerse no entregó HLS válido para la señal elegida.");
            return Collections.unmodifiableList(result);
    }

    private JSONObject json(String url, long ttl, boolean force, boolean[] reused) throws IOException, JSONException {
        ResolutionContext context = ResolutionContext.current();
        if (context != null) context.check();
        long generation;
        synchronized (jsonCache) {
            long now = clock.getAsLong();
            jsonCache.values().removeIf(value -> value.expires <= now);
            CachedJson cached = force ? null : jsonCache.get(url);
            if (cached != null) { reused[0] = true; return cached.data; }
            generation = cacheGeneration;
        }
        TokenHttpClient.Response response = client.getPublicOnHosts(url,
                Collections.singletonMap("Accept", "application/json"), MAX_JSON_BYTES, null, API_HOSTS);
        if (context != null) context.check();
        if (response.getStatusCode() != 200) throw new IOException("CNCVerse no respondió correctamente.");
        JSONObject data = new JSONObject(new String(response.getBody(), StandardCharsets.UTF_8));
        synchronized (jsonCache) {
            if (generation == cacheGeneration && response.getBody().length <= MAX_CACHE_BYTES) {
                jsonCache.put(url, new CachedJson(data, clock.getAsLong() + ttl, response.getBody().length));
                int bytes = jsonCache.values().stream().mapToInt(value -> value.bytes).sum();
                Iterator<CachedJson> oldest = jsonCache.values().iterator();
                while ((bytes > MAX_CACHE_BYTES || jsonCache.size() > 6) && oldest.hasNext()) {
                    bytes -= oldest.next().bytes; oldest.remove();
                }
            }
        }
        return data;
    }

    static String[] referenceParts(String reference) throws IOException {
        if (reference == null || reference.length() > 256) {
            throw new IOException("Referencia CNCVerse inválida.");
        }
        String[] parts = reference.split("\\|", -1);
        if (parts.length != 3 || !("sportsworld".equals(parts[0]) || "chiletv".equals(parts[0]))) {
            throw new IOException("Referencia CNCVerse inválida.");
        }
        boolean chile = "chiletv".equals(parts[0]);
        String checked = chile ? reference.replace("[Not 24/7]", "[Not 24-7]") : reference;
        if ((chile && !"auto".equals(parts[2]))
                || checked.matches("(?s).*[\\x00-\\x1f\\x7f/\\\\?#=\"].*")) {
            throw new IOException("Referencia CNCVerse inválida.");
        }
        for (String part : parts) if (part.isEmpty() || !part.equals(part.trim())) {
            throw new IOException("Referencia CNCVerse inválida.");
        }
        return parts;
    }

    static String addonBase(String manifest) throws IOException {
        try {
            URI uri = URI.create(manifest);
            if (!"https".equals(uri.getScheme()) || !API_HOSTS.contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getPath().matches("(?:/u/p_[A-Za-z0-9_-]{1,64})?/manifest\\.json")) {
                throw new IllegalArgumentException();
            }
            return manifest.substring(0, manifest.length() - "/manifest.json".length());
        } catch (IllegalArgumentException error) {
            throw new IOException("Manifiesto CNCVerse no permitido.");
        }
    }

    static String uniqueResource(JSONObject catalog, String group) throws IOException {
        JSONArray metas = catalog.optJSONArray("metas");
        String found = null;
        if (metas == null || metas.length() > 512) throw new IOException("Catálogo CNCVerse inválido.");
        for (int i = 0; i < metas.length(); i++) {
            JSONObject meta = metas.optJSONObject(i);
            if (meta == null || !group.equals(meta.optString("name", ""))) continue;
            if (found != null) throw new IOException("El grupo CNCVerse es ambiguo.");
            found = meta.optString("id", "");
            if (found.isEmpty() || found.length() > 16384) throw new IOException("Recurso CNCVerse inválido.");
        }
        if (found == null) throw new IOException("El grupo ya no existe en CNCVerse.");
        return found;
    }

    static String sourceLabel(JSONObject stream) {
        String title = stream.optString("title", "");
        for (String line : title.split("\\r?\\n")) {
            String value = line.trim();
            if (value.startsWith("🏷")) {
                String label = value.replaceFirst("^🏷\\uFE0F?\\s*", "").trim();
                return label.length() <= 160 && !label.matches("(?s).*[\\x00-\\x1f\\x7f].*") ? label : "";
            }
        }
        // No fuzzy matching or generic provider-name fallback: it could select another feed.
        return "";
    }

    static URI playbackUri(String value) {
        return playbackUri(value, Collections.emptyList());
    }

    /** Only addresses currently published by the trusted domain can act as its aliases. */
    static URI playbackUri(String value, List<InetAddress> addresses) {
        try {
            okhttp3.HttpUrl parsed = bridgeShape(value);
            if (parsed == null) return null;
            if (!API_HOSTS.contains(parsed.host())) {
                if (!isIpLiteral(parsed.host()) || addresses == null || addresses.isEmpty()) return null;
                InetAddress candidate = InetAddress.getByName(parsed.host()); // Literal only, no DNS.
                if (!PublicStreamPolicy.isPublic(candidate)) return null;
                boolean matches = false;
                for (InetAddress address : addresses) {
                    if (!PublicStreamPolicy.isPublic(address)) return null;
                    matches |= candidate.equals(address);
                }
                if (!matches) return null;
            }
            // Preserve escaped query/path and use valid TLS/SNI for the canonical domain.
            return parsed.newBuilder().scheme("https").host("cncverse.dpdns.org").port(443).build().uri();
        } catch (IllegalArgumentException | java.net.UnknownHostException error) { return null; }
    }

    private static boolean isIpLiteral(String host) {
        return host != null && (host.indexOf(':') >= 0 || host.matches("[0-9]+(?:\\.[0-9]+){3}"));
    }

    private static okhttp3.HttpUrl bridgeShape(String value) {
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.get(value);
            if (!parsed.username().isEmpty() || !parsed.password().isEmpty() || parsed.fragment() != null
                    || parsed.port() != ("https".equals(parsed.scheme()) ? 443 : 80)
                    || !parsed.encodedPath().startsWith("/proxy/")
                    || !parsed.encodedPath().endsWith(".m3u8")) return null;
            return parsed;
        } catch (IllegalArgumentException error) { return null; }
    }

    private List<InetAddress> bridgeAddresses(ResolutionContext context) throws IOException {
        return context.lookupDns("cncverse.dpdns.org", dns);
    }

    /** Scoped to CHILE TV. Public-IP enforcement happens before any HLS request. */
    static URI chilePlaybackUri(String value) {
        URI bridge = playbackUri(value);
        if (bridge != null) return bridge;
        try {
            URI uri = okhttp3.HttpUrl.get(value).uri();
            if (uri.getUserInfo() != null || uri.getFragment() != null
                    || !uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) return null;
            // No key-bearing Bridge query or signed URL is sent over cleartext.
            if (API_HOSTS.contains(uri.getHost())
                    || ("http".equals(uri.getScheme()) && uri.getRawQuery() != null)) return null;
            return uri;
        } catch (IllegalArgumentException error) { return null; }
    }

    private static boolean hasRawDrm(JSONObject stream) {
        for (JSONObject object : new JSONObject[]{stream, stream.optJSONObject("behaviorHints")}) {
            if (object == null) continue;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next().toLowerCase(Locale.ROOT);
                if (key.contains("drm") || key.contains("clearkey") || key.contains("widevine")
                        || key.contains("playready") || key.contains("license")) return true;
            }
        }
        return false;
    }

    private static Map<String, String> headers(JSONObject stream) {
        Map<String, String> result = new LinkedHashMap<>();
        JSONObject hints = stream.optJSONObject("behaviorHints");
        JSONObject proxy = hints == null ? null : hints.optJSONObject("proxyHeaders");
        JSONObject request = proxy == null ? null : proxy.optJSONObject("request");
        if (request != null) for (Iterator<String> keys = request.keys(); keys.hasNext();) {
            String key = keys.next();
            String lower = key.toLowerCase(Locale.ROOT);
            String value = request.optString(key, "");
            if ((lower.equals("user-agent") || lower.equals("referer")
                    || lower.equals("origin") || lower.equals("accept"))
                    && value.length() <= 2048 && !value.matches("(?s).*[\\r\\n].*")) {
                String canonical = lower.equals("user-agent") ? "User-Agent"
                        : lower.equals("referer") ? "Referer" : lower.equals("origin") ? "Origin" : "Accept";
                result.put(canonical, value);
            }
        }
        return result;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

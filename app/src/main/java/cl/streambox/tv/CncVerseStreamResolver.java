package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
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

/** Independent Stremio HTTP client; no Bridge/Cloudstream implementation is bundled. */
public final class CncVerseStreamResolver implements StreamResolver {
    public static final String ID = "cncverse";
    public static final String DEFAULT_MANIFEST_URL =
            "https://cncverse.dpdns.org/u/p_0md89st1e5th/manifest.json";
    private static final Set<String> API_HOSTS = Collections.singleton("cncverse.dpdns.org");
    private static final String CATALOG = "/catalog/tv/cnc_SPORTSWORLD_tv.json";
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final long TTL_MS = 120_000L;

    private final ResolverDefinition definition;
    private final TokenHttpClient client;
    private final HlsCheck validation;
    interface HlsCheck {
        void validate(URI uri, Map<String, String> headers, ResolutionContext context,
                ResolutionProgressListener progress) throws IOException;
    }

    public CncVerseStreamResolver() { this(null); }
    public CncVerseStreamResolver(ResolverDefinition definition) {
        this(definition, new TokenHttpClient(6000, 8000));
    }
    CncVerseStreamResolver(ResolverDefinition definition, TokenHttpClient client) {
        this(definition, client, new HlsStreamValidator(client)::validateForPlayback);
    }
    CncVerseStreamResolver(ResolverDefinition definition, TokenHttpClient client, HlsCheck validation) {
        this.definition = definition;
        this.client = client;
        this.validation = validation;
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
                    "CNCVerse · consultando catálogo SPORTS WORLD"));
            JSONObject catalog = json(base + CATALOG);
            String resourceId = uniqueResource(catalog, parts[1]);
            // resourceId can contain session material: it exists only within this attempt.
            JSONObject payload = json(base + "/stream/tv/" + encode(resourceId) + ".json");
            JSONArray streams = payload.optJSONArray("streams");
            List<ResolvedPlaybackCandidate> result = new ArrayList<>();
            if (streams != null) for (int i = 0; i < Math.min(streams.length(), 64); i++) {
                if (!result.isEmpty() && context.remainingMillis() <= 0L) break;
                context.check();
                JSONObject stream = streams.optJSONObject(i);
                if (stream == null || !parts[2].equals(sourceLabel(stream))) continue;
                URI uri = playbackUri(stream.optString("url", ""));
                if (uri == null) continue;
                Map<String, String> headers = headers(stream);
                try {
                    // Keep the master intact: separate audio renditions must not be lost.
                    validation.validate(uri, headers, context, progress);
                    ResolvedPlaybackSource source = ResolvedPlaybackSource.dynamic(
                            ID, stableSourceId(channel), uri, headers,
                            headers.getOrDefault("User-Agent", TokenHttpClient.BROWSER_USER_AGENT),
                            System.currentTimeMillis() + TTL_MS, parts[2],
                            "application/x-mpegURL", null);
                    result.add(new ResolvedPlaybackCandidate(parts[2], "CNCVerse · HLS del puente", source));
                    if (!all || result.size() >= 8) break;
                } catch (IOException failed) {
                    // Never propagate an exception embedding a signed URL or JSON resource id.
                    if (context.isCancelled() || Thread.currentThread().isInterrupted()) break;
                }
            }
            if (result.isEmpty()) throw new IOException("CNCVerse no entregó HLS válido para la señal elegida.");
            return Collections.unmodifiableList(result);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("La respuesta de CNCVerse no es compatible.");
        } catch (IOException error) {
            throw new IOException("No se pudo resolver la señal CNCVerse. Reintenta o revisa el proveedor.");
        }
    }

    private JSONObject json(String url) throws IOException, JSONException {
        TokenHttpClient.Response response = client.getPublicOnHosts(url,
                Collections.singletonMap("Accept", "application/json"), MAX_JSON_BYTES, null, API_HOSTS);
        if (response.getStatusCode() != 200) throw new IOException("CNCVerse no respondió correctamente.");
        return new JSONObject(new String(response.getBody(), StandardCharsets.UTF_8));
    }

    static String[] referenceParts(String reference) throws IOException {
        if (reference == null || reference.length() > 256
                || reference.matches("(?s).*[\\x00-\\x1f\\x7f/\\\\?#=\"].*")) {
            throw new IOException("Referencia CNCVerse inválida.");
        }
        String[] parts = reference.split("\\|", -1);
        if (parts.length != 3 || !"sportsworld".equals(parts[0])) {
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
        try {
            // Bridge query parameters may contain literal JSON braces/quotes. HttpUrl
            // escapes those characters without changing query semantics; only RAM holds it.
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.get(value);
            // Some Bridge deployments advertise HTTP even when HTTPS is available.
            // Never transmit its key-bearing query over cleartext, even on this fixed host.
            URI uri = parsed.newBuilder().scheme("https").build().uri();
            if (!"https".equals(uri.getScheme()) || !API_HOSTS.contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                    || !uri.getPath().startsWith("/proxy/")
                    || !uri.getPath().endsWith(".m3u8")) return null;
            return uri;
        } catch (IllegalArgumentException error) { return null; }
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

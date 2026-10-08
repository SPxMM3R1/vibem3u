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
import java.util.Locale;
import java.util.Map;

/** Nauta metadata and playback references are discovered in RAM, never persisted. */
public final class NautaStreamResolver implements StreamResolver {
    public static final String ID = "nauta";
    public static final String BASE = "https://stremio-addon-wheat.vercel.app";
    private static final long CATALOG_TTL_MS = 30 * 60_000L;
    private final TokenHttpClient http;
    private final HlsStreamValidator validator;
    private final Map<String, CachedCatalog> catalogs = new LinkedHashMap<>();
    private long generation;

    public NautaStreamResolver() { this(new TokenHttpClient()); }
    NautaStreamResolver(TokenHttpClient http) {
        this.http = http;
        validator = new HlsStreamValidator(http);
    }
    @Override public String getId() { return ID; }
    @Override public boolean supports(Channel channel) {
        return channel != null && ID.equals(DynamicSourceReference.provider(channel.getStreamUri()));
    }
    @Override public long cacheTtlMillis() { return 300_000L; }
    @Override public boolean keepSessionSourceOnPlaybackPause() { return true; }
    @Override public void clearSensitiveState() {
        synchronized (catalogs) { catalogs.clear(); generation++; }
    }

    @Override public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        return resolve(channel, ResolutionProgressListener.NONE);
    }
    @Override public ResolvedPlaybackSource resolve(Channel channel, ResolutionProgressListener listener)
            throws IOException {
        if (!supports(channel)) throw new IOException("Referencia Nauta no válida.");
        String[] reference = parseLocator(DynamicSourceReference.stableId(channel.getStreamUri()));
        ResolutionContext parent = ResolutionContext.current();
        ResolutionContext context = parent == null ? new ResolutionContext(20_000L) : parent.child(20_000L);
        ResolutionProgressListener progress = listener == null ? ResolutionProgressListener.NONE : listener;
        try (ResolutionContext.Scope ignored = context.activate()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                context.check();
                List<String> ids = idsFor(reference[0], reference[1], attempt > 0);
                for (String id : ids) {
                    context.check();
                    JSONObject payload;
                    try { payload = json(BASE + "/stream/tv/" + encode(id) + ".json"); }
                    catch (IOException unavailable) { context.check(); continue; }
                    JSONArray streams = payload.optJSONArray("streams");
                    if (streams == null) continue;
                    for (int index = 0; index < Math.min(streams.length(), 16); index++) {
                        context.check();
                        JSONObject stream = streams.optJSONObject(index);
                        if (stream == null || hasDrm(stream)) continue;
                        URI uri;
                        try { uri = URI.create(stream.optString("url", "")); }
                        catch (IllegalArgumentException invalid) { continue; }
                        if (isKnownUpdateSlateOrigin(uri)) continue;
                        Map<String, String> headers = headersOf(stream);
                        headers.putIfAbsent("User-Agent", TokenHttpClient.BROWSER_USER_AGENT);
                        try {
                            PublicStreamPolicy.requirePublicHttp(uri);
                            validator.validate(uri, headers, ResolutionProgressListener.NONE);
                            progress.onProgress(ResolutionProgress.of(ResolutionStage.SOURCE_FOUND,
                                    "Nauta · HLS y segmento validados · datos de acceso solo en RAM"));
                            return ResolvedPlaybackSource.dynamic(ID, stableSourceId(channel), uri,
                                    headers, headers.get("User-Agent"), System.currentTimeMillis() + cacheTtlMillis(),
                                    null, "application/x-mpegURL", null);
                        } catch (IOException unavailable) {
                            // Never retain a network exception containing a provider URL/opaque ID.
                            context.check();
                        }
                    }
                }
            }
            throw new IOException("Nauta no entregó una fuente HLS válida en este momento.");
        } catch (JSONException invalid) {
            throw new IOException("Nauta devolvió JSON no válido.");
        } catch (IOException error) {
            // Endpoints carry session-only identifiers; do not leak them via cause chains.
            throw new IOException(context.isCancelled() ? "Resolución Nauta cancelada."
                    : "No se pudo abrir Nauta dentro del plazo. Reintenta.");
        }
    }

    static String[] parseLocator(String value) throws IOException {
        int separator = value.indexOf('|');
        if (separator < 1) throw new IOException("Localizador Nauta inválido.");
        String catalog = value.substring(0, separator), name = value.substring(separator + 1);
        if (!catalog.matches("cat_[0-9]+|nautatv_catalog") || name.isEmpty() || name.length() > 200
                || name.contains("://") || name.matches("(?s).*[\\\\?#\\p{Cntrl}].*")) {
            throw new IOException("Localizador Nauta inválido.");
        }
        return new String[]{catalog, name};
    }

    private List<String> idsFor(String catalog, String name, boolean refresh)
            throws IOException, JSONException {
        String key = catalog.equals("nautatv_catalog") ? catalog + "|" + name : catalog;
        CachedCatalog cached;
        long epoch;
        synchronized (catalogs) { cached = catalogs.get(key); epoch = generation; }
        if (refresh || cached == null || System.currentTimeMillis() - cached.fetchedAt >= CATALOG_TTL_MS) {
            String suffix = catalog.equals("nautatv_catalog") ? "/search=" + encode(name) : "";
            JSONArray metas = json(BASE + "/catalog/tv/" + encode(catalog) + suffix + ".json")
                    .optJSONArray("metas");
            if (metas == null || metas.length() > 2000) throw new IOException("Catálogo Nauta inválido.");
            Map<String, List<String>> names = new LinkedHashMap<>();
            for (int index = 0; index < metas.length(); index++) {
                JSONObject meta = metas.optJSONObject(index);
                if (meta == null || !"tv".equals(meta.optString("type", "tv"))) continue;
                String id = meta.optString("id", ""), exactName = meta.optString("name", "");
                if (id.isEmpty() || id.length() > 2048 || exactName.isEmpty()) continue;
                List<String> ids = names.computeIfAbsent(exactName, unused -> new ArrayList<>());
                if (ids.size() < 8 && !ids.contains(id)) ids.add(id);
            }
            cached = new CachedCatalog(names);
            synchronized (catalogs) {
                // No network while holding the lock: exiting the app cannot block on HTTP.
                if (epoch == generation) {
                    if (catalogs.size() >= 64 && !catalogs.containsKey(key)) catalogs.remove(catalogs.keySet().iterator().next());
                    catalogs.put(key, cached);
                }
            }
        }
        return new ArrayList<>(cached.names.getOrDefault(name, Collections.emptyList()));
    }

    private JSONObject json(String url) throws IOException, JSONException {
        TokenHttpClient.Response response = http.getPublicOnHosts(url,
                Collections.singletonMap("Accept", "application/json"), 4 * 1024 * 1024, null,
                Collections.singleton("stremio-addon-wheat.vercel.app"));
        return new JSONObject(new String(response.getBody(), StandardCharsets.UTF_8));
    }
    static boolean hasDrm(JSONObject stream) {
        JSONObject hints = stream.optJSONObject("behaviorHints");
        for (String key : new String[]{"drm", "drmKey", "licenseUrl", "externalUrl", "keySystems"}) {
            if (stream.has(key) && !stream.isNull(key)) return true;
            if (hints != null && hints.has(key) && !hints.isNull(key)) return true;
        }
        // notWebReady means browser limitations, not Android DRM or a dead source.
        return false;
    }
    static boolean isKnownUpdateSlateOrigin(URI uri) {
        // 2026-10-07: two independent channels decoded the same mandatory-update
        // slate from this origin. A playable HLS is not proof of channel content.
        // Fail closed until fresh decoded-content evidence justifies removing this guard.
        return "tv.m3uts.xyz".equalsIgnoreCase(uri.getHost());
    }
    static Map<String, String> headersOf(JSONObject stream) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject hints = stream.optJSONObject("behaviorHints");
        JSONObject proxy = hints == null ? null : hints.optJSONObject("proxyHeaders");
        JSONObject request = proxy == null ? null : proxy.optJSONObject("request");
        if (request == null) return headers;
        Map<String, String> allowed = Map.of("user-agent", "User-Agent", "referer", "Referer", "origin", "Origin");
        for (java.util.Iterator<String> keys = request.keys(); keys.hasNext();) {
            String key = keys.next();
            String canonical = allowed.get(key.toLowerCase(Locale.ROOT));
            if (canonical == null) continue;
            Object raw = request.opt(key);
            if (!(raw instanceof String)) throw new IOException("Cabecera Nauta inválida.");
            String value = (String) raw;
            if (value.length() > 2048 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                throw new IOException("Cabecera Nauta inválida.");
            headers.put(canonical, value);
        }
        return headers;
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class CachedCatalog {
        final long fetchedAt = System.currentTimeMillis();
        final Map<String, List<String>> names;
        CachedCatalog(Map<String, List<String>> names) { this.names = names; }
    }
}

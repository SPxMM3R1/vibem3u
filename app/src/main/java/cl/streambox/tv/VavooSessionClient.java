package cl.streambox.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-memory client for the public Vavoo catalogue and short-lived resolver. */
final class VavooSessionClient {
    private static final int MAX_PING_BYTES = 256 * 1024;
    private static final int MAX_CATALOG_PAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RESOLVE_BYTES = 512 * 1024;
    private static final long SIGNATURE_REUSE_MS = 4 * 60 * 1000L;
    private static final long CATALOG_REUSE_MS = 30 * 60 * 1000L;
    private static final String CLIENT_VERSION = "3.0.2";
    private static final String APP_PACKAGE = "net.vypn.app";
    private static final String APP_VERSION = "1.4.1";
    private static final Set<String> PING_HOSTS = setOf("www.vavoo.tv", "www.vypn.net");
    private static final Set<String> API_HOSTS = setOf("vavoo.to", "kool.to");

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final String deviceId = UUID.randomUUID().toString();

    private String signature;
    private long signatureCreatedAt;
    private List<CatalogEntry> catalog = Collections.emptyList();
    private long catalogCreatedAt;
    private String activeBase;

    VavooSessionClient(ResolverDefinition definition) {
        this(definition, new TokenHttpClient());
    }

    VavooSessionClient(ResolverDefinition definition, TokenHttpClient httpClient) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.activeBase = definition.getConfig("catalogBase", "https://vavoo.to");
    }

    synchronized List<URI> resolveCandidates(Channel channel, List<String> aliases)
            throws IOException {
        if (channel == null) throw new IOException("Canal Vavoo ausente.");
        int maximum = definition.getIntConfig("maxResolveCandidates", 8, 1, 12);
        IOException firstError = null;
        for (int pass = 0; pass < 2; pass++) {
            try {
                String currentSignature = signature(pass > 0);
                List<CatalogEntry> entries = catalog(currentSignature, pass > 0);
                List<CatalogEntry> matches = rankedMatches(channel, aliases, entries);
                if (matches.isEmpty()) {
                    throw new IOException("El canal no aparece en el catálogo Vavoo.");
                }

                LinkedHashSet<URI> resolved = new LinkedHashSet<>();
                int attempted = 0;
                for (CatalogEntry match : matches) {
                    if (attempted++ >= maximum) break;
                    try {
                        URI candidate = resolveEntry(match, currentSignature);
                        if (candidate != null) resolved.add(candidate);
                    } catch (IOException error) {
                        if (firstError == null) firstError = error;
                    }
                }
                if (!resolved.isEmpty()) {
                    return Collections.unmodifiableList(new ArrayList<>(resolved));
                }
            } catch (IOException error) {
                if (firstError == null) firstError = error;
            }
            clearSessionOnly();
        }
        throw new IOException("Vavoo no entregó una fuente reproducible.", firstError);
    }

    synchronized void clear() {
        signature = null;
        signatureCreatedAt = 0L;
        catalog = Collections.emptyList();
        catalogCreatedAt = 0L;
    }

    private String signature(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && signature != null && now - signatureCreatedAt < SIGNATURE_REUSE_MS) {
            return signature;
        }
        String primary = checkedUrl(
                definition.getConfig("pingUrl", "https://www.vavoo.tv/api/app/ping"),
                PING_HOSTS,
                "/api/app/ping"
        );
        String fallback = checkedUrl(
                definition.getConfig("fallbackPingUrl", "https://www.vypn.net/api/app/ping"),
                PING_HOSTS,
                "/api/app/ping"
        );
        IOException failure = null;
        for (String endpoint : new String[]{primary, fallback}) {
            try {
                String response = httpClient.postJsonText(
                        endpoint,
                        pingHeaders(),
                        pingPayload().toString(),
                        MAX_PING_BYTES
                );
                JSONObject root = new JSONObject(response);
                String value = root.optString("addonSig", root.optString("mhub", "")).trim();
                if (value.length() < 32 || value.length() > 16 * 1024) {
                    throw new IOException("Vavoo no entregó una sesión válida.");
                }
                signature = value;
                signatureCreatedAt = now;
                return value;
            } catch (IOException | JSONException error) {
                failure = error instanceof IOException
                        ? (IOException) error
                        : new IOException("Vavoo devolvió una sesión inválida.", error);
            }
        }
        throw new IOException("No se pudo iniciar una sesión Vavoo.", failure);
    }

    private List<CatalogEntry> catalog(String currentSignature, boolean force)
            throws IOException {
        long now = System.currentTimeMillis();
        if (!force && !catalog.isEmpty() && now - catalogCreatedAt < CATALOG_REUSE_MS) {
            return catalog;
        }
        int maxPages = definition.getIntConfig("maxCatalogPages", 60, 1, 100);
        int maxItems = definition.getIntConfig("maxCatalogItems", 15000, 300, 20000);
        String cursor = null;
        List<CatalogEntry> loaded = new ArrayList<>();
        for (int page = 0; page < maxPages && loaded.size() < maxItems; page++) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("language", language());
                payload.put("region", region());
                payload.put("catalogId", "iptv");
                payload.put("id", "iptv");
                payload.put("adult", false);
                payload.put("search", "");
                payload.put("sort", "");
                payload.put("filter", new JSONObject());
                payload.put("cursor", cursor == null ? JSONObject.NULL : cursor);
                payload.put("clientVersion", CLIENT_VERSION);
            } catch (JSONException impossible) {
                throw new IOException("No se pudo preparar el catálogo Vavoo.", impossible);
            }

            JSONObject response = postApi(
                    catalogEndpoint(),
                    payload,
                    currentSignature,
                    MAX_CATALOG_PAGE_BYTES
            );
            JSONArray items = response.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            for (int index = 0; index < items.length() && loaded.size() < maxItems; index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null || !"iptv".equalsIgnoreCase(item.optString("type"))) continue;
                JSONObject ids = item.optJSONObject("ids");
                String id = ids == null ? "" : ids.optString("id", "").trim();
                String name = item.optString("name", "").trim();
                String url = item.optString("url", "").trim();
                String group = item.optString("group", "").trim();
                if (id.isBlank() || name.isBlank() || url.isBlank()) continue;
                loaded.add(new CatalogEntry(id, name, url, baseCountry(group), group));
            }
            cursor = response.optString("nextCursor", "").trim();
            if (cursor.isBlank()) break;
        }
        if (loaded.isEmpty()) throw new IOException("Vavoo devolvió un catálogo vacío.");
        catalog = Collections.unmodifiableList(loaded);
        catalogCreatedAt = now;
        return catalog;
    }

    private URI resolveEntry(CatalogEntry entry, String currentSignature) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("language", language());
            payload.put("region", region());
            payload.put("url", entry.sourceUrl);
            payload.put("clientVersion", CLIENT_VERSION);
        } catch (JSONException impossible) {
            throw new IOException("No se pudo preparar la resolución Vavoo.", impossible);
        }
        JSONObject object;
        String response = postApiText(
                resolveEndpoint(),
                payload,
                currentSignature,
                MAX_RESOLVE_BYTES
        );
        try {
            String candidate = "";
            String trimmed = response.trim();
            if (trimmed.startsWith("[")) {
                JSONArray array = new JSONArray(trimmed);
                object = array.optJSONObject(0);
            } else {
                object = new JSONObject(trimmed);
            }
            if (object != null) {
                candidate = object.optString(
                        "url",
                        object.optString("streamUrl", "")
                ).trim();
            }
            URI uri = candidate.isBlank() ? null : URI.create(candidate);
            if (uri == null || uri.getHost() == null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IOException("Vavoo devolvió una fuente inválida.");
            }
            return uri;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Vavoo devolvió una fuente inválida.", error);
        }
    }

    private JSONObject postApi(
            String endpoint,
            JSONObject payload,
            String currentSignature,
            int maximumBytes
    ) throws IOException {
        String text = postApiText(endpoint, payload, currentSignature, maximumBytes);
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException("Vavoo devolvió JSON inválido.", error);
        }
    }

    private String postApiText(
            String endpoint,
            JSONObject payload,
            String currentSignature,
            int maximumBytes
    ) throws IOException {
        try {
            return httpClient.postJsonText(
                    endpoint,
                    apiHeaders(currentSignature),
                    payload.toString(),
                    maximumBytes
            );
        } catch (TokenHttpClient.HttpStatusException error) {
            if (error.getStatusCode() != 451 && error.getStatusCode() != 502) throw error;
            switchBase();
            String retryEndpoint = endpoint.contains("mediahubmx-catalog")
                    ? catalogEndpoint()
                    : resolveEndpoint();
            return httpClient.postJsonText(
                    retryEndpoint,
                    apiHeaders(currentSignature),
                    payload.toString(),
                    maximumBytes
            );
        }
    }

    private List<CatalogEntry> rankedMatches(
            Channel channel,
            List<String> aliases,
            List<CatalogEntry> entries
    ) {
        List<Target> targets = targets(channel, aliases);
        List<ScoredEntry> scored = new ArrayList<>();
        String explicitId = channel.getAttributes().get("x-resolver-id");
        for (CatalogEntry entry : entries) {
            int best = explicitId != null && explicitId.equalsIgnoreCase(entry.id) ? 1000 : -1;
            for (Target target : targets) {
                int score = score(target, entry);
                if (score > best) best = score;
            }
            if (best >= 100) scored.add(new ScoredEntry(entry, best));
        }
        Collections.sort(scored, new Comparator<ScoredEntry>() {
            @Override
            public int compare(ScoredEntry left, ScoredEntry right) {
                int byScore = Integer.compare(right.score, left.score);
                return byScore != 0
                        ? byScore
                        : left.entry.name.compareTo(right.entry.name);
            }
        });
        List<CatalogEntry> result = new ArrayList<>();
        for (ScoredEntry value : scored) result.add(value.entry);
        return result;
    }

    private static int score(Target target, CatalogEntry entry) {
        String exactEntry = normalizedName(entry.name, false);
        String relaxedEntry = normalizedName(entry.name, true);
        int score;
        if (target.exactName.equals(exactEntry)) {
            score = 180;
        } else if (target.relaxedName.equals(relaxedEntry)) {
            score = 140;
        } else if (relaxedEntry.startsWith(target.relaxedName)
                || target.relaxedName.startsWith(relaxedEntry)) {
            score = 105;
        } else {
            return -1;
        }
        if (!target.country.isBlank()) {
            if (target.country.equals(countryKey(entry.country))) score += 80;
            else score -= 60;
        }
        String lower = entry.name.toLowerCase(Locale.ROOT);
        if (lower.contains("backup") || lower.contains("local")) score -= 10;
        if (lower.contains("hd") || lower.contains("fhd")) score += 4;
        return score;
    }

    private static List<Target> targets(Channel channel, List<String> aliases) {
        LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
        if (aliases != null) {
            for (String alias : aliases) {
                Target target = targetFromAlias(alias);
                if (target != null) targets.put(target.key(), target);
            }
        }
        String name = channel.getAttributes().get("tvg-name");
        if (name == null || name.isBlank()) name = channel.getName();
        String country = countryKey(channel.getAttributes().get("tvg-country"));
        Target channelTarget = new Target(name, country);
        if (!channelTarget.relaxedName.isBlank()) targets.put(channelTarget.key(), channelTarget);
        return new ArrayList<>(targets.values());
    }

    static Target targetFromAlias(String alias) {
        if (alias == null || alias.isBlank()) return null;
        try {
            String decoded = URLDecoder.decode(alias.trim(), StandardCharsets.UTF_8.name());
            if (decoded.regionMatches(true, 0, "vavoo_", 0, 6)) decoded = decoded.substring(6);
            String[] parts = decoded.split("(?i)\\|group:", 2);
            Target target = new Target(parts[0], parts.length > 1 ? countryKey(parts[1]) : "");
            return target.relaxedName.isBlank() ? null : target;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject pingPayload() throws IOException {
        long now = System.currentTimeMillis();
        try {
            JSONObject device = new JSONObject()
                    .put("type", "phone")
                    .put("uniqueId", deviceId);
            JSONObject os = new JSONObject()
                    .put("name", "android")
                    .put("version", "14")
                    .put("abis", new JSONArray().put("arm64-v8a"))
                    .put("host", "android");
            JSONObject metadata = new JSONObject()
                    .put("device", device)
                    .put("os", os)
                    .put("app", new JSONObject().put("platform", "android"))
                    .put("version", new JSONObject()
                            .put("package", APP_PACKAGE)
                            .put("binary", APP_VERSION)
                            .put("js", APP_VERSION));
            return new JSONObject()
                    .put("token", "")
                    .put("reason", "app-focus")
                    .put("locale", language())
                    .put("theme", "dark")
                    .put("metadata", metadata)
                    .put("appFocusTime", 0)
                    .put("playerActive", false)
                    .put("playDuration", 0)
                    .put("devMode", false)
                    .put("hasAddon", true)
                    .put("castConnected", false)
                    .put("package", APP_PACKAGE)
                    .put("version", APP_VERSION)
                    .put("process", "app")
                    .put("firstAppStart", now - 86_400_000L)
                    .put("lastAppStart", now)
                    .put("ipLocation", JSONObject.NULL)
                    .put("adblockEnabled", true)
                    .put("migrationApplied", false)
                    .put("migrationTargetInstalled", false)
                    .put("proxy", new JSONObject()
                            .put("supported", new JSONArray().put("ss"))
                            .put("engine", "Mu")
                            .put("ssVersion", "2022")
                            .put("enabled", false)
                            .put("autoServer", true)
                            .put("id", ""))
                    .put("iap", new JSONObject().put("supported", false).put("error", ""));
        } catch (JSONException error) {
            throw new IOException("No se pudo preparar la sesión Vavoo.", error);
        }
    }

    private Map<String, String> pingHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "okhttp/4.11.0");
        headers.put("Accept", "application/json");
        return headers;
    }

    private Map<String, String> apiHeaders(String currentSignature) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "MediaHubMX/2");
        headers.put("Accept", "application/json");
        headers.put("Accept-Language", language());
        headers.put("mediahubmx-signature", currentSignature);
        return headers;
    }

    private String catalogEndpoint() throws IOException {
        return apiEndpoint(definition.getConfig("catalogPath", "mediahubmx-catalog.json"));
    }

    private String resolveEndpoint() throws IOException {
        return apiEndpoint(definition.getConfig("resolvePath", "mediahubmx-resolve.json"));
    }

    private String apiEndpoint(String path) throws IOException {
        String base = checkedBase(activeBase);
        String cleanPath = path == null ? "" : path.trim();
        if (!cleanPath.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){0,3}")) {
            throw new IOException("Ruta Vavoo no permitida.");
        }
        return base + "/" + cleanPath;
    }

    private void switchBase() throws IOException {
        String primary = checkedBase(definition.getConfig("catalogBase", "https://vavoo.to"));
        String fallback = checkedBase(
                definition.getConfig("fallbackCatalogBase", "https://kool.to")
        );
        activeBase = activeBase.equals(primary) ? fallback : primary;
    }

    private static String checkedBase(String value) throws IOException {
        String checked = checkedUrl(value, API_HOSTS, null);
        URI uri = URI.create(checked);
        if (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
            throw new IOException("Base Vavoo no permitida.");
        }
        return checked.endsWith("/") ? checked.substring(0, checked.length() - 1) : checked;
    }

    private static String checkedUrl(String value, Set<String> hosts, String exactPath)
            throws IOException {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    || (exactPath != null && !exactPath.equals(uri.getPath()))) {
                throw new IOException("Endpoint Vavoo no permitido.");
            }
            return uri.toString();
        } catch (IllegalArgumentException error) {
            throw new IOException("Endpoint Vavoo inválido.", error);
        }
    }

    private void clearSessionOnly() {
        signature = null;
        signatureCreatedAt = 0L;
    }

    private static String language() {
        String language = Locale.getDefault().getLanguage();
        return language == null || language.isBlank() ? "en" : language;
    }

    private static String region() {
        String country = Locale.getDefault().getCountry();
        return country == null || country.isBlank() ? "US" : country.toUpperCase(Locale.ROOT);
    }

    private static String baseCountry(String group) {
        if (group == null) return "";
        String result = group.trim();
        for (String separator : new String[]{"➾", "⟾", "->", "→", "»", "›"}) {
            int position = result.indexOf(separator);
            if (position >= 0) result = result.substring(0, position).trim();
        }
        return result;
    }

    private static String normalizedName(String value, boolean relaxed) {
        if (value == null) return "";
        String result = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("(?i)\\s*\\.[bcs]\\s*$", "")
                .toLowerCase(Locale.ROOT);
        if (relaxed) {
            result = result
                    .replaceAll("(?i)\\([^)]*(backup|local|event)[^)]*\\)", " ")
                    .replaceAll("(?i)\\b(fhd|uhd|hd|sd|hevc|h265|raw|4k|backup|local)\\b", " ");
        }
        return result.replaceAll("[^a-z0-9]+", "").trim();
    }

    private static String countryKey(String value) {
        String key = normalizedName(value, false);
        return switch (key) {
            case "uk", "gb", "greatbritain", "unitedkingdom" -> "unitedkingdom";
            case "us", "usa", "unitedstates" -> "unitedstates";
            case "ar", "argentina" -> "argentina";
            case "es", "spain", "espana" -> "spain";
            case "fr", "france" -> "france";
            case "de", "germany", "deutschland" -> "germany";
            case "it", "italy", "italia" -> "italy";
            case "pt", "portugal" -> "portugal";
            case "nl", "netherlands" -> "netherlands";
            case "pl", "poland" -> "poland";
            case "tr", "turkey", "turkiye" -> "turkey";
            case "ie", "ireland" -> "ireland";
            default -> key;
        };
    }

    private static Set<String> setOf(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    static final class Target {
        final String exactName;
        final String relaxedName;
        final String country;

        Target(String name, String country) {
            this.exactName = normalizedName(name, false);
            this.relaxedName = normalizedName(name, true);
            this.country = country == null ? "" : country;
        }

        String key() {
            return exactName + "|" + country;
        }
    }

    private static final class CatalogEntry {
        final String id;
        final String name;
        final String sourceUrl;
        final String country;
        final String group;

        CatalogEntry(String id, String name, String sourceUrl, String country, String group) {
            this.id = id;
            this.name = name;
            this.sourceUrl = sourceUrl;
            this.country = country;
            this.group = group;
        }
    }

    private static final class ScoredEntry {
        final CatalogEntry entry;
        final int score;

        ScoredEntry(CatalogEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }
}

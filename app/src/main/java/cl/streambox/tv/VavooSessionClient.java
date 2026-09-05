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
    private static final long CATALOG_REUSE_MS = 30 * 60 * 1000L;
    private static final int DEFAULT_RESOLUTION_BUDGET_MS = 10_000;
    private static final String CLIENT_VERSION = "3.0.2";
    private static final String APP_PACKAGE = "net.vypn.app";
    private static final String APP_VERSION = "1.4.1";
    private static final Set<String> PING_HOSTS = setOf("www.vavoo.tv", "www.vypn.net");
    private static final Set<String> API_HOSTS = setOf("vavoo.to", "kool.to");

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final String deviceId = UUID.randomUUID().toString();
    private final Object stateLock = new Object();

    /* These values are session memory only. Never persist signatures or URLs. */
    private String signature;
    private long signatureCreatedAt;
    private final Map<String, CachedCatalog> catalogsByTarget = new LinkedHashMap<>();
    private final Map<String, String> winningIdentityByChannel = new LinkedHashMap<>();
    private String activeBase;

    VavooSessionClient(ResolverDefinition definition) {
        this(definition, new TokenHttpClient());
    }

    VavooSessionClient(ResolverDefinition definition, TokenHttpClient httpClient) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.activeBase = definition.getConfig("catalogBase", "https://vavoo.to");
    }

    /** Compatibility collector; the resolver uses the streaming overload. */
    List<URI> resolveCandidates(Channel channel, List<String> aliases)
            throws IOException {
        return resolveCandidates(channel, aliases, ResolutionProgressListener.NONE);
    }

    /** Compatibility collector; it still receives each source as soon as it arrives. */
    List<URI> resolveCandidates(
            Channel channel,
            List<String> aliases,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolutionDeadline deadline = new ResolutionDeadline(
                definition.getIntConfig(
                        "resolutionBudgetMs",
                        DEFAULT_RESOLUTION_BUDGET_MS,
                        2_000,
                        20_000
                )
        );
        List<URI> result = new ArrayList<>();
        streamCandidates(
                channel,
                aliases,
                listener,
                deadline,
                (candidate, identity) -> {
                    result.add(candidate);
                    return false;
                }
        );
        return Collections.unmodifiableList(result);
    }

    /** Receives a fresh resolved URL and its stable catalogue identity. */
    interface CandidateSink {
        /** Return true to stop the catalogue walk after this candidate. */
        boolean onCandidate(URI candidate, String stableIdentity) throws IOException;
    }

    /**
     * Streams source POST results to the caller. Network work is intentionally
     * outside {@link #stateLock}; only short memory reads/writes use the lock.
     */
    boolean streamCandidates(
            Channel channel,
            List<String> aliases,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline,
            CandidateSink sink
    ) throws IOException {
        if (channel == null) throw new IOException("Canal Vavoo ausente.");
        if (deadline == null) throw new IOException("Presupuesto de resolución ausente.");
        if (sink == null) throw new IOException("Destino de candidato ausente.");
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        deadline.check();
        List<Target> targets = targets(channel, aliases);
        IOException firstError = null;
        try {
            for (int pass = 0; pass < 2; pass++) {
                deadline.check();
                try {
                    // A signature is authorization material. Generate a new
                    // one for every channel open and retain it only while this
                    // resolve call is active.
                    progress.onProgress(ResolutionProgress.of(ResolutionStage.SESSION));
                    String currentSignature = signature(true, progress, deadline);
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.CATALOG_REQUEST,
                            "Catálogo · preparando búsqueda JSON"
                    ));
                    List<CatalogEntry> entries = catalog(
                            currentSignature,
                            targets,
                            pass > 0,
                            progress,
                            deadline
                    );
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.CATALOG_PARSED,
                            "JSON válido · entradas=" + entries.size()
                    ));
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.CATALOG_MATCHING,
                            "comparando nombre exacto/normalizado + país + numeración"
                    ));
                    List<CatalogEntry> matches = rankedMatches(channel, aliases, entries);
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.CATALOG_MATCHING,
                            "coincidencias utilizables=" + matches.size()
                                    + " de " + entries.size()
                    ));
                    if (matches.isEmpty()) {
                        throw new IOException("El canal no aparece en el catálogo Vavoo.");
                    }

                    int maximum = definition.getIntConfig(
                            "maxResolveCandidates",
                            8,
                            1,
                            12
                    );
                    int attempted = 0;
                    for (CatalogEntry match : matches) {
                        if (attempted++ >= maximum) break;
                        deadline.check();
                        progress.onProgress(ResolutionProgress.counted(
                                ResolutionStage.SOURCE_REQUEST,
                                attempted,
                                Math.min(matches.size(), maximum),
                                "resolución " + attempted + "/"
                                        + Math.min(matches.size(), maximum)
                                        + " · source de catálogo · id=" + match.id
                        ));
                        try {
                            // Resolve and publish one URL immediately. The
                            // caller can validate it and stop before another
                            // source POST is started.
                            URI candidate = resolveEntry(
                                    match,
                                    currentSignature,
                                    progress,
                                    deadline
                            );
                            if (candidate == null) continue;
                            boolean stop = sink.onCandidate(candidate, match.id);
                            if (stop) {
                                rememberWinningIdentity(channel, match.id);
                                return true;
                            }
                        } catch (IOException error) {
                            if (firstError == null) firstError = error;
                        }
                    }
                } catch (IOException error) {
                    if (firstError == null) firstError = error;
                } finally {
                    clearSessionOnly();
                }
            }
        } finally {
            clearSessionOnly();
        }
        throw new IOException("Vavoo no entregó una fuente reproducible.", firstError);
    }

    void clear() {
        synchronized (stateLock) {
            signature = null;
            signatureCreatedAt = 0L;
            catalogsByTarget.clear();
            winningIdentityByChannel.clear();
        }
    }

    private String signature(
            boolean force,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        long now = System.currentTimeMillis();
        if (!force) {
            synchronized (stateLock) {
                if (signature != null) return signature;
            }
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
                deadline.check();
                listener.onProgress(ResolutionProgress.of(
                        ResolutionStage.SESSION,
                        "POST " + SafePlaybackText.url(endpoint)
                                + " · generando firma en memoria"
                ));
                String response = withDeadline(deadline, () -> httpClient.postJsonText(
                        endpoint,
                        pingHeaders(),
                        pingPayload().toString(),
                        MAX_PING_BYTES
                ));
                JSONObject root = new JSONObject(response);
                String value = root.optString("addonSig", root.optString("mhub", "")).trim();
                if (value.length() < 32 || value.length() > 16 * 1024) {
                    throw new IOException("Vavoo no entregó una sesión válida.");
                }
                synchronized (stateLock) {
                    signature = value;
                    signatureCreatedAt = now;
                }
                listener.onProgress(ResolutionProgress.of(
                        ResolutionStage.SESSION,
                        "JSON válido · addonSig recibido · firma solo en memoria"
                ));
                return value;
            } catch (IOException | JSONException error) {
                failure = error instanceof IOException
                        ? (IOException) error
                        : new IOException("Vavoo devolvió una sesión inválida.", error);
            }
        }
        throw new IOException("No se pudo iniciar una sesión Vavoo.", failure);
    }

    private List<CatalogEntry> catalog(
            String currentSignature,
            List<Target> targets,
            boolean force,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        int maxTargets = definition.getIntConfig("maxSearchTargets", 4, 1, 8);
        LinkedHashMap<String, CatalogEntry> loaded = new LinkedHashMap<>();
        IOException lastError = null;
        int searched = 0;
        for (Target target : targets) {
            if (searched++ >= maxTargets) break;
            deadline.check();
            try {
                for (CatalogEntry entry : catalogForTarget(
                        currentSignature,
                        target,
                        force,
                        listener,
                        deadline
                )) {
                    loaded.put(entry.id + "\n" + entry.sourceUrl, entry);
                }
            } catch (IOException error) {
                lastError = error;
            }
        }
        if (loaded.isEmpty()) {
            throw new IOException("Vavoo devolvió un catálogo vacío.", lastError);
        }
        return Collections.unmodifiableList(new ArrayList<>(loaded.values()));
    }

    private List<CatalogEntry> catalogForTarget(
            String currentSignature,
            Target target,
            boolean force,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        CachedCatalog cached;
        synchronized (stateLock) {
            cached = catalogsByTarget.get(target.key());
        }
        long now = System.currentTimeMillis();
        if (!force && cached != null && now - cached.createdAt < CATALOG_REUSE_MS) {
            listener.onProgress(ResolutionProgress.of(
                    ResolutionStage.CATALOG_PARSED,
                    "memoria · reutilizando catálogo para búsqueda="
                            + target.searchName
            ));
            return cached.entries;
        }
        List<CatalogEntry> loaded = searchCatalog(
                currentSignature,
                target,
                true,
                listener,
                deadline
        );
        if (loaded.isEmpty() && !target.country.isBlank()) {
            loaded = searchCatalog(
                    currentSignature,
                    target,
                    false,
                    listener,
                    deadline
            );
        }
        List<CatalogEntry> immutable = Collections.unmodifiableList(loaded);
        synchronized (stateLock) {
            catalogsByTarget.put(target.key(), new CachedCatalog(immutable, now));
        }
        return immutable;
    }

    private List<CatalogEntry> searchCatalog(
            String currentSignature,
            Target target,
            boolean filterCountry,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        int maxPages = definition.getIntConfig("maxSearchPages", 2, 1, 4);
        int maxItems = definition.getIntConfig("maxSearchItems", 100, 10, 300);
        String cursor = null;
        List<CatalogEntry> loaded = new ArrayList<>();
        for (int page = 0; page < maxPages && loaded.size() < maxItems; page++) {
            deadline.check();
            String endpoint = catalogEndpoint();
            progress.onProgress(ResolutionProgress.counted(
                    ResolutionStage.CATALOG_PAGE,
                    page + 1,
                    maxPages,
                    "POST " + SafePlaybackText.url(endpoint)
                            + " · search=\"" + target.searchName + "\""
                            + (filterCountry && !target.country.isBlank()
                            ? " · filter.group=" + countryGroup(target.country)
                            : " · sin filtro de país")
            ));
            JSONObject payload = new JSONObject();
            try {
                payload.put("language", language());
                payload.put("region", region());
                payload.put("catalogId", "iptv");
                payload.put("id", "iptv");
                payload.put("adult", false);
                payload.put("search", target.searchName);
                payload.put("sort", "");
                JSONObject filter = new JSONObject();
                if (filterCountry && !target.country.isBlank()) {
                    filter.put("group", countryGroup(target.country));
                }
                payload.put("filter", filter);
                payload.put("cursor", cursor == null ? JSONObject.NULL : cursor);
                payload.put("clientVersion", CLIENT_VERSION);
            } catch (JSONException impossible) {
                throw new IOException("No se pudo preparar el catálogo Vavoo.", impossible);
            }

            JSONObject response = postApi(
                    endpoint,
                    payload,
                    currentSignature,
                    MAX_CATALOG_PAGE_BYTES,
                    deadline
            );
            JSONArray items = response.optJSONArray("items");
            if (items == null || items.length() == 0) {
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.CATALOG_PARSED,
                        "JSON válido · items=0 · fin de búsqueda"
                ));
                break;
            }
            for (int index = 0; index < items.length() && loaded.size() < maxItems; index++) {
                deadline.check();
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
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.CATALOG_PARSED,
                    "JSON válido · items=" + items.length()
                            + " · acumulados=" + loaded.size()
            ));
            cursor = response.optString("nextCursor", "").trim();
            if (cursor.isBlank()) break;
        }
        return loaded;
    }

    private URI resolveEntry(
            CatalogEntry entry,
            String currentSignature,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("language", language());
            payload.put("region", region());
            payload.put("url", entry.sourceUrl);
            payload.put("clientVersion", CLIENT_VERSION);
        } catch (JSONException impossible) {
            throw new IOException("No se pudo preparar la resolución Vavoo.", impossible);
        }
        String endpoint = resolveEndpoint();
        listener.onProgress(ResolutionProgress.of(
                ResolutionStage.SOURCE_REQUEST,
                "POST " + SafePlaybackText.url(endpoint)
                        + " · id=" + entry.id + " · solicitando source"
        ));
        String response = postApiText(
                endpoint,
                payload,
                currentSignature,
                MAX_RESOLVE_BYTES,
                deadline
        );
        listener.onProgress(ResolutionProgress.of(
                ResolutionStage.SOURCE_REQUEST,
                "JSON válido · source recibida solo en memoria · id=" + entry.id
        ));
        try {
            String candidate = "";
            String trimmed = response.trim();
            JSONObject object;
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
            int maximumBytes,
            ResolutionDeadline deadline
    ) throws IOException {
        String text = postApiText(
                endpoint,
                payload,
                currentSignature,
                maximumBytes,
                deadline
        );
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
            int maximumBytes,
            ResolutionDeadline deadline
    ) throws IOException {
        try {
            return withDeadline(deadline, () -> httpClient.postJsonText(
                    endpoint,
                    apiHeaders(currentSignature),
                    payload.toString(),
                    maximumBytes
            ));
        } catch (TokenHttpClient.HttpStatusException error) {
            if (error.getStatusCode() != 451 && error.getStatusCode() != 502) throw error;
            deadline.check();
            switchBase();
            String retryEndpoint = endpoint.contains("mediahubmx-catalog")
                    ? catalogEndpoint()
                    : resolveEndpoint();
            return withDeadline(deadline, () -> httpClient.postJsonText(
                    retryEndpoint,
                    apiHeaders(currentSignature),
                    payload.toString(),
                    maximumBytes
            ));
        }
    }

    /** Runs one network operation in a child context bounded by the shared deadline. */
    private <T> T withDeadline(
            ResolutionDeadline deadline,
            IoOperation<T> operation
    ) throws IOException {
        deadline.check();
        ResolutionContext parent = ResolutionContext.current();
        ResolutionContext context = parent == null
                ? new ResolutionContext(deadline.remainingMillis())
                : parent.child(deadline.remainingMillis());
        try (ResolutionContext.Scope ignored = context.activate()) {
            deadline.check();
            context.check();
            try {
                return operation.call();
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("No se pudo completar la solicitud Vavoo.", error);
            }
        }
    }

    private interface IoOperation<T> {
        T call() throws Exception;
    }

    private List<CatalogEntry> rankedMatches(
            Channel channel,
            List<String> aliases,
            List<CatalogEntry> entries
    ) {
        List<Target> targets = targets(channel, aliases);
        List<ScoredEntry> scored = new ArrayList<>();
        String explicitId = attribute(channel, "x-resolver-id");
        String rememberedId = rememberedWinningIdentity(channel);
        for (CatalogEntry entry : entries) {
            if (!strictChannelCompatible(channel, entry)) continue;
            int best = -1;
            // A stable identity is authoritative for the name lookup. The
            // channel-level country/number guard above still applies, so an
            // ID can rescue a renamed catalogue entry without crossing a
            // declared regional or numeric boundary.
            if (!explicitId.isBlank() && explicitId.equalsIgnoreCase(entry.id)) {
                best = 2_000;
            }
            if (!rememberedId.isBlank() && rememberedId.equalsIgnoreCase(entry.id)) {
                best = 3_000;
            }
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
                if (byScore != 0) return byScore;
                int byId = left.entry.id.compareToIgnoreCase(right.entry.id);
                return byId != 0 ? byId : left.entry.name.compareTo(right.entry.name);
            }
        });
        List<CatalogEntry> result = new ArrayList<>();
        for (ScoredEntry value : scored) result.add(value.entry);
        return result;
    }

    private static int score(Target target, CatalogEntry entry) {
        if (!strictCompatible(target, entry)) return -1;

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
        if (target.countryDeclared) score += 80;
        if (target.numberDeclared) score += 80;
        String lower = entry.name.toLowerCase(Locale.ROOT);
        if (lower.contains("backup") || lower.contains("local")) score -= 10;
        if (lower.contains("hd") || lower.contains("fhd")) score += 4;
        return score;
    }

    private static boolean strictCompatible(Target target, CatalogEntry entry) {
        if (target == null || entry == null) return false;
        if (target.numberDeclared
                && !target.number.equals(extractNumber(entry.name))) return false;
        return !target.countryDeclared
                || target.country.equals(countryKey(entry.country));
    }

    /** Channel metadata is authoritative even when an alias is less specific. */
    private static boolean strictChannelCompatible(Channel channel, CatalogEntry entry) {
        String country = countryKey(attribute(channel, "tvg-country"));
        String name = attribute(channel, "tvg-name");
        if (name.isBlank() && channel != null) name = channel.getName();
        String declaredNumber = firstNonBlank(
                attribute(channel, "tvg-number"),
                attribute(channel, "channel-number"),
                attribute(channel, "x-resolver-number")
        );
        return strictChannelCompatible(
                name,
                country,
                declaredNumber,
                entry.name,
                entry.country
        );
    }

    /** Package-visible for resolver contract tests; uses the same production rule. */
    static boolean strictChannelCompatible(
            String channelName,
            String channelCountry,
            String channelNumber,
            String candidateName,
            String candidateCountry
    ) {
        String country = countryKey(channelCountry);
        if (!country.isBlank() && !country.equals(countryKey(candidateCountry))) return false;
        String declaredNumber = channelNumber == null ? "" : channelNumber.trim();
        if (declaredNumber.isBlank()) declaredNumber = extractNumber(channelName);
        return declaredNumber.isBlank()
                || declaredNumber.replaceAll("[^0-9]", "")
                .equals(extractNumber(candidateName));
    }

    private static List<Target> targets(Channel channel, List<String> aliases) {
        LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
        if (aliases != null) {
            for (String alias : aliases) {
                Target target = targetFromAlias(alias);
                if (target != null) targets.put(target.key(), target);
            }
        }
        String name = attribute(channel, "tvg-name");
        if (name.isBlank()) name = channel.getName();
        String country = countryKey(attribute(channel, "tvg-country"));
        String declaredNumber = firstNonBlank(
                attribute(channel, "tvg-number"),
                attribute(channel, "channel-number"),
                attribute(channel, "x-resolver-number")
        );
        Target channelTarget = new Target(
                name,
                country,
                declaredNumber.isBlank() ? extractNumber(name) : declaredNumber
        );
        if (!channelTarget.relaxedName.isBlank()) targets.put(channelTarget.key(), channelTarget);
        return new ArrayList<>(targets.values());
    }

    static Target targetFromAlias(String alias) {
        if (alias == null || alias.isBlank()) return null;
        try {
            String decoded = URLDecoder.decode(alias.trim(), StandardCharsets.UTF_8.name());
            if (decoded.regionMatches(true, 0, "vavoo_", 0, 6)) {
                decoded = decoded.substring(6);
            }
            String[] parts = decoded.split("\\|");
            String name = parts.length == 0 ? "" : parts[0];
            String country = "";
            String number = "";
            for (int index = 1; index < parts.length; index++) {
                String part = parts[index].trim();
                int separator = part.indexOf(':');
                if (separator <= 0) continue;
                String key = part.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = part.substring(separator + 1).trim();
                if ("group".equals(key) || "country".equals(key)) {
                    country = countryKey(value);
                } else if ("number".equals(key)
                        || "channel".equals(key)
                        || "channelnumber".equals(key)
                        || "num".equals(key)) {
                    number = value;
                }
            }
            Target target = new Target(name, country, number);
            return target.relaxedName.isBlank() ? null : target;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void rememberWinningIdentity(Channel channel, String identity) {
        if (identity == null || identity.isBlank()) return;
        synchronized (stateLock) {
            winningIdentityByChannel.put(channelIdentity(channel), identity.trim());
        }
    }

    private String rememberedWinningIdentity(Channel channel) {
        synchronized (stateLock) {
            String value = winningIdentityByChannel.get(channelIdentity(channel));
            return value == null ? "" : value;
        }
    }

    private static String channelIdentity(Channel channel) {
        String configured = attribute(channel, "x-resolver-id");
        if (!configured.isBlank()) return "id:" + configured.toLowerCase(Locale.ROOT);
        String tvgId = channel == null ? "" : channel.getTvgId();
        if (tvgId != null && !tvgId.isBlank()) {
            return "tvg:" + tvgId.trim().toLowerCase(Locale.ROOT);
        }
        return "name:" + (channel == null || channel.getName() == null
                ? ""
                : channel.getName().trim().toLowerCase(Locale.ROOT));
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
        String base;
        synchronized (stateLock) {
            base = activeBase;
        }
        String checked = checkedBase(base);
        String cleanPath = path == null ? "" : path.trim();
        if (!cleanPath.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){0,3}")) {
            throw new IOException("Ruta Vavoo no permitida.");
        }
        return checked + "/" + cleanPath;
    }

    private void switchBase() throws IOException {
        String primary = checkedBase(definition.getConfig("catalogBase", "https://vavoo.to"));
        String fallback = checkedBase(
                definition.getConfig("fallbackCatalogBase", "https://kool.to")
        );
        synchronized (stateLock) {
            activeBase = activeBase.equals(primary) ? fallback : primary;
        }
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
        synchronized (stateLock) {
            signature = null;
            signatureCreatedAt = 0L;
        }
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

    private static String extractNumber(String value) {
        if (value == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|\\D)(\\d+)(?:\\D|$)")
                .matcher(value);
        return matcher.find() ? matcher.group(1) : "";
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

    private static String countryGroup(String value) {
        return switch (countryKey(value)) {
            case "unitedkingdom" -> "United Kingdom";
            case "unitedstates" -> "United States";
            case "argentina" -> "Argentina";
            case "spain" -> "Spain";
            case "france" -> "France";
            case "germany" -> "Germany";
            case "italy" -> "Italy";
            case "portugal" -> "Portugal";
            case "netherlands" -> "Netherlands";
            case "poland" -> "Poland";
            case "turkey" -> "Turkey";
            case "ireland" -> "Ireland";
            default -> value;
        };
    }

    private static String attribute(Channel channel, String key) {
        if (channel == null || channel.getAttributes() == null) return "";
        String value = channel.getAttributes().get(key);
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static Set<String> setOf(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    static final class Target {
        final String searchName;
        final String exactName;
        final String relaxedName;
        final String country;
        final String number;
        final boolean countryDeclared;
        final boolean numberDeclared;

        Target(String name, String country) {
            this(name, country, "");
        }

        Target(String name, String country, String number) {
            this.searchName = name == null ? "" : name.trim();
            this.exactName = normalizedName(name, false);
            this.relaxedName = normalizedName(name, true);
            this.country = country == null ? "" : countryKey(country);
            this.number = number == null || number.isBlank()
                    ? extractNumber(name)
                    : number.replaceAll("[^0-9]", "");
            this.countryDeclared = !this.country.isBlank();
            this.numberDeclared = !this.number.isBlank();
        }

        String key() {
            return exactName + "|" + country + "|" + number;
        }
    }

    private static final class CachedCatalog {
        final List<CatalogEntry> entries;
        final long createdAt;

        CachedCatalog(List<CatalogEntry> entries, long createdAt) {
            this.entries = entries;
            this.createdAt = createdAt;
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

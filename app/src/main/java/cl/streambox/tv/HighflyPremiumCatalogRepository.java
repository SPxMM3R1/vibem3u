package cl.streambox.tv;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the Highfly Premium Stremio catalogue when temporary events need to be
 * shown and resolves short-lived streams at playback time. Stable channel
 * metadata comes from Lista 3/GitHub and is cached by PlaylistRepository;
 * manifests, signed URLs and the credential are never written to a resource
 * cache.
 */
public final class HighflyPremiumCatalogRepository {
    private static final long CATALOG_MEMORY_TTL_MS = 45_000L;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CATALOG_REQUESTS = 2;
    private static final Pattern SAFE_STABLE_SLUG = Pattern.compile(
            "(?i)^[a-z0-9][a-z0-9_-]{1,127}$"
    );
    private static final Set<String> PREMIUM_HOSTS = setOf(
            "premium.highfly.dev",
            "premium-us1.highfly.dev",
            "premium-us2.highfly.dev",
            "premium-eu1.highfly.dev"
    );

    private final android.content.Context context;
    private final HighflyPremiumCredentialStore credentialStore;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;
    private final Object sessionLock = new Object();
    private Session session;

    public HighflyPremiumCatalogRepository(android.content.Context context) {
        this(
                context,
                HighflyPremiumCredentialStore.getInstance(context),
                new TokenHttpClient(),
                new HlsStreamValidator()
        );
    }

    HighflyPremiumCatalogRepository(
            android.content.Context context,
            HighflyPremiumCredentialStore credentialStore,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this.context = context == null ? null : context.getApplicationContext();
        this.credentialStore = credentialStore;
        this.httpClient = httpClient;
        this.validator = validator;
    }

    public boolean hasCredential() {
        return credentialStore != null && credentialStore.hasCredential();
    }

    public AccountInfo verifyToken(
            String rawToken,
            HighflyPremiumPreferences.Region region
    ) throws IOException {
        String token = HighflyPremiumTokenRules.normalize(rawToken);
        HighflyPremiumPreferences.Region safeRegion = safeRegion(region);
        String endpoint = endpoint(safeRegion, token, "verify.json");
        String json = getJson(endpoint, 64 * 1024);
        AccountInfo account = parseAccount(json);
        if (!account.isUsable()) throw new CredentialRejectedException(0);
        return account;
    }

    public AccountInfo verifyStoredCredential(
            HighflyPremiumPreferences.Region region
    ) throws IOException {
        String token = credentialStore == null ? null : credentialStore.readTokenForRequest();
        if (token == null || token.isBlank()) {
            throw new CredentialRejectedException(0);
        }
        AccountInfo account = verifyToken(token, region);
        credentialStore.recordVerification(account);
        return account;
    }

    public HighflyPremiumCatalog queryCatalog(
            HighflyPremiumPreferences.Region region,
            boolean includeEvents,
            boolean forceRefresh
    ) throws IOException {
        if (credentialStore == null) throw new CredentialRejectedException(0);
        String token = credentialStore.readTokenForRequest();
        if (token == null || token.isBlank()) throw new CredentialRejectedException(0);
        HighflyPremiumPreferences.Region safeRegion = safeRegion(region);
        long generation = credentialStore.getGeneration();
        long now = System.currentTimeMillis();
        synchronized (sessionLock) {
            if (!forceRefresh && session != null
                    && session.credentialGeneration == generation
                    && session.region == safeRegion
                    && session.includeEvents == includeEvents
                    && now - session.loadedAtMillis < CATALOG_MEMORY_TTL_MS) {
                return session.catalog;
            }
        }

        try {
            AccountInfo account = verifyToken(token, safeRegion);
            credentialStore.recordVerification(account);
            String manifestJson = getJson(
                    endpoint(safeRegion, token, "manifest.json"),
                    HighflyPremiumPayloadParser.MAX_MANIFEST_BYTES
            );
            HighflyPremiumPayloadParser.ManifestInfo manifest =
                    HighflyPremiumPayloadParser.parseManifest(manifestJson);
            List<String> catalogIds = selectCatalogIds(manifest, includeEvents);
            if (catalogIds.isEmpty()) {
                throw new IOException("Highfly Premium no publicó un catálogo utilizable.");
            }

            LinkedHashSet<String> allIds = new LinkedHashSet<>();
            List<HighflyPremiumCatalog.Entry> entries = new ArrayList<>();
            for (String catalogId : catalogIds) {
                String catalogJson = getJson(
                        endpoint(safeRegion, token, "catalog/sport/" + catalogId + ".json"),
                        MAX_JSON_BYTES
                );
                for (HighflyPremiumCatalog.Entry entry
                        : HighflyPremiumPayloadParser.parseCatalog(catalogJson)) {
                    if (allIds.add(entry.getId())) entries.add(entry);
                }
            }
            if (entries.isEmpty()) {
                throw new IOException("Highfly Premium no tiene señales publicadas.");
            }
            HighflyPremiumCatalog result = new HighflyPremiumCatalog(
                    entries,
                    safeRegion,
                    System.currentTimeMillis()
            );
            synchronized (sessionLock) {
                session = new Session(result, generation, safeRegion, includeEvents);
            }
            return result;
        } catch (CredentialRejectedException error) {
            credentialStore.recordInvalid();
            throw error;
        }
    }

    public Playlist loadPlaylistForDisplay(
            HighflyPremiumPreferences.Region region,
            boolean includeEvents,
            boolean forceRefresh
    ) throws IOException {
        return queryCatalog(region, includeEvents, forceRefresh).toPlaylist(includeEvents);
    }

    /**
     * Loads the Premium catalog for the event selector. The generated stable
     * view is retained as a compatibility/preview object, but production
     * playback receives stable metadata from the public Lista 3 source. The
     * event view contains only selected identities and placeholders, never a
     * signed HLS URL.
     */
    public PremiumPlaylists loadPlaylistsForDisplay(
            HighflyPremiumPreferences.Region region,
            boolean includeEvents,
            Set<String> selectedEventIds,
            boolean forceRefresh
    ) throws IOException {
        HighflyPremiumCatalog catalog = queryCatalog(region, includeEvents, forceRefresh);
        return new PremiumPlaylists(
                catalog,
                catalog.toStablePlaylist(),
                catalog.toEventsPlaylist(selectedEventIds)
        );
    }

    /** Resolves one catalog entry by ID and validates only its current HLS sources. */
    public ResolvedPlaybackSource resolve(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        if (credentialStore == null) throw new CredentialRejectedException(0);
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        HighflyPremiumPreferences.Region region = context == null
                ? HighflyPremiumPreferences.Region.MAIN
                : HighflyPremiumPreferences.region(context);
        boolean temporaryEvent = isTemporaryEvent(channel);
        String streamId;
        String sourceIdentity;
        if (isPremiumStableChannel(channel)) {
            // Lista 3 already contains the complete stable membership and the
            // slug. Do not query the protected catalogue just to rediscover
            // metadata; use the credential only for this playback request.
            String stableSlug = stableSlug(channel);
            streamId = "leaf:" + stableSlug;
            sourceIdentity = stableSlug;
        } else if (temporaryEvent) {
            // Lista 4 is app-owned and its event identity must be checked
            // against the current protected catalogue before playback.
            HighflyPremiumCatalog catalog = queryCatalog(region, true, false);
            HighflyPremiumCatalog.Entry entry = findEntry(catalog, channel);
            if (entry == null || entry.getType() == HighflyPremiumCatalog.EntryType.UNSUPPORTED) {
                throw new IOException("Highfly Premium no encontró la señal solicitada.");
            }
            streamId = entry.getId();
            sourceIdentity = entry.getIdentity();
        } else {
            throw new IOException("Highfly Premium no encontró la señal solicitada.");
        }

        String token = credentialStore.readTokenForRequest();
        if (token == null || token.isBlank()) throw new CredentialRejectedException(0);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.SOURCE_REQUEST,
                "GET Premium · solicitando fuente actual"
        ));
        String streamJson = getJson(
                endpoint(region, token, "stream/sport/" + streamId + ".json"),
                MAX_JSON_BYTES
        );
        List<HighflyPremiumPayloadParser.StreamCandidate> candidates =
                new ArrayList<>(HighflyPremiumPayloadParser.parseStreams(streamJson));
        HighflyPremiumPreferences.StreamSort streamSort = context == null
                ? HighflyPremiumPreferences.StreamSort.HIGHEST_FIRST
                : HighflyPremiumPreferences.streamSort(context);
        sortCandidates(candidates, streamSort);
        if (candidates.isEmpty()) throw new IOException("Highfly Premium no publicó fuentes HLS.");

        List<URI> uris = new ArrayList<>();
        for (HighflyPremiumPayloadParser.StreamCandidate candidate : candidates) {
            uris.add(candidate.getUri());
        }
        ResolutionProgressListener safeProgress = progressWithoutUrls(progress);
        HlsCandidateRace.Result race = HlsCandidateRace.firstValid(
                uris,
                Math.min(6, uris.size()),
                2,
                new ResolutionDeadline(18_000L),
                0,
                Math.min(6, uris.size()),
                safeProgress,
                candidate -> {
                    validator.validateForPlayback(
                            candidate,
                            Collections.emptyMap(),
                            safeProgress
                    );
                    return candidate;
                }
        );
        URI accepted = race.getSource();
        if (accepted == null) throw new IOException("Fuente HLS Premium no reproducible.");
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.SOURCE_FOUND,
                "Fuente Premium validada · Media3 continúa con la reproducción"
        ));
        return ResolvedPlaybackSource.dynamic(
                "highfly",
                sourceIdentity,
                accepted,
                Collections.emptyMap(),
                TokenHttpClient.BROWSER_USER_AGENT,
                0L
        );
    }

    public HighflyPremiumCatalog.Entry findEntry(
            HighflyPremiumCatalog catalog,
            Channel channel
    ) {
        if (catalog == null || channel == null) return null;
        String premiumId = attribute(channel, "x-highfly-premium-id");
        if (!premiumId.isBlank()) {
            for (HighflyPremiumCatalog.Entry entry : catalog.getEntries()) {
                if (premiumId.equals(entry.getId())) return entry;
            }
        }

        String resolverId = attribute(channel, "x-resolver-id");
        if (!resolverId.isBlank()) {
            for (HighflyPremiumCatalog.Entry entry : catalog.getEntries()) {
                if (resolverId.equals(entry.getSlug())
                        || resolverId.equals(entry.getId())) return entry;
            }
        }

        String tvgId = channel.getTvgId();
        for (HighflyPremiumCatalog.Entry entry : catalog.getEntries()) {
            if (entry.getSlug().equals(tvgId) || entry.getId().equals(tvgId)) return entry;
        }
        return null;
    }

    public boolean isPremiumStableChannel(Channel channel) {
        return "true".equalsIgnoreCase(attribute(channel, "x-highfly-premium-stable"))
                && "estable".equalsIgnoreCase(attribute(channel, "x-highfly-premium-kind"))
                && "3".equals(attribute(channel, "x-highfly-premium-list"))
                && !attribute(channel, "x-highfly-premium-id").isBlank();
    }

    private static String stableSlug(Channel channel) throws IOException {
        String stableId = attribute(channel, "x-highfly-premium-id");
        String slug = stableId.regionMatches(true, 0, "leaf:", 0, 5)
                ? stableId.substring(5)
                : attribute(channel, "x-resolver-id");
        slug = slug.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_STABLE_SLUG.matcher(slug).matches()) {
            throw new IOException("Highfly Premium no publicó un slug estable válido.");
        }
        return slug;
    }

    /** Only Lista 4 event placeholders are virtual playback channels. */
    public boolean isVirtualChannel(Channel channel) {
        return "true".equalsIgnoreCase(attribute(channel, "x-highfly-premium"))
                && "true".equalsIgnoreCase(attribute(channel, "x-highfly-premium-virtual"))
                && "4".equals(attribute(channel, "x-highfly-premium-list"))
                && !attribute(channel, "x-highfly-premium-id").isBlank();
    }

    public boolean isTemporaryEvent(Channel channel) {
        return isVirtualChannel(channel)
                && "evento".equalsIgnoreCase(attribute(channel, "x-highfly-premium-kind"));
    }

    /** Drops only the in-memory catalogue; an encrypted credential is retained by policy. */
    public void clearSession() {
        synchronized (sessionLock) {
            session = null;
        }
    }

    private String getJson(String endpoint, int maximumBytes) throws IOException {
        try {
            TokenHttpClient.Response response = httpClient.getPublicOnHosts(
                    endpoint,
                    requestHeaders(),
                    maximumBytes,
                    null,
                    PREMIUM_HOSTS
            );
            return new String(response.getBody(), StandardCharsets.UTF_8);
        } catch (TokenHttpClient.HttpStatusException error) {
            int status = error.getStatusCode();
            if (status == 401 || status == 403) {
                throw new CredentialRejectedException(status);
            }
            throw new IOException("Highfly Premium no respondió a la solicitud.");
        } catch (IOException error) {
            // Do not preserve an exception whose message could contain the
            // token-bearing request URI. The UI only needs a safe diagnosis.
            throw new IOException("No se pudo consultar Highfly Premium.");
        }
    }

    private static Map<String, String> requestHeaders() {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", TokenHttpClient.BROWSER_USER_AGENT);
        return Collections.unmodifiableMap(headers);
    }

    private static String endpoint(
            HighflyPremiumPreferences.Region region,
            String token,
            String path
    ) throws IOException {
        String safeToken = HighflyPremiumTokenRules.normalize(token);
        HighflyPremiumPreferences.Region safeRegion = safeRegion(region);
        String safePath = path == null ? "" : path.trim();
        if (safePath.isBlank() || safePath.startsWith("/") || safePath.contains("..")
                || safePath.contains("?") || safePath.contains("#")) {
            throw new IOException("Ruta Premium no válida.");
        }
        try {
            URI uri = URI.create(safeRegion.getBaseUrl() + "/" + safeToken + "/" + safePath);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!PREMIUM_HOSTS.contains(host) || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Host Premium no permitido.");
            }
            return uri.toString();
        } catch (IllegalArgumentException error) {
            throw new IOException("Ruta Premium no válida.");
        }
    }

    private static AccountInfo parseAccount(String json) throws IOException {
        if (json == null || json.isBlank()) throw new IOException("Respuesta Premium vacía.");
        try {
            JSONObject object = new JSONObject(json);
            boolean active = object.optBoolean("active", false);
            long expiresAtSeconds = object.optLong("expires_at", 0L);
            long expiresAtMillis = expiresAtSeconds > 0L
                    && expiresAtSeconds <= Long.MAX_VALUE / 1000L
                    ? expiresAtSeconds * 1000L
                    : 0L;
            int plan = object.optInt("plan", 0);
            String planName = switch (plan) {
                case 1 -> "Premium";
                case 2 -> "Standard";
                case 3 -> "Pro";
                default -> "Premium";
            };
            return new AccountInfo(active, expiresAtMillis, planName);
        } catch (Exception error) {
            throw new IOException("Respuesta de credencial Premium inválida.");
        }
    }

    private static List<String> selectCatalogIds(
            HighflyPremiumPayloadParser.ManifestInfo manifest,
            boolean includeEvents
    ) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        List<String> available = manifest.getCatalogIds();
        List<String> preferredCatalogs = new ArrayList<>();
        preferredCatalogs.add("sports_live");
        if (includeEvents) preferredCatalogs.add("sports_today");
        for (String preferred : preferredCatalogs) {
            if (available.contains(preferred)) selected.add(preferred);
        }
        if (selected.isEmpty() && !available.isEmpty()) selected.add(available.get(0));
        if (includeEvents && selected.size() < MAX_CATALOG_REQUESTS) {
            for (String id : available) {
                if (selected.size() >= MAX_CATALOG_REQUESTS) break;
                if (!id.toLowerCase(Locale.ROOT).contains("recap")) selected.add(id);
            }
        }
        return new ArrayList<>(selected);
    }

    private static void sortCandidates(
            List<HighflyPremiumPayloadParser.StreamCandidate> candidates,
        HighflyPremiumPreferences.StreamSort sort
    ) {
        if (sort == null || sort == HighflyPremiumPreferences.StreamSort.DEFAULT) return;
        final boolean highestFirst = sort == HighflyPremiumPreferences.StreamSort.HIGHEST_FIRST;
        Collections.sort(candidates, new Comparator<HighflyPremiumPayloadParser.StreamCandidate>() {
            @Override
            public int compare(
                    HighflyPremiumPayloadParser.StreamCandidate left,
                    HighflyPremiumPayloadParser.StreamCandidate right
            ) {
                int leftScore = left.getQualityScore();
                int rightScore = right.getQualityScore();
                if (leftScore == rightScore) return 0;
                boolean leftBeforeRight = leftScore < rightScore;
                if (highestFirst) leftBeforeRight = !leftBeforeRight;
                return leftBeforeRight ? -1 : 1;
            }
        });
    }

    private static ResolutionProgressListener progressWithoutUrls(
            ResolutionProgressListener listener
    ) {
        return progress -> {
            if (progress == null) return;
            String detail;
            switch (progress.getStage()) {
                case SOURCE_CANDIDATE -> detail = "Probando fuente Premium";
                case HLS_PLAYLIST -> detail = "Validando playlist Premium";
                case HLS_VARIANT -> detail = "Validando variante Premium";
                case HLS_SEGMENT -> detail = "Validando segmento Premium";
                default -> detail = "Validando fuente Premium";
            }
            if (progress.getCurrent() > 0 && progress.getTotal() > 0) {
                listener.onProgress(ResolutionProgress.counted(
                        progress.getStage(),
                        progress.getCurrent(),
                        progress.getTotal(),
                        detail
                ));
            } else {
                listener.onProgress(ResolutionProgress.of(progress.getStage(), detail));
            }
        };
    }

    private static String attribute(Channel channel, String key) {
        if (channel == null || channel.getAttributes() == null) return "";
        String value = channel.getAttributes().get(key);
        return value == null ? "" : value.trim();
    }

    private static HighflyPremiumPreferences.Region safeRegion(
            HighflyPremiumPreferences.Region region
    ) {
        return region == null ? HighflyPremiumPreferences.Region.MAIN : region;
    }

    private static Set<String> setOf(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            Collections.addAll(result, values);
        }
        return Collections.unmodifiableSet(result);
    }

    public static final class PremiumPlaylists {
        private final HighflyPremiumCatalog catalog;
        private final Playlist stablePlaylist;
        private final Playlist eventPlaylist;

        PremiumPlaylists(
                HighflyPremiumCatalog catalog,
                Playlist stablePlaylist,
                Playlist eventPlaylist
        ) {
            this.catalog = catalog;
            this.stablePlaylist = stablePlaylist;
            this.eventPlaylist = eventPlaylist;
        }

        public HighflyPremiumCatalog getCatalog() {
            return catalog;
        }

        public Playlist getStablePlaylist() {
            return stablePlaylist;
        }

        public Playlist getEventPlaylist() {
            return eventPlaylist;
        }
    }

    public static final class AccountInfo {
        private final boolean active;
        private final long expiresAtMillis;
        private final String planName;

        AccountInfo(boolean active, long expiresAtMillis, String planName) {
            this.active = active;
            this.expiresAtMillis = Math.max(0L, expiresAtMillis);
            this.planName = planName == null || planName.isBlank() ? "Premium" : planName;
        }

        public boolean isActive() {
            return active;
        }

        public long getExpiresAtMillis() {
            return expiresAtMillis;
        }

        public String getPlanName() {
            return planName;
        }

        public boolean isExpired() {
            return expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis;
        }

        public boolean isUsable() {
            return active && !isExpired();
        }
    }

    public static final class CredentialRejectedException extends IOException {
        private final int statusCode;

        CredentialRejectedException(int statusCode) {
            super("Credencial Premium inválida o expirada.");
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    private static final class Session {
        final HighflyPremiumCatalog catalog;
        final long credentialGeneration;
        final HighflyPremiumPreferences.Region region;
        final boolean includeEvents;
        final long loadedAtMillis;

        Session(
                HighflyPremiumCatalog catalog,
                long credentialGeneration,
                HighflyPremiumPreferences.Region region,
                boolean includeEvents
        ) {
            this.catalog = catalog;
            this.credentialGeneration = credentialGeneration;
            this.region = region;
            this.includeEvents = includeEvents;
            this.loadedAtMillis = System.currentTimeMillis();
        }
    }
}

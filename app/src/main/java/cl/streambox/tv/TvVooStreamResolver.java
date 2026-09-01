package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Resolves short-lived TvVoo candidates from stable catalogue aliases. */
public final class TvVooStreamResolver implements StreamResolver {
    private static final String DEFAULT_ENDPOINT = "https://tvvoo.hayd.uk/stream/tv";
    static final String PLAYBACK_USER_AGENT = "VAVOO/2.6";
    private static final int DEFAULT_PARALLEL_ALIASES = 2;
    private static final int DEFAULT_PARALLEL_CANDIDATES = 3;
    private static final int DEFAULT_RESOLUTION_BUDGET_MS = 8_000;
    private static final int DEFAULT_MAX_ALIASES = 6;
    private static final int DEFAULT_MAX_CANDIDATES = 8;
    static final String BOUNDED_PAYLOAD_RECIPE = "bounded-payload-v1";
    static final String MEDIA_SIGNATURE_VALIDATION = "media-signature-v1";

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;
    private final StreamResolver directFallback;
    private final TvVooResolutionMode resolutionMode;

    public TvVooStreamResolver(ResolverDefinition definition) {
        this(definition, TvVooResolutionMode.BOTH);
    }

    public TvVooStreamResolver(
            ResolverDefinition definition,
            TvVooResolutionMode resolutionMode
    ) {
        TokenHttpClient fastClient = new TokenHttpClient(4_000, 6_000);
        this.definition = definition;
        this.httpClient = fastClient;
        this.validator = new HlsStreamValidator(fastClient);
        this.directFallback = new VavooStreamResolver(definition);
        this.resolutionMode = resolutionMode == null
                ? TvVooResolutionMode.BOTH
                : resolutionMode;
    }

    TvVooStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this(
                definition,
                httpClient,
                validator,
                new VavooStreamResolver(definition),
                TvVooResolutionMode.BOTH
        );
    }

    TvVooStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator,
            StreamResolver directFallback,
            TvVooResolutionMode resolutionMode
    ) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.validator = validator;
        this.directFallback = directFallback;
        this.resolutionMode = resolutionMode == null
                ? TvVooResolutionMode.BOTH
                : resolutionMode;
    }

    @Override public String getId() { return definition.getId(); }

    @Override
    public boolean supports(Channel channel) {
        return definition.matchesExplicit(channel)
                || definition.matchesTvgId(channel)
                || definition.matchesHost(channel);
    }

    @Override public String stableSourceId(Channel channel) {
        return definition.stableSourceId(channel);
    }

    @Override public long cacheTtlMillis() { return definition.getCacheTtlMillis(); }

    @Override public boolean cacheResolvedSource() { return false; }

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        return resolve(channel, ResolutionProgressListener.NONE);
    }

    @Override
    public ResolvedPlaybackSource resolve(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        ResolutionDeadline deadline = newResolutionDeadline();
        if (!resolutionMode.usesExternalResolver()) {
            return resolveDirect(channel, null, progress, deadline);
        }

        String endpointBase = channel.getAttributes().get("x-resolver-endpoint");
        if (endpointBase == null || endpointBase.isBlank()) {
            endpointBase = definition.getConfig("endpointBase", DEFAULT_ENDPOINT);
        }
        endpointBase = validEndpoint(endpointBase);
        String requestedRecipe = definition.requestedRecipe(channel);
        boolean boundedPayloadRecipe = definition.usesRecipe(
                channel,
                BOUNDED_PAYLOAD_RECIPE
        ) && MEDIA_SIGNATURE_VALIDATION.equals(
                definition.getConfig("validationMode", "")
        );
        if (!requestedRecipe.isBlank() && !boundedPayloadRecipe) {
            throw new IOException("La receta declarativa del canal no está autorizada.");
        }

        LinkedHashSet<String> aliases = new LinkedHashSet<>(definition.resolverAliases(channel));
        // Explicit M3U aliases are authoritative. Generated aliases are only
        // compatibility fallbacks; querying both delays the direct engine
        // behind unrelated dead candidates.
        if (aliases.isEmpty()) aliases.addAll(generatedAliases(channel));
        int maxAliases = definition.getIntConfig(
                "maxAliases",
                DEFAULT_MAX_ALIASES,
                1,
                12
        );
        int maxCandidates = definition.getIntConfig(
                "maxCandidates",
                DEFAULT_MAX_CANDIDATES,
                1,
                32
        );
        boolean allowHttpFallback = definition.getBooleanConfig("allowHttpFallback", true);
        Map<String, String> jsonHeaders = Collections.singletonMap("Accept", "application/json");
        Map<String, String> playbackHeaders = playbackHeaders();

        int parallelAliases = definition.getIntConfig(
                "parallelAliases",
                DEFAULT_PARALLEL_ALIASES,
                1,
                3
        );
        int parallelCandidates = definition.getIntConfig(
                "parallelCandidates",
                DEFAULT_PARALLEL_CANDIDATES,
                1,
                4
        );
        int candidateCount = 0;
        int aliasTotal = Math.min(aliases.size(), maxAliases);
        IOException lastError = null;
        List<String> limitedAliases = new ArrayList<>();
        int aliasIndex = 0;
        for (String alias : aliases) {
            if (aliasIndex++ >= maxAliases) break;
            limitedAliases.add(alias);
        }
        ExecutorService aliasExecutor = Executors.newFixedThreadPool(
                parallelAliases,
                new NamedDaemonThreadFactory("vibem3u-tvvoo-alias")
        );
        try {
            for (int offset = 0;
                 offset < limitedAliases.size() && candidateCount < maxCandidates;
                 offset += parallelAliases) {
                deadline.check();
                List<AliasResult> batch = fetchAliasBatch(
                        limitedAliases,
                        offset,
                        parallelAliases,
                        aliasTotal,
                        endpointBase,
                        jsonHeaders,
                        definition,
                        boundedPayloadRecipe,
                        httpClient,
                        progress,
                        deadline,
                        aliasExecutor
                );
                LinkedHashSet<URI> candidates = new LinkedHashSet<>();
                for (AliasResult result : batch) {
                    if (result.error != null) lastError = result.error;
                    candidates.addAll(result.candidates);
                }
                if (candidates.isEmpty()) continue;

                HlsCandidateRace.Result race = HlsCandidateRace.firstValid(
                        new ArrayList<>(candidates),
                        maxCandidates - candidateCount,
                        parallelCandidates,
                        deadline,
                        candidateCount,
                        maxCandidates,
                        progress,
                        candidate -> validateCandidate(
                                validator,
                                candidate,
                                allowHttpFallback,
                                playbackHeaders,
                                true,
                                progress
                        )
                );
                candidateCount += race.getAttempted();
                if (race.getLastError() != null) lastError = race.getLastError();
                if (race.getSource() != null) {
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.SOURCE_FOUND,
                            "HLS válido · GET " + SafePlaybackText.url(race.getSource())
                                    + " · candidato aceptado"
                    ));
                    return ResolvedPlaybackSource.dynamic(
                            getId(),
                            stableSourceId(channel),
                            race.getSource(),
                            playbackHeaders,
                            PLAYBACK_USER_AGENT,
                            expiresAt()
                    );
                }
            }
        } catch (IOException error) {
            lastError = error;
        } finally {
            aliasExecutor.shutdownNow();
        }
        if (resolutionMode.usesDirectResolver()) {
            // The direct engine is an independent recovery path. Give it a
            // fresh budget instead of inheriting an exhausted external
            // catalogue budget; otherwise a slow/empty TvVoo endpoint would
            // prevent the fallback from ever being attempted.
            return resolveDirect(channel, lastError, progress, newResolutionDeadline());
        }
        throw new IOException("TvVoo no entregó una fuente reproducible.", lastError);
    }

    private ResolvedPlaybackSource resolveDirect(
            Channel channel,
            IOException externalError
    ) throws IOException {
        return resolveDirect(
                channel,
                externalError,
                ResolutionProgressListener.NONE,
                newResolutionDeadline()
        );
    }

    private ResolvedPlaybackSource resolveDirect(
            Channel channel,
            IOException externalError,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        try {
            if (directFallback instanceof VavooStreamResolver) {
                return ((VavooStreamResolver) directFallback).resolve(
                        channel,
                        listener,
                        deadline
                );
            }
            deadline.check();
            return directFallback.resolve(channel, listener);
        } catch (IOException directError) {
            if (externalError != null) directError.addSuppressed(externalError);
            String message = resolutionMode == TvVooResolutionMode.DIRECT_ONLY
                    ? "Vavoo directo no entregó una fuente reproducible."
                    : "TvVoo y Vavoo directo no entregaron una fuente reproducible.";
            throw new IOException(message, directError);
        }
    }

    @Override
    public void clearSensitiveState() {
        directFallback.clearSensitiveState();
    }

    static URI validateCandidate(
            HlsStreamValidator validator,
            URI published,
            boolean allowHttpFallback,
            Map<String, String> playbackHeaders
    ) throws IOException {
        return validateCandidate(
                validator,
                published,
                allowHttpFallback,
                playbackHeaders,
                true,
                ResolutionProgressListener.NONE
        );
    }

    static URI validateCandidate(
            HlsStreamValidator validator,
            URI published,
            boolean allowHttpFallback,
            Map<String, String> playbackHeaders,
            ResolutionProgressListener listener
    ) throws IOException {
        return validateCandidate(
                validator,
                published,
                allowHttpFallback,
                playbackHeaders,
                true,
                listener
        );
    }

    static URI validateCandidate(
            HlsStreamValidator validator,
            URI published,
            boolean allowHttpFallback,
            Map<String, String> playbackHeaders,
            boolean strictValidation,
            ResolutionProgressListener listener
    ) throws IOException {
        String scheme = published.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            try {
                validateHls(
                        validator,
                        published,
                        playbackHeaders,
                        strictValidation,
                        listener
                );
                return published;
            } catch (IOException error) {
                if (!allowHttpFallback || !isExpiredCertificateFailure(error)) throw error;
                URI fallback = withScheme(published, "http");
                validateHls(
                        validator,
                        fallback,
                        playbackHeaders,
                        strictValidation,
                        listener
                );
                return fallback;
            }
        }
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IOException("TvVoo publicó una URL inválida.");
        }
        URI upgraded = withScheme(published, "https");
        try {
            validateHls(
                    validator,
                    upgraded,
                    playbackHeaders,
                    strictValidation,
                    listener
            );
            return upgraded;
        } catch (IOException error) {
            if (!allowHttpFallback || !isExpiredCertificateFailure(error)) throw error;
            validateHls(
                    validator,
                    published,
                    playbackHeaders,
                    strictValidation,
                    listener
            );
            return published;
        }
    }

    private static void validateHls(
            HlsStreamValidator validator,
            URI source,
            Map<String, String> playbackHeaders,
            boolean strictValidation,
            ResolutionProgressListener listener
    ) throws IOException {
        if (strictValidation) {
            validator.validate(source, playbackHeaders, listener);
        } else {
            validator.validateForPlayback(source, playbackHeaders, listener);
        }
    }

    private static List<AliasResult> fetchAliasBatch(
            List<String> aliases,
            int offset,
            int parallelAliases,
            int aliasTotal,
            String endpointBase,
            Map<String, String> jsonHeaders,
            ResolverDefinition definition,
            boolean boundedPayloadRecipe,
            TokenHttpClient httpClient,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline,
            ExecutorService executor
    ) throws IOException {
        CompletionService<AliasResult> completion = new ExecutorCompletionService<>(executor);
        List<Future<AliasResult>> submitted = new ArrayList<>();
        int batchSize = Math.min(parallelAliases, aliases.size() - offset);
        for (int index = 0; index < batchSize; index++) {
            int absoluteIndex = offset + index;
            String alias = aliases.get(absoluteIndex);
            progress.onProgress(ResolutionProgress.counted(
                    ResolutionStage.ALIAS_ATTEMPT,
                    absoluteIndex + 1,
                    aliasTotal,
                    "alias=" + alias + " · preparando consulta JSON"
            ));
            submitted.add(completion.submit(() -> {
                try {
                    deadline.check();
                    String endpoint = endpointBase + "/" + encodedAlias(alias) + ".json";
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.CATALOG_REQUEST,
                            "GET " + SafePlaybackText.url(endpoint)
                                    + " · JSON · streams[].url"
                    ));
                    String response = httpClient.getText(endpoint, jsonHeaders);
                    progress.onProgress(ResolutionProgress.of(
                            ResolutionStage.CATALOG_PARSED,
                            "JSON válido · extrayendo streams[].url · alias=" + alias
                    ));
                    LinkedHashSet<URI> candidates = new LinkedHashSet<>(
                            ResolverPayloadParsers.parseTvVooCandidates(
                                    response,
                                    definition.getConfig("streamsPath", "streams"),
                                    definition.getConfig("urlField", "url")
                            )
                    );
                    if (boundedPayloadRecipe) {
                        candidates.addAll(ResolverPayloadParsers.parseBoundedHlsCandidates(
                                response,
                                URI.create(endpoint),
                                definition.getIntConfig("maxPayloadDepth", 6, 1, 8),
                                definition.getIntConfig("maxExtractedStrings", 256, 8, 512),
                                definition.getIntConfig("maxCandidates", 8, 1, 32)
                        ));
                    }
                    return new AliasResult(
                            absoluteIndex,
                            Collections.unmodifiableList(new ArrayList<>(candidates)),
                            null
                    );
                } catch (IOException error) {
                    return new AliasResult(absoluteIndex, Collections.emptyList(), error);
                }
            }));
        }

        List<AliasResult> results = new ArrayList<>();
        try {
            while (results.size() < batchSize) {
                deadline.check();
                Future<AliasResult> future;
                try {
                    future = completion.poll(
                            deadline.remainingMillis(),
                            TimeUnit.MILLISECONDS
                    );
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", error);
                }
                if (future == null) throw new IOException("Tiempo de resolución agotado.");
                try {
                    results.add(future.get());
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    results.add(new AliasResult(
                            offset + results.size(),
                            Collections.emptyList(),
                            cause instanceof IOException
                                    ? (IOException) cause
                                    : new IOException("No se pudo consultar el alias.", cause)
                    ));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", error);
                }
            }
        } finally {
            for (Future<AliasResult> future : submitted) {
                if (!future.isDone()) future.cancel(true);
            }
        }
        Collections.sort(results, (left, right) ->
                Integer.compare(left.index, right.index)
        );
        return results;
    }

    private ResolutionDeadline newResolutionDeadline() {
        return new ResolutionDeadline(
                definition.getIntConfig(
                        "resolutionBudgetMs",
                        DEFAULT_RESOLUTION_BUDGET_MS,
                        2_000,
                        20_000
                )
        );
    }

    private static final class AliasResult {
        private final int index;
        private final List<URI> candidates;
        private final IOException error;

        AliasResult(int index, List<URI> candidates, IOException error) {
            this.index = index;
            this.candidates = candidates == null
                    ? Collections.emptyList()
                    : candidates;
            this.error = error;
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String name;

        NamedDaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }

    static Map<String, String> playbackHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", PLAYBACK_USER_AGENT);
        headers.put("Referer", "https://vavoo.to/");
        headers.put("Origin", "https://vavoo.to");
        return Collections.unmodifiableMap(headers);
    }

    private static List<String> generatedAliases(Channel channel) {
        if (channel == null) return Collections.emptyList();
        String name = channel.getAttributes().get("tvg-name");
        if (name == null || name.isBlank()) name = channel.getName();
        String country = channel.getAttributes().get("tvg-country");
        if (name == null || name.isBlank() || country == null || country.isBlank()) {
            return Collections.emptyList();
        }
        String normalizedName = name.trim().toUpperCase(Locale.ROOT);
        String group = country.trim().toLowerCase(Locale.ROOT);
        List<String> aliases = new ArrayList<>();
        aliases.add("vavoo_" + encodePart(normalizedName + "|group:" + group));
        aliases.add("vavoo_" + encodePart(normalizedName + " HD|group:" + group));
        aliases.add("vavoo_" + encodePart(normalizedName + " FHD|group:" + group));
        return aliases;
    }

    private static String validEndpoint(String value) throws IOException {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"tvvoo.hayd.uk".equalsIgnoreCase(uri.getHost())
                    || !uri.getPath().startsWith("/stream/tv")) {
                throw new IOException("Endpoint TvVoo no permitido.");
            }
            String result = uri.toString();
            return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
        } catch (IllegalArgumentException error) {
            throw new IOException("Endpoint TvVoo inválido.", error);
        }
    }

    private static String encodedAlias(String alias) throws IOException {
        String value = alias == null ? "" : alias.trim();
        if (value.matches("vavoo_[A-Za-z0-9%._~+\\-]+")) return value;
        if (value.startsWith("vavoo_")) {
            return "vavoo_" + encodePart(value.substring("vavoo_".length()));
        }
        throw new IOException("Alias TvVoo inválido.");
    }

    private static String encodePart(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static URI withScheme(URI uri, String scheme) throws IOException {
        try {
            return new URI(
                    scheme,
                    uri.getUserInfo(),
                    uri.getHost(),
                    -1,
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
        } catch (Exception error) {
            throw new IOException("TvVoo publicó una URL inválida.", error);
        }
    }

    static boolean isExpiredCertificateFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof CertificateExpiredException) return true;
            if (current instanceof SSLHandshakeException
                    || current instanceof SSLPeerUnverifiedException
                    || current instanceof CertificateException) {
                String message = current.getMessage();
                String normalized = message == null
                        ? ""
                        : message.toLowerCase(Locale.ROOT);
                if (normalized.contains("expired") || normalized.contains("notafter")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
    }
}

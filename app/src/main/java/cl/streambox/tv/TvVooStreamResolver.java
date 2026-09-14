package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
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
import java.util.concurrent.Callable;
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
    private static final String TVVOO_CDN_HOST_SUFFIX =
            ".ngolpdkyoctjcddxshli469r.org";
    private static final String TVVOO_CDN_PATH_PREFIX = "/sunshine/";

    private final ResolverDefinition definition;
    private final TokenHttpClient httpClient;
    private final HlsStreamValidator validator;
    public TvVooStreamResolver(ResolverDefinition definition) {
        TokenHttpClient fastClient = new TokenHttpClient(4_000, 6_000);
        this.definition = definition;
        this.httpClient = fastClient;
        this.validator = new HlsStreamValidator(fastClient);
    }

    TvVooStreamResolver(
            ResolverDefinition definition,
            TokenHttpClient httpClient,
            HlsStreamValidator validator
    ) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.validator = validator;
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
        ResolutionContext ambient = ResolutionContext.current();
        if (ambient == null) {
            ResolutionContext root = new ResolutionContext(deadline.remainingMillis());
            try (ResolutionContext.Scope ignored = root.activate()) {
                return resolveWithDeadline(channel, progress, deadline);
            }
        }
        return resolveWithDeadline(channel, progress, deadline);
    }

    private ResolvedPlaybackSource resolveWithDeadline(
            Channel channel,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline
    ) throws IOException {
        return resolveExternal(channel, progress, deadline);
    }

    @Override
    public List<ResolvedPlaybackCandidate> resolvePlaybackCandidates(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        ResolutionDeadline deadline = newResolutionDeadline();
        ResolutionContext ambient = ResolutionContext.current();
        if (ambient == null) {
            ResolutionContext root = new ResolutionContext(deadline.remainingMillis());
            try (ResolutionContext.Scope ignored = root.activate()) {
                return resolvePlaybackCandidatesWithDeadline(channel, progress, deadline);
            }
        }
        return resolvePlaybackCandidatesWithDeadline(channel, progress, deadline);
    }

    /**
     * Resolves alternatives only when the user explicitly opens the source
     * selector. The normal channel-open path keeps using the first-valid race
     * above so it does not wait for every candidate.
     */
    private List<ResolvedPlaybackCandidate> resolvePlaybackCandidatesWithDeadline(
            Channel channel,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline
    ) throws IOException {
        List<ResolvedPlaybackCandidate> result = resolveExternalCandidates(
                channel,
                progress,
                deadline
        );
        return result == null ? Collections.emptyList() : result;
    }

    private List<ResolvedPlaybackCandidate> resolveExternalCandidates(
            Channel channel,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline
    ) throws IOException {
        String endpointBase = channel.getAttributes().get("x-resolver-endpoint");
        if (endpointBase == null || AppStrings.isBlank(endpointBase)) {
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
        if (!AppStrings.isBlank(requestedRecipe) && !boundedPayloadRecipe) {
            throw new IOException("La receta declarativa del canal no está autorizada.");
        }

        LinkedHashSet<String> aliases = new LinkedHashSet<>(definition.resolverAliases(channel));
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
        List<String> limitedAliases = new ArrayList<>();
        int aliasIndex = 0;
        for (String alias : aliases) {
            if (aliasIndex++ >= maxAliases) break;
            limitedAliases.add(alias);
        }
        if (limitedAliases.isEmpty()) {
            throw new IOException("El canal no tiene aliases TvVoo.");
        }

        List<AliasResult> aliasResults = queryAliasResults(
                limitedAliases,
                endpointBase,
                Collections.singletonMap("Accept", "application/json"),
                definition,
                boundedPayloadRecipe,
                parallelAliases,
                progress,
                deadline
        );
        LinkedHashMap<URI, String> candidatesByAlias = new LinkedHashMap<>();
        IOException lastError = null;
        for (AliasResult result : aliasResults) {
            if (result.error != null) lastError = result.error;
            String alias = result.index >= 0 && result.index < limitedAliases.size()
                    ? limitedAliases.get(result.index)
                    : "";
            for (URI candidate : result.candidates) {
                if (candidate != null && candidatesByAlias.size() < maxCandidates) {
                    candidatesByAlias.putIfAbsent(candidate, alias);
                }
            }
        }
        if (candidatesByAlias.isEmpty()) {
            throw new IOException("TvVoo no publicó URLs alternativas.", lastError);
        }

        Map<String, String> playbackHeaders = playbackHeaders();
        List<ResolvedPlaybackCandidate> result = new ArrayList<>();
        HlsCandidateRace.Streaming candidateRace = new HlsCandidateRace.Streaming(
                candidatesByAlias.size(),
                parallelCandidates,
                deadline,
                0,
                candidatesByAlias.size(),
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
        try {
            for (URI candidate : candidatesByAlias.keySet()) {
                candidateRace.submit(candidate);
            }
            while (candidateRace.hasInFlight()) {
                try {
                    deadline.check();
                } catch (IOException deadlineError) {
                    if (result.isEmpty()) throw deadlineError;
                    break;
                }
                HlsCandidateRace.Attempt attempt = candidateRace.poll(
                        Math.min(250L, Math.max(1L, deadline.remainingMillis()))
                );
                if (attempt == null) continue;
                if (attempt.getAccepted() != null) {
                    String alias = candidatesByAlias.get(attempt.getCandidate());
                    int ordinal = result.size() + 1;
                    String qualityHint = aliasQualityHint(alias);
                    String label = "Fuente " + ordinal + " · " + qualityHint;
                    String detail = "TvVoo · HLS validada";
                    result.add(new ResolvedPlaybackCandidate(
                            label,
                            detail,
                            ResolvedPlaybackSource.dynamic(
                                    getId(),
                                    stableSourceId(channel),
                                    playbackOptionId(alias),
                                    attempt.getAccepted(),
                                    playbackHeaders,
                                    PLAYBACK_USER_AGENT,
                                    expiresAt()
                            )
                    ));
                } else if (attempt.getError() != null) {
                    lastError = attempt.getError();
                }
            }
        } finally {
            candidateRace.close();
        }
        if (result.isEmpty()) {
            throw new IOException(
                    "TvVoo no validó ninguna fuente alternativa.",
                    lastError
            );
        }
        return Collections.unmodifiableList(result);
    }

    private List<AliasResult> queryAliasResults(
            List<String> aliases,
            String endpointBase,
            Map<String, String> jsonHeaders,
            ResolverDefinition definition,
            boolean boundedPayloadRecipe,
            int parallelAliases,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline
    ) throws IOException {
        ExecutorService aliasExecutor = Executors.newFixedThreadPool(
                parallelAliases,
                new NamedDaemonThreadFactory("vibem3u-tvvoo-option-alias")
        );
        CompletionService<AliasResult> completion =
                new ExecutorCompletionService<>(aliasExecutor);
        List<AliasState> states = new ArrayList<>();
        List<AliasResult> results = new ArrayList<>();
        int nextAlias = 0;
        int inFlight = 0;
        try {
            while (nextAlias < aliases.size() && inFlight < parallelAliases) {
                states.add(submitAlias(
                        aliases.get(nextAlias),
                        nextAlias,
                        aliases.size(),
                        endpointBase,
                        jsonHeaders,
                        definition,
                        boundedPayloadRecipe,
                        httpClient,
                        progress,
                        deadline,
                        completion
                ));
                nextAlias++;
                inFlight++;
            }
            while (inFlight > 0) {
                deadline.check();
                Future<AliasResult> finished;
                try {
                    finished = completion.poll(
                            Math.min(250L, Math.max(1L, deadline.remainingMillis())),
                            TimeUnit.MILLISECONDS
                    );
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", error);
                }
                if (finished == null) continue;
                inFlight--;
                try {
                    results.add(finished.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Solicitud cancelada.", error);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    results.add(new AliasResult(
                            -1,
                            Collections.emptyList(),
                            cause instanceof IOException
                                    ? (IOException) cause
                                    : new IOException("No se pudo consultar el alias.", cause)
                    ));
                }
                while (nextAlias < aliases.size() && inFlight < parallelAliases) {
                    states.add(submitAlias(
                            aliases.get(nextAlias),
                            nextAlias,
                            aliases.size(),
                            endpointBase,
                            jsonHeaders,
                            definition,
                            boundedPayloadRecipe,
                            httpClient,
                            progress,
                            deadline,
                            completion
                    ));
                    nextAlias++;
                    inFlight++;
                }
            }
        } finally {
            for (AliasState state : states) state.cancel();
            aliasExecutor.shutdownNow();
        }
        return results;
    }

    private static String aliasQualityHint(String alias) {
        if (alias == null || AppStrings.isBlank(alias)) return "alias";
        String decoded = alias;
        try {
            int prefix = decoded.indexOf('_');
            if (prefix >= 0 && prefix + 1 < decoded.length()) {
                decoded = URLDecoder.decode(
                        decoded.substring(prefix + 1),
                        StandardCharsets.UTF_8.name()
                );
            }
        } catch (Exception ignored) {
            // Keep the generic label when an alias is not URL-decodable.
        }
        String normalized = decoded.toUpperCase(Locale.ROOT);
        if (normalized.contains(" FHD") || normalized.contains(" UHD")) {
            return "alias FHD/UHD";
        }
        if (normalized.contains(" HD")) return "alias HD";
        if (normalized.contains(" SD")) return "alias SD";
        return "alias estándar";
    }

    private ResolvedPlaybackSource resolveExternal(
            Channel channel,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline
    ) throws IOException {

        String endpointBase;
        boolean boundedPayloadRecipe;
        try {
            endpointBase = channel.getAttributes().get("x-resolver-endpoint");
            if (endpointBase == null || AppStrings.isBlank(endpointBase)) {
                endpointBase = definition.getConfig("endpointBase", DEFAULT_ENDPOINT);
            }
            endpointBase = validEndpoint(endpointBase);
            String requestedRecipe = definition.requestedRecipe(channel);
            boundedPayloadRecipe = definition.usesRecipe(
                    channel,
                    BOUNDED_PAYLOAD_RECIPE
            ) && MEDIA_SIGNATURE_VALIDATION.equals(
                    definition.getConfig("validationMode", "")
            );
            if (!AppStrings.isBlank(requestedRecipe) && !boundedPayloadRecipe) {
                throw new IOException("La receta declarativa del canal no está autorizada.");
            }
        } catch (IOException setupError) {
            throw setupError;
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
        CompletionService<AliasResult> aliasCompletion =
                new ExecutorCompletionService<>(aliasExecutor);
        List<AliasState> aliasStates = new ArrayList<>();
        HlsCandidateRace.Streaming candidateRace = new HlsCandidateRace.Streaming(
                maxCandidates,
                parallelCandidates,
                deadline,
                0,
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
        LinkedHashSet<URI> globallySeenCandidates = new LinkedHashSet<>();
        Map<URI, String> aliasByCandidate = new LinkedHashMap<>();
        int nextAlias = 0;
        int inFlightAliases = 0;
        int completedAliases = 0;
        try {
            while (nextAlias < limitedAliases.size()
                    && inFlightAliases < parallelAliases
                    && candidateRace.hasCapacity()) {
                aliasStates.add(submitAlias(
                        limitedAliases.get(nextAlias),
                        nextAlias++,
                        aliasTotal,
                        endpointBase,
                        jsonHeaders,
                        definition,
                        boundedPayloadRecipe,
                        httpClient,
                        progress,
                        deadline,
                        aliasCompletion
                ));
                inFlightAliases++;
            }
            while (inFlightAliases > 0 || candidateRace.hasInFlight()) {
                deadline.check();
                if (!candidateRace.hasCapacity() && !candidateRace.hasInFlight()) {
                    break;
                }
                HlsCandidateRace.Attempt candidateAttempt = candidateRace.poll(1L);
                if (candidateAttempt != null) {
                    if (candidateAttempt.getAccepted() != null) {
                        URI source = candidateAttempt.getAccepted();
                        progress.onProgress(ResolutionProgress.of(
                                ResolutionStage.SOURCE_FOUND,
                                "HLS válido · GET " + SafePlaybackText.url(source)
                                        + " · candidato aceptado"
                        ));
                        return ResolvedPlaybackSource.dynamic(
                                getId(),
                                stableSourceId(channel),
                                playbackOptionId(aliasByCandidate.get(candidateAttempt.getCandidate())),
                                source,
                                playbackHeaders,
                                PLAYBACK_USER_AGENT,
                                expiresAt()
                        );
                    }
                    if (candidateAttempt.getError() != null) {
                        lastError = candidateAttempt.getError();
                    }
                    continue;
                }

                Future<AliasResult> finished = aliasCompletion.poll();
                if (finished != null) {
                    inFlightAliases--;
                    completedAliases++;
                    AliasResult result;
                    try {
                        result = finished.get();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Solicitud cancelada.", error);
                    } catch (ExecutionException error) {
                        Throwable cause = error.getCause();
                        result = new AliasResult(
                                completedAliases - 1,
                                Collections.emptyList(),
                                cause instanceof IOException
                                        ? (IOException) cause
                                        : new IOException("No se pudo consultar el alias.", cause)
                        );
                    }
                    if (result.error != null) lastError = result.error;
                    for (URI candidate : result.candidates) {
                        if (globallySeenCandidates.add(candidate)) {
                            String alias = result.index >= 0 && result.index < limitedAliases.size()
                                    ? limitedAliases.get(result.index)
                                    : "";
                            aliasByCandidate.put(candidate, alias);
                            candidateRace.submit(candidate);
                            if (!candidateRace.hasCapacity()) break;
                        }
                    }
                    while (nextAlias < limitedAliases.size()
                            && inFlightAliases < parallelAliases
                            && candidateRace.hasCapacity()) {
                        aliasStates.add(submitAlias(
                                limitedAliases.get(nextAlias),
                                nextAlias++,
                                aliasTotal,
                                endpointBase,
                                jsonHeaders,
                                definition,
                                boundedPayloadRecipe,
                                httpClient,
                                progress,
                                deadline,
                                aliasCompletion
                        ));
                        inFlightAliases++;
                    }
                    continue;
                }

                // No completed event yet. A short poll lets either the next
                // alias or the next HLS validation report without imposing a
                // batch barrier on the other queue.
                if (candidateRace.hasInFlight()) {
                    HlsCandidateRace.Attempt attempt = candidateRace.poll(10L);
                    if (attempt != null) {
                        if (attempt.getAccepted() != null) {
                            URI source = attempt.getAccepted();
                            progress.onProgress(ResolutionProgress.of(
                                    ResolutionStage.SOURCE_FOUND,
                                    "HLS válido · GET " + SafePlaybackText.url(source)
                                            + " · candidato aceptado"
                            ));
                            return ResolvedPlaybackSource.dynamic(
                                    getId(),
                                    stableSourceId(channel),
                                    playbackOptionId(aliasByCandidate.get(attempt.getCandidate())),
                                    source,
                                    playbackHeaders,
                                    PLAYBACK_USER_AGENT,
                                    expiresAt()
                            );
                        }
                        if (attempt.getError() != null) lastError = attempt.getError();
                    }
                } else {
                    Thread.yield();
                }
            }
        } catch (IOException error) {
            lastError = error;
        } finally {
            candidateRace.close();
            for (AliasState state : aliasStates) state.cancel();
            aliasExecutor.shutdownNow();
        }
        throw new IOException("TvVoo no entregó una fuente reproducible.", lastError);
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
        return validateCandidate(
                (source, headers, strict, progress) -> validateHls(
                        validator,
                        source,
                        headers,
                        strict,
                        progress
                ),
                published,
                allowHttpFallback,
                playbackHeaders,
                strictValidation,
                listener
        );
    }

    static URI validateCandidate(
            HlsCandidateValidator validator,
            URI published,
            boolean allowHttpFallback,
            Map<String, String> playbackHeaders,
            boolean strictValidation,
            ResolutionProgressListener listener
    ) throws IOException {
        if (allowHttpFallback && isScopedTvVooHttpFallback(published)) {
            return validateScopedTvVooCandidate(
                    validator,
                    published,
                    playbackHeaders,
                    strictValidation,
                    listener
            );
        }
        String scheme = published.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            try {
                validator.validate(
                        published,
                        playbackHeaders,
                        strictValidation,
                        listener
                );
                return published;
            } catch (IOException error) {
                if (!allowHttpFallback || !isExpiredCertificateFailure(error)) throw error;
                URI fallback = withScheme(published, "http");
                validator.validate(
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
            validator.validate(
                    upgraded,
                    playbackHeaders,
                    strictValidation,
                    listener
            );
            return upgraded;
        } catch (IOException error) {
            if (!allowHttpFallback || !isExpiredCertificateFailure(error)) throw error;
            validator.validate(
                    published,
                    playbackHeaders,
                    strictValidation,
                    listener
            );
            return published;
        }
    }

    /**
     * TvVoo currently publishes short-lived /sunshine/ candidates on CDN
     * hosts whose HTTPS certificate is expired while the same HLS remains
     * available over HTTP. Keep this compatibility path narrow: it is not a
     * general TLS bypass and still requires full HLS validation.
     */
    private static URI validateScopedTvVooCandidate(
            HlsCandidateValidator validator,
            URI published,
            Map<String, String> playbackHeaders,
            boolean strictValidation,
            ResolutionProgressListener listener
    ) throws IOException {
        URI httpCandidate = "http".equalsIgnoreCase(published.getScheme())
                ? published
                : withScheme(published, "http");
        IOException httpError;
        try {
            validator.validate(
                    httpCandidate,
                    playbackHeaders,
                    strictValidation,
                    listener
            );
            return httpCandidate;
        } catch (IOException error) {
            httpError = error;
        }

        URI httpsCandidate = "https".equalsIgnoreCase(published.getScheme())
                ? published
                : withScheme(published, "https");
        try {
            validator.validate(
                    httpsCandidate,
                    playbackHeaders,
                    strictValidation,
                    listener
            );
            return httpsCandidate;
        } catch (IOException httpsError) {
            httpsError.addSuppressed(httpError);
            throw httpsError;
        }
    }

    static boolean isScopedTvVooHttpFallback(URI candidate) {
        if (candidate == null || candidate.getHost() == null) return false;
        String host = candidate.getHost().toLowerCase(Locale.ROOT);
        String path = candidate.getPath() == null
                ? ""
                : candidate.getPath().toLowerCase(Locale.ROOT);
        return host.length() > TVVOO_CDN_HOST_SUFFIX.length()
                && host.endsWith(TVVOO_CDN_HOST_SUFFIX)
                && path.startsWith(TVVOO_CDN_PATH_PREFIX);
    }

    interface HlsCandidateValidator {
        void validate(
                URI source,
                Map<String, String> headers,
                boolean strictValidation,
                ResolutionProgressListener listener
        ) throws IOException;
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

    private static AliasState submitAlias(
            String alias,
            int absoluteIndex,
            int aliasTotal,
            String endpointBase,
            Map<String, String> jsonHeaders,
            ResolverDefinition definition,
            boolean boundedPayloadRecipe,
            TokenHttpClient httpClient,
            ResolutionProgressListener progress,
            ResolutionDeadline deadline,
            CompletionService<AliasResult> completion
    ) {
        progress.onProgress(ResolutionProgress.counted(
                ResolutionStage.ALIAS_ATTEMPT,
                absoluteIndex + 1,
                aliasTotal,
                "alias=" + alias + " · preparando consulta JSON"
        ));
        ResolutionContext parent = ResolutionContext.current();
        if (parent == null) parent = new ResolutionContext(deadline.remainingMillis());
        ResolutionContext aliasContext = parent.child(deadline.remainingMillis());
        Callable<AliasResult> task = () -> {
            try (ResolutionContext.Scope ignored = aliasContext.activate()) {
                try {
                    deadline.check();
                    ResolutionContext.current().check();
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
            } catch (RuntimeException error) {
                return new AliasResult(
                        absoluteIndex,
                        Collections.emptyList(),
                        new IOException("No se pudo consultar el alias.", error)
                );
            }
        };
        Future<AliasResult> future = completion.submit(ResolutionContext.wrapCurrent(task));
        return new AliasState(aliasContext, future);
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

    private static final class AliasState {
        private final ResolutionContext context;
        private final Future<AliasResult> future;

        AliasState(ResolutionContext context, Future<AliasResult> future) {
            this.context = context;
            this.future = future;
        }

        void cancel() {
            context.cancel();
            if (!future.isDone()) future.cancel(true);
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

    private static String playbackOptionId(String alias) {
        return AppStrings.isBlank(alias) ? "" : "tvvoo:" + alias.trim();
    }

    private static List<String> generatedAliases(Channel channel) {
        if (channel == null) return Collections.emptyList();
        String name = channel.getAttributes().get("tvg-name");
        if (name == null || AppStrings.isBlank(name)) name = channel.getName();
        String country = channel.getAttributes().get("tvg-country");
        if (name == null || AppStrings.isBlank(name) || country == null || AppStrings.isBlank(country)) {
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

    static URI withScheme(URI uri, String scheme) throws IOException {
        if (uri == null || scheme == null
                || !scheme.matches("(?i)https?")) {
            throw new IOException("TvVoo publicó una URL inválida.");
        }
        String raw = uri.toString();
        int separator = raw.indexOf(':');
        if (separator <= 0) throw new IOException("TvVoo publicó una URL inválida.");
        try {
            // Replacing only the scheme preserves every raw byte in the
            // authority, path, query and fragment, including escaped octets.
            // The explicit port policy is to preserve a declared port (even
            // when it is the default for the new scheme).
            return URI.create(scheme + raw.substring(separator));
        } catch (IllegalArgumentException error) {
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

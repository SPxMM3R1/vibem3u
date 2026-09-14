package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Direct Vavoo engine used by the experimental provider and TvVoo fallback. */
public final class VavooStreamResolver implements StreamResolver {
    private static final int DEFAULT_RESOLUTION_BUDGET_MS = 10_000;

    private final ResolverDefinition definition;
    private final VavooSessionClient sessionClient;
    private final HlsStreamValidator validator;

    public VavooStreamResolver(ResolverDefinition definition) {
        TokenHttpClient fastClient = new TokenHttpClient(5_000, 8_000);
        this.definition = definition;
        this.sessionClient = new VavooSessionClient(definition, fastClient);
        this.validator = new HlsStreamValidator(fastClient);
    }

    VavooStreamResolver(
            ResolverDefinition definition,
            VavooSessionClient sessionClient,
            HlsStreamValidator validator
    ) {
        this.definition = definition;
        this.sessionClient = sessionClient;
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
        ResolutionDeadline deadline = newResolutionDeadline();
        ResolutionContext ambient = ResolutionContext.current();
        if (ambient == null) {
            ResolutionContext root = new ResolutionContext(deadline.remainingMillis());
            try (ResolutionContext.Scope ignored = root.activate()) {
                return resolve(channel, listener, deadline);
            }
        }
        return resolve(channel, listener, deadline);
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
                return resolvePlaybackCandidates(channel, progress, deadline);
            }
        }
        return resolvePlaybackCandidates(channel, progress, deadline);
    }

    /** Resolves all bounded, currently valid Vavoo options for the selector. */
    private List<ResolvedPlaybackCandidate> resolvePlaybackCandidates(
            Channel channel,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        deadline.check();
        LinkedHashSet<String> aliases = new LinkedHashSet<>(definition.resolverAliases(channel));
        Map<String, String> playbackHeaders = TvVooStreamResolver.playbackHeaders();
        boolean allowHttpFallback = definition.getBooleanConfig("allowHttpFallback", true);
        int maximum = definition.getIntConfig("maxResolveCandidates", 8, 1, 12);
        List<ResolvedPlaybackCandidate> result = new ArrayList<>();
        LinkedHashSet<URI> seen = new LinkedHashSet<>();
        final IOException[] lastValidationError = new IOException[1];
        try {
            sessionClient.streamCandidates(
                    channel,
                    new ArrayList<>(aliases),
                    progress,
                    deadline,
                    (candidate, stableIdentity) -> {
                        if (result.size() >= maximum) return true;
                        if (candidate == null || !seen.add(candidate)) return false;
                        try {
                            URI accepted = TvVooStreamResolver.validateCandidate(
                                    validator,
                                    candidate,
                                    allowHttpFallback,
                                    playbackHeaders,
                                    true,
                                    progress
                            );
                            int ordinal = result.size() + 1;
                            String detail = "Vavoo · HLS validada";
                            if (!AppStrings.isBlank(stableIdentity)) {
                                detail += " · catálogo "
                                        + SafePlaybackText.detail(stableIdentity);
                            }
                            result.add(new ResolvedPlaybackCandidate(
                                    "Fuente " + ordinal + " · Vavoo",
                                    detail,
                                    ResolvedPlaybackSource.dynamic(
                                            getId(),
                                            stableIdentity,
                                            accepted,
                                            playbackHeaders,
                                            TvVooStreamResolver.PLAYBACK_USER_AGENT,
                                            expiresAt()
                                    )
                            ));
                            return result.size() >= maximum;
                        } catch (IOException error) {
                            lastValidationError[0] = error;
                            return false;
                        }
                    }
            );
        } catch (IOException error) {
            if (result.isEmpty()) lastValidationError[0] = error;
        }
        if (result.isEmpty()) {
            throw new IOException(
                    "Vavoo no entregó fuentes alternativas.",
                    lastValidationError[0]
            );
        }
        return java.util.Collections.unmodifiableList(result);
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

    ResolvedPlaybackSource resolve(
            Channel channel,
            ResolutionProgressListener listener,
            ResolutionDeadline deadline
    ) throws IOException {
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        deadline.check();
        LinkedHashSet<String> aliases = new LinkedHashSet<>(definition.resolverAliases(channel));
        Map<String, String> playbackHeaders = TvVooStreamResolver.playbackHeaders();
        boolean allowHttpFallback = definition.getBooleanConfig("allowHttpFallback", true);
        final URI[] accepted = new URI[1];
        final IOException[] lastValidationError = new IOException[1];
        try {
            sessionClient.streamCandidates(
                    channel,
                    new ArrayList<>(aliases),
                    progress,
                    deadline,
                    (candidate, stableIdentity) -> {
                        try {
                            accepted[0] = TvVooStreamResolver.validateCandidate(
                                    validator,
                                    candidate,
                                    allowHttpFallback,
                                    playbackHeaders,
                                    true,
                                    progress
                            );
                            return true;
                        } catch (IOException error) {
                            lastValidationError[0] = error;
                            return false;
                        }
                    }
            );
        } catch (IOException error) {
            if (lastValidationError[0] == null) lastValidationError[0] = error;
        }
        if (accepted[0] != null) {
            deadline.check();
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.SOURCE_FOUND,
                    "HLS válido · GET " + SafePlaybackText.url(accepted[0])
                            + " · fuente Vavoo aceptada"
            ));
            return ResolvedPlaybackSource.dynamic(
                    getId(),
                    stableSourceId(channel),
                    accepted[0],
                    playbackHeaders,
                    TvVooStreamResolver.PLAYBACK_USER_AGENT,
                    expiresAt()
            );
        }
        throw new IOException("Vavoo no entregó un HLS reproducible.", lastValidationError[0]);
    }

    @Override
    public void clearSensitiveState() {
        sessionClient.clear();
    }

    private long expiresAt() {
        long ttl = cacheTtlMillis();
        return ttl <= 0L ? 0L : System.currentTimeMillis() + ttl;
    }
}

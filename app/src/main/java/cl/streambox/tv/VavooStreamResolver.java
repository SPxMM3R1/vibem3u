package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Direct Vavoo engine used by the experimental provider and TvVoo fallback. */
public final class VavooStreamResolver implements StreamResolver {
    private static final int DEFAULT_RESOLUTION_BUDGET_MS = 10_000;
    private static final int DEFAULT_PARALLEL_CANDIDATES = 3;

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
        return resolve(
                channel,
                listener,
                new ResolutionDeadline(
                        definition.getIntConfig(
                                "resolutionBudgetMs",
                                DEFAULT_RESOLUTION_BUDGET_MS,
                                2_000,
                                20_000
                        )
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
        List<URI> candidates = sessionClient.resolveCandidates(
                channel,
                new ArrayList<>(aliases),
                progress
        );
        deadline.check();
        Map<String, String> playbackHeaders = TvVooStreamResolver.playbackHeaders();
        boolean allowHttpFallback = definition.getBooleanConfig("allowHttpFallback", true);
        int maxCandidates = definition.getIntConfig("maxResolveCandidates", 8, 1, 12);
        int parallelCandidates = definition.getIntConfig(
                "parallelCandidates",
                DEFAULT_PARALLEL_CANDIDATES,
                1,
                4
        );
        HlsCandidateRace.Result race = HlsCandidateRace.firstValid(
                candidates,
                maxCandidates,
                parallelCandidates,
                deadline,
                0,
                Math.min(candidates.size(), maxCandidates),
                progress,
                candidate -> TvVooStreamResolver.validateCandidate(
                        validator,
                        candidate,
                        allowHttpFallback,
                        playbackHeaders,
                        true,
                        progress
                )
        );
        if (race.getSource() != null) {
            progress.onProgress(ResolutionProgress.of(ResolutionStage.SOURCE_FOUND));
            return ResolvedPlaybackSource.dynamic(
                    getId(),
                    stableSourceId(channel),
                    race.getSource(),
                    playbackHeaders,
                    TvVooStreamResolver.PLAYBACK_USER_AGENT,
                    expiresAt()
            );
        }
        throw new IOException("Vavoo no entregó un HLS reproducible.", race.getLastError());
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

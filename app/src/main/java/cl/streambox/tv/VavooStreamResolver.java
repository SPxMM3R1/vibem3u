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

    @Override
    public ResolvedPlaybackSource resolve(Channel channel) throws IOException {
        LinkedHashSet<String> aliases = new LinkedHashSet<>(definition.resolverAliases(channel));
        List<URI> candidates = sessionClient.resolveCandidates(
                channel,
                new ArrayList<>(aliases)
        );
        IOException lastError = null;
        Map<String, String> playbackHeaders = TvVooStreamResolver.playbackHeaders();
        for (URI candidate : candidates) {
            try {
                validator.validate(candidate, playbackHeaders);
                return ResolvedPlaybackSource.dynamic(
                        getId(),
                        stableSourceId(channel),
                        candidate,
                        playbackHeaders,
                        TvVooStreamResolver.PLAYBACK_USER_AGENT,
                        expiresAt()
                );
            } catch (IOException error) {
                lastError = error;
            }
        }
        throw new IOException("Vavoo no entregó un HLS reproducible.", lastError);
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

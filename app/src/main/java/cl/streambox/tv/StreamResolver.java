package cl.streambox.tv;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Resolves a provider-specific stream immediately before playback. */
public interface StreamResolver {
    String getId();

    boolean supports(Channel channel);

    ResolvedPlaybackSource resolve(Channel channel) throws IOException;

    /**
     * Resolves a source while optionally reporting non-sensitive provider stages.
     * Existing resolvers remain source-compatible through the default method.
     */
    default ResolvedPlaybackSource resolve(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        return resolve(channel);
    }

    /**
     * Resolves a bounded list of short-lived alternatives for an explicit
     * source selector. Normal playback must continue using {@link #resolve}
     * so opening a channel does not wait for every alternative.
     */
    default List<ResolvedPlaybackCandidate> resolvePlaybackCandidates(
            Channel channel,
            ResolutionProgressListener listener
    ) throws IOException {
        ResolvedPlaybackSource source = resolve(channel, listener);
        if (source == null) return Collections.emptyList();
        return Collections.singletonList(new ResolvedPlaybackCandidate(
                "Fuente actual",
                "Fuente HLS validada",
                source
        ));
    }

    default String stableSourceId(Channel channel) {
        if (channel == null) return "";
        String tvgId = channel.getTvgId();
        return tvgId == null || AppStrings.isBlank(tvgId) ? channel.getName() : tvgId;
    }

    default long cacheTtlMillis() {
        return 0L;
    }

    /**
     * Whether the resolved playback URL may be reused during this process.
     *
     * <p>Resolvers that return temporary URLs or tokens should opt in only
     * when the provider permits bounded reuse during the current process. A
     * positive TTL is mandatory so the coordinator can discard the source
     * before its session window ends; resolvers that require a fresh token for
     * every opening must return {@code false}.</p>
     */
    default boolean cacheResolvedSource() {
        return true;
    }

    /**
     * Whether an in-memory source containing provider credentials should
     * survive a temporary playback pause such as leaving the app window.
     * Credentials are still discarded on expiry, authorization failure,
     * resolver changes, or the end of the app session.
     */
    default boolean keepSessionSourceOnPlaybackPause() {
        return false;
    }

    /** Drops provider credentials and other session-only sensitive state. */
    default void clearSensitiveState() {
        // Most resolvers do not hold credentials between requests.
    }
}

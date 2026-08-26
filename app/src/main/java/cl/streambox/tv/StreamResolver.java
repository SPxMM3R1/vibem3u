package cl.streambox.tv;

import java.io.IOException;

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

    default String stableSourceId(Channel channel) {
        if (channel == null) return "";
        String tvgId = channel.getTvgId();
        return tvgId == null || tvgId.isBlank() ? channel.getName() : tvgId;
    }

    default long cacheTtlMillis() {
        return 0L;
    }

    /**
     * Whether the resolved playback URL may be reused during this process.
     *
     * <p>Implementations that return temporary URLs or tokens should return
     * {@code false}. They may still expose a TTL for diagnostics or for
     * stable metadata, but a temporary playback source must be generated
     * again when the channel is opened.</p>
     */
    default boolean cacheResolvedSource() {
        return true;
    }

    /** Drops provider credentials and other session-only sensitive state. */
    default void clearSensitiveState() {
        // Most resolvers do not hold credentials between requests.
    }
}

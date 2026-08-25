package cl.streambox.tv;

import java.io.IOException;

/** Resolves a provider-specific stream immediately before playback. */
public interface StreamResolver {
    String getId();

    boolean supports(Channel channel);

    ResolvedPlaybackSource resolve(Channel channel) throws IOException;

    default String stableSourceId(Channel channel) {
        if (channel == null) return "";
        String tvgId = channel.getTvgId();
        return tvgId == null || tvgId.isBlank() ? channel.getName() : tvgId;
    }

    default long cacheTtlMillis() {
        return 0L;
    }

    /** Drops provider credentials and other session-only sensitive state. */
    default void clearSensitiveState() {
        // Most resolvers do not hold credentials between requests.
    }
}

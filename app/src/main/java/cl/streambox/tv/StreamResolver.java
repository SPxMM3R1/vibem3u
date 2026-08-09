package cl.streambox.tv;

import java.io.IOException;

/** Resolves a provider-specific stream immediately before playback. */
public interface StreamResolver {
    String getId();

    boolean supports(Channel channel);

    ResolvedPlaybackSource resolve(Channel channel) throws IOException;
}

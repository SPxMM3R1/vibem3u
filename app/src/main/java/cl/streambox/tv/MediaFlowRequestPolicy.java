package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;

/** Request guard shared by MediaFlow extraction and Media3 playback. */
public final class MediaFlowRequestPolicy {
    private MediaFlowRequestPolicy() {}

    public static void requireConfiguredOrigin(URI request, URI configuredOrigin)
            throws IOException {
        MediaFlowOriginPolicy.requireSameOrigin(request, configuredOrigin);
    }

    public static URI resolveRedirect(
            URI current,
            String location,
            URI configuredOrigin
    ) throws IOException {
        if (current == null || location == null || AppStrings.isBlank(location)) {
            throw new IOException("Redirección MediaFlow sin destino.");
        }
        final URI resolved;
        try {
            resolved = current.resolve(location);
        } catch (IllegalArgumentException error) {
            throw new IOException("Redirección MediaFlow inválida.", error);
        }
        requireConfiguredOrigin(resolved, configuredOrigin);
        return resolved;
    }

    public static void requirePlaybackUri(URI playbackUri, URI configuredOrigin)
            throws IOException {
        requireConfiguredOrigin(playbackUri, configuredOrigin);
        if (playbackUri.getUserInfo() != null
                || playbackUri.getQuery() == null
                || playbackUri.getQuery().isEmpty()) {
            throw new IOException("URL de reproducción MediaFlow inválida.");
        }
    }
}

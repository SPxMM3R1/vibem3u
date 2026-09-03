package cl.streambox.tv;

import androidx.media3.common.PlaybackException;

import java.net.URI;
import java.util.Locale;

/** Decides when a Media3 HTTP failure invalidates a dynamically resolved source. */
final class ResolvedSourceRefreshPolicy {
    private ResolvedSourceRefreshPolicy() {}

    static boolean shouldRefresh(int responseCode, URI failedRequestUri) {
        if (responseCode == 401 || responseCode == 403) return true;
        // HTTP 410 is an explicit statement that the resource is gone. For a
        // dynamically resolved HLS source that includes media segments, not
        // only manifests: keeping the same generated host cannot recover it.
        if (responseCode == 410) return true;
        return responseCode == 404 && isManifest(failedRequestUri);
    }

    /**
     * Extends HTTP-based invalidation with failures that indicate that the
     * resolved HLS source itself is not usable. Network timeouts and ordinary
     * server errors deliberately stay on the bounded same-source retry path;
     * a live stream can transiently miss a segment without needing a new
     * resolver request.
     */
    static boolean shouldRefresh(
            int responseCode,
            URI failedRequestUri,
            int playbackErrorCode
    ) {
        if (shouldRefresh(responseCode, failedRequestUri)) return true;
        if (responseCode >= 400) return false;
        return playbackErrorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                || playbackErrorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                || playbackErrorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || playbackErrorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
    }

    static boolean isManifest(URI uri) {
        if (uri == null || uri.getPath() == null) return false;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".m3u8") || path.endsWith(".mpd");
    }
}

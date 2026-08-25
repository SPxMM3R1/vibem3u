package cl.streambox.tv;

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

    static boolean isManifest(URI uri) {
        if (uri == null || uri.getPath() == null) return false;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".m3u8") || path.endsWith(".mpd");
    }
}

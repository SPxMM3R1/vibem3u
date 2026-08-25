package cl.streambox.tv;

import java.net.URI;
import java.util.Locale;

/** Decides when a Media3 HTTP failure invalidates a dynamically resolved source. */
final class ResolvedSourceRefreshPolicy {
    private ResolvedSourceRefreshPolicy() {}

    static boolean shouldRefresh(int responseCode, URI failedRequestUri) {
        if (responseCode == 401 || responseCode == 403) return true;
        if (responseCode != 404 && responseCode != 410) return false;
        return isManifest(failedRequestUri);
    }

    static boolean isManifest(URI uri) {
        if (uri == null || uri.getPath() == null) return false;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".m3u8") || path.endsWith(".mpd");
    }
}

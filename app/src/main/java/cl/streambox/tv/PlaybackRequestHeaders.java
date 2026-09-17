package cl.streambox.tv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Header policy for Media3 playback requests. */
final class PlaybackRequestHeaders {
    private PlaybackRequestHeaders() {
    }

    /**
     * Removes User-Agent from the request-property map.
     *
     * Media3 receives the effective User-Agent separately through
     * OkHttpDataSource.Factory#setUserAgent. Keeping another User-Agent in the
     * map makes OkHttp send duplicate headers, which some HLS providers reject
     * with HTTP 400. All other provider headers are preserved.
     */
    static Map<String, String> withoutUserAgent(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || "User-Agent".equalsIgnoreCase(name)) {
                continue;
            }
            sanitized.put(name, entry.getValue());
        }
        if (sanitized.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(sanitized);
    }
}

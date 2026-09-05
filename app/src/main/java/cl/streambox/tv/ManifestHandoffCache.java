package cl.streambox.tv;

import java.net.URI;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-use, in-memory handoff for playlists fetched while resolving a
 * source. It is tied to one ResolutionContext (children share their parent's
 * cache), expires quickly, and never accepts segments or encryption keys.
 */
public final class ManifestHandoffCache {
    public static final long DEFAULT_TTL_MILLIS = 5_000L;
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_BYTES = 1 * 1024 * 1024;

    private final ResolutionContext context;
    private final long ttlMillis;
    private final long ttlNanos;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public ManifestHandoffCache(ResolutionContext context) {
        this(context, DEFAULT_TTL_MILLIS);
    }

    ManifestHandoffCache(ResolutionContext context, long ttlMillis) {
        if (context == null) throw new NullPointerException("context");
        this.context = context;
        this.ttlMillis = Math.max(1L, Math.min(DEFAULT_TTL_MILLIS, ttlMillis));
        this.ttlNanos = this.ttlMillis > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE
                : this.ttlMillis * 1_000_000L;
    }

    public ResolutionContext getContext() {
        return context;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    /** Stores a decoded, validated playlist for one future Media3 open. */
    public synchronized void put(
            URI originalUri,
            URI finalUri,
            Map<String, String> headers,
            byte[] rawBytes
    ) {
        if (!usable() || !isPlaylist(originalUri) || rawBytes == null
                || rawBytes.length == 0 || rawBytes.length > MAX_BYTES) return;
        URI safeFinal = finalUri == null ? originalUri : finalUri;
        if (!isPlaylist(safeFinal)) return;
        purgeExpiredLocked();
        while (entries.size() >= MAX_ENTRIES) {
            Iterator<String> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) break;
            String oldest = iterator.next();
            Entry removed = entries.get(oldest);
            iterator.remove();
            if (removed != null) removed.data.clearBytes();
        }
        long now = System.currentTimeMillis();
        String cacheKey = key(originalUri);
        Entry replaced = entries.put(cacheKey, new Entry(new ManifestHandoffData(
                originalUri,
                safeFinal,
                headers,
                rawBytes,
                now
        ), now));
        if (replaced != null) replaced.data.clearBytes();
    }

    public void put(
            String originalUri,
            String finalUri,
            Map<String, String> headers,
            byte[] rawBytes
    ) {
        URI original = parseUri(originalUri);
        URI target = finalUri == null || finalUri.isBlank() ? original : parseUri(finalUri);
        put(original, target, headers, rawBytes);
    }

    /** Returns a non-consuming snapshot, primarily for diagnostics/tests. */
    public synchronized ManifestHandoffData peek(URI originalUri) {
        if (!usable()) return null;
        purgeExpiredLocked();
        Entry entry = entries.get(key(originalUri));
        return entry == null ? null : copy(entry.data);
    }

    public ManifestHandoffData peek(String originalUri) {
        return peek(parseUri(originalUri));
    }

    /** Atomically consumes the handoff. A second call always returns null. */
    public synchronized ManifestHandoffData consume(URI originalUri) {
        if (!usable()) return null;
        purgeExpiredLocked();
        Entry entry = entries.remove(key(originalUri));
        if (entry == null) return null;
        ManifestHandoffData result = copy(entry.data);
        entry.data.clearBytes();
        return result;
    }

    public ManifestHandoffData consume(String originalUri) {
        return consume(parseUri(originalUri));
    }

    /** Alias used by DataSource implementations. */
    public ManifestHandoffData take(URI originalUri) {
        return consume(originalUri);
    }

    public synchronized int size() {
        purgeExpiredLocked();
        return entries.size();
    }

    public synchronized void clear() {
        for (Entry entry : entries.values()) entry.data.clearBytes();
        entries.clear();
    }

    private boolean usable() {
        return !context.isCancelled() && context.remainingMillis() > 0L;
    }

    private void purgeExpiredLocked() {
        long now = System.nanoTime();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> entry = iterator.next();
            if (now - entry.getValue().createdAtNanos >= ttlNanos) {
                entry.getValue().data.clearBytes();
                iterator.remove();
            }
        }
    }

    private static ManifestHandoffData copy(ManifestHandoffData source) {
        return new ManifestHandoffData(
                source.getOriginalUri(),
                source.getFinalUri(),
                source.getHeaders(),
                source.getRawBytes(),
                source.getCapturedAtMillis()
        );
    }

    static boolean isPlaylist(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String path = uri.getPath();
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");
    }

    private static String key(URI uri) {
        if (uri == null) return "";
        try {
            // URI#getPath/#getQuery decode percent escapes and rebuilding a
            // URI can re-encode them differently. Signed HLS URLs must remain
            // byte-exact keys, so remove only the fragment from toString().
            String raw = uri.toString();
            int fragment = raw.indexOf('#');
            return fragment < 0 ? raw : raw.substring(0, fragment);
        } catch (Exception ignored) {
            return uri.toString();
        }
    }

    private static URI parseUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class Entry {
        final ManifestHandoffData data;
        final long createdAtNanos;

        Entry(ManifestHandoffData data, long createdAtMillis) {
            this.data = data;
            this.createdAtNanos = System.nanoTime();
        }
    }
}

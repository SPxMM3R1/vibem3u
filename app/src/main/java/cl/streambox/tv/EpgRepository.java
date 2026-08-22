package cl.streambox.tv;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;

public final class EpgRepository {
    private static final int MAX_EPG_BYTES = 32 * 1024 * 1024;
    private static final String USER_AGENT = "VibeM3U/0.4.19 (Android TV)";
    private static final String ACCEPT = "application/xml, text/xml, text/plain, */*";

    private final HttpResourceCache cache;

    public EpgRepository(Context context) {
        cache = new HttpResourceCache(
                context,
                "epg_cache",
                3,
                48L * 1024L * 1024L
        );
    }

    public EpgData loadCached(URI epgUri) throws IOException {
        String url = validUrl(epgUri);
        if (url == null) return null;
        HttpResourceCache.CachedResource resource = cache.readCached(url, MAX_EPG_BYTES);
        if (resource == null) return null;
        try {
            return parse(resource.getBytes());
        } catch (IOException error) {
            cache.remove(url);
            return null;
        }
    }

    public LoadResult downloadIfChanged(URI epgUri) throws IOException {
        String url = validUrl(epgUri);
        if (url == null) return new LoadResult(EpgData.empty(), false);

        HttpResourceCache.FetchResult fetched = cache.fetch(
                url,
                MAX_EPG_BYTES,
                USER_AGENT,
                ACCEPT
        );
        EpgData data;
        try {
            data = parse(fetched.getResource().getBytes());
        } catch (IOException error) {
            if (fetched.isChanged()) throw error;
            cache.remove(url);
            fetched = cache.fetch(url, MAX_EPG_BYTES, USER_AGENT, ACCEPT);
            data = parse(fetched.getResource().getBytes());
        }

        cache.commit(url, fetched, fetched.getResource().getBytes());
        return new LoadResult(data, fetched.isChanged());
    }

    /** Compatibility entry point for callers that do not need change metadata. */
    public EpgData download(URI epgUri) throws IOException {
        return downloadIfChanged(epgUri).getData();
    }

    private static EpgData parse(byte[] bytes) throws IOException {
        return EpgParser.parse(new ByteArrayInputStream(bytes));
    }

    private static String validUrl(URI epgUri) throws IOException {
        if (epgUri == null || epgUri.getScheme() == null) return null;
        String scheme = epgUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("La URL de programación no es HTTP.");
        }
        return epgUri.toString();
    }

    public static final class LoadResult {
        private final EpgData data;
        private final boolean changed;

        LoadResult(EpgData data, boolean changed) {
            this.data = data;
            this.changed = changed;
        }

        public EpgData getData() {
            return data;
        }

        public boolean isChanged() {
            return changed;
        }
    }
}

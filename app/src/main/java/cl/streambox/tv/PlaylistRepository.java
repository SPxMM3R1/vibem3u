package cl.streambox.tv;

import android.content.Context;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PlaylistRepository {
    private static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;
    private static final String USER_AGENT = "VibeM3U/0.4.19 (Android TV)";
    private static final String ACCEPT =
            "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*";

    private final HttpResourceCache cache;

    public PlaylistRepository(Context context) {
        cache = new HttpResourceCache(
                context,
                "playlist_cache",
                4,
                16L * 1024L * 1024L
        );
    }

    public Playlist loadCached(String url) throws IOException {
        URI playlistUri = parseUri(url);
        HttpResourceCache.CachedResource resource = cache.readCached(url, MAX_PLAYLIST_BYTES);
        if (resource == null) return null;
        try {
            return parseAndValidate(resource.getBytes(), playlistUri);
        } catch (IOException error) {
            cache.remove(url);
            return null;
        }
    }

    public LoadResult downloadIfChanged(String url) throws IOException {
        URI playlistUri = parseUri(url);
        HttpResourceCache.CachedResource cached = cache.readCached(url, MAX_PLAYLIST_BYTES);
        HttpResourceCache.FetchResult fetched = cache.fetch(
                url,
                MAX_PLAYLIST_BYTES,
                USER_AGENT,
                ACCEPT
        );
        Playlist playlist;
        try {
            playlist = parseAndValidate(fetched.getResource().getBytes(), playlistUri);
        } catch (IOException error) {
            if (fetched.isChanged()) throw error;

            // A partially written or obsolete cached file must not block a
            // fresh request forever after a 304 or a temporary network error.
            cache.remove(url);
            cached = null;
            fetched = cache.fetch(url, MAX_PLAYLIST_BYTES, USER_AGENT, ACCEPT);
            playlist = parseAndValidate(fetched.getResource().getBytes(), playlistUri);
        }

        String content = new String(
                fetched.getResource().getBytes(),
                StandardCharsets.UTF_8
        );
        byte[] diskContent = M3uCacheSanitizer
                .forDisk(content)
                .getBytes(StandardCharsets.UTF_8);
        cache.commit(url, fetched, diskContent);
        boolean changed = cached == null || !Arrays.equals(cached.getBytes(), diskContent);
        return new LoadResult(playlist, changed);
    }

    /** Compatibility entry point for callers that do not need change metadata. */
    public Playlist download(String url) throws IOException {
        return downloadIfChanged(url).getPlaylist();
    }

    private static Playlist parseAndValidate(byte[] bytes, URI playlistUri) throws IOException {
        Playlist playlist = M3uParser.parsePlaylist(
                new String(bytes, StandardCharsets.UTF_8),
                playlistUri
        );
        if (playlist.getChannels().isEmpty()) {
            throw new IOException("La lista no contiene canales reproducibles.");
        }
        return playlist;
    }

    private static URI parseUri(String url) throws IOException {
        try {
            URI playlistUri = URI.create(url);
            if (playlistUri.getScheme() == null) throw new IllegalArgumentException();
            return playlistUri;
        } catch (IllegalArgumentException ex) {
            throw new IOException("La URL de la lista no es válida.", ex);
        }
    }

    public static final class LoadResult {
        private final Playlist playlist;
        private final boolean changed;

        LoadResult(Playlist playlist, boolean changed) {
            this.playlist = playlist;
            this.changed = changed;
        }

        public Playlist getPlaylist() {
            return playlist;
        }

        public boolean isChanged() {
            return changed;
        }
    }
}

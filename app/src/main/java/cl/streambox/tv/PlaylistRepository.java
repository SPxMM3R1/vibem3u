package cl.streambox.tv;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class PlaylistRepository {
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 4;
    private static final long MAX_CACHE_BYTES = 24L * 1024L * 1024L;
    private static final String USER_AGENT = "VibeM3U/0.2.1 (Android TV)";

    private final File cacheDirectory;

    public PlaylistRepository(Context context) {
        cacheDirectory = new File(context.getCacheDir(), "playlists");
        if (!cacheDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDirectory.mkdirs();
        }
    }

    public Playlist loadCached(String url) {
        URI playlistUri = parseUri(url);
        File cacheFile = cacheFile(playlistUri);
        if (playlistUri == null || cacheFile == null || !cacheFile.isFile()) return null;

        try {
            byte[] bytes;
            try (InputStream input = new FileInputStream(cacheFile)) {
                bytes = readLimited(input);
            }
            Playlist cached = parse(bytes, playlistUri);
            if (cached.getChannels().isEmpty()) return null;
            //noinspection ResultOfMethodCallIgnored
            cacheFile.setLastModified(System.currentTimeMillis());
            return cached;
        } catch (IOException ignored) {
            // Una caché dañada no debe bloquear la descarga nueva.
            //noinspection ResultOfMethodCallIgnored
            cacheFile.delete();
            return null;
        }
    }

    public Playlist download(String url) throws IOException {
        URI playlistUri = parseUri(url);
        if (playlistUri == null) {
            throw new IOException("La URL de la lista no es válida.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*");
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("El servidor respondió HTTP " + responseCode + ".");
            }
            byte[] bytes = readLimited(connection.getInputStream());
            Playlist downloaded = parse(bytes, playlistUri);
            if (downloaded.getChannels().isEmpty()) {
                throw new IOException("La lista no contiene canales reproducibles.");
            }
            saveCached(playlistUri, bytes);
            return downloaded;
        } finally {
            connection.disconnect();
        }
    }

    private static Playlist parse(byte[] bytes, URI playlistUri) {
        return M3uParser.parsePlaylist(new String(bytes, StandardCharsets.UTF_8), playlistUri);
    }

    private void saveCached(URI playlistUri, byte[] bytes) {
        File target = cacheFile(playlistUri);
        if (target == null) return;

        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        boolean movedPrevious = false;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
            }
            if (backup.exists() && !backup.delete()) {
                throw new IOException("No se pudo preparar la caché de la lista.");
            }
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw new IOException("No se pudo conservar la caché de la lista anterior.");
                }
                movedPrevious = true;
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("No se pudo guardar la caché de la lista nueva.");
            }
            if (movedPrevious) {
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
            trimCache();
        } catch (IOException ignored) {
            if (movedPrevious && !target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private void trimCache() {
        File[] files = cacheDirectory.listFiles((directory, name) -> name.endsWith(".m3u"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, new Comparator<>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });
        long totalBytes = 0;
        for (int index = 0; index < files.length; index++) {
            File file = files[index];
            totalBytes += file.length();
            if (index >= MAX_CACHE_FILES || totalBytes > MAX_CACHE_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private File cacheFile(URI playlistUri) {
        if (playlistUri == null || playlistUri.getScheme() == null) return null;
        return new File(cacheDirectory, cacheKey(playlistUri.toString()) + ".m3u");
    }

    private static URI parseUri(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            return uri.getScheme() == null ? null : uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String cacheKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_PLAYLIST_BYTES) {
                    throw new IOException("La lista supera el límite de 8 MB.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}

package cl.streambox.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class PlaylistRepository {
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;
    private static final String USER_AGENT = "VibeM3U/0.2.1 (Android TV)";

    public Playlist download(String url) throws IOException {
        URI playlistUri;
        try {
            playlistUri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IOException("La URL de la lista no es válida.", ex);
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
            Playlist playlist = M3uParser.parsePlaylist(new String(bytes, StandardCharsets.UTF_8), playlistUri);
            if (playlist.getChannels().isEmpty()) {
                throw new IOException("La lista no contiene canales reproducibles.");
            }
            return playlist;
        } finally {
            connection.disconnect();
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

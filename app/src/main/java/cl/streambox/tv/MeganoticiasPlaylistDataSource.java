package cl.streambox.tv;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts MegaMedia's numeric HLS playlists for Media3 without touching media
 * segments or persisting the short-lived authorization URL.
 */
final class MeganoticiasPlaylistDataSource implements DataSource {
    private static final int READ_BUFFER_BYTES = 16 * 1024;

    private final DataSource upstream;
    private byte[] playlistBody;
    private int playlistPosition;

    MeganoticiasPlaylistDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        playlistBody = null;
        playlistPosition = 0;
        long upstreamLength = upstream.open(dataSpec);
        if (!isPlaylist(dataSpec.uri)) return upstreamLength;

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(Math.max(0L, upstreamLength), 64 * 1024L)
        );
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int total = 0;
        int count;
        while ((count = upstream.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Solicitud cancelada.");
            }
            if (count <= 0) continue;
            total += count;
            if (total > MeganoticiasHlsDecoder.MAX_PLAYLIST_BYTES) {
                throw new IOException("Playlist Meganoticias demasiado grande.");
            }
            output.write(buffer, 0, count);
        }
        playlistBody = MeganoticiasHlsDecoder.decodeIfNeeded(output.toByteArray());
        return playlistBody.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (playlistBody == null) return upstream.read(buffer, offset, length);
        if (playlistPosition >= playlistBody.length) return C.RESULT_END_OF_INPUT;
        int count = Math.min(length, playlistBody.length - playlistPosition);
        System.arraycopy(playlistBody, playlistPosition, buffer, offset, count);
        playlistPosition += count;
        return count;
    }

    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        playlistBody = null;
        playlistPosition = 0;
        upstream.close();
    }

    private static boolean isPlaylist(Uri uri) {
        if (uri == null || uri.getPath() == null) return false;
        return uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new MeganoticiasPlaylistDataSource(
                    upstreamFactory.createDataSource()
            );
        }
    }
}

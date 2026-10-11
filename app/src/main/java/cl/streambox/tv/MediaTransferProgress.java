package cl.streambox.tv;

import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.util.Locale;

/**
 * Hora del último byte de video recibido (0.5.89). El vigilante de cortes lo usa para no
 * reiniciar un canal cuyo servidor es lento pero sigue entregando el segmento: Nauta tarda
 * a veces más de 5 s en un segmento de 5 s y el reinicio volvía a dejarlo sin búfer.
 * Las listas HLS no cuentan: se recargan cada pocos segundos aunque el video esté cortado.
 */
@UnstableApi
final class MediaTransferProgress implements TransferListener {
    private volatile long lastMediaBytesElapsedMs = -1L;

    long lastMediaBytesElapsedMs() {
        return lastMediaBytesElapsedMs;
    }

    void reset() {
        lastMediaBytesElapsedMs = -1L;
    }

    static boolean isPlaylist(Uri uri) {
        String path = uri == null ? null : uri.getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    @Override public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {}

    @Override public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {}

    @Override public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork,
            int bytesTransferred) {
        if (bytesTransferred > 0 && isNetwork && !isPlaylist(dataSpec.uri)) {
            lastMediaBytesElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    @Override public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {}
}

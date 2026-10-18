package cl.streambox.tv;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import java.net.URI;

/** Surface rejected temporary sources promptly; keep normal CDN/segment recovery in Media3. */
@UnstableApi
final class PlaybackLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {
    private final boolean renewable;
    PlaybackLoadErrorPolicy(boolean renewable) { this.renewable = renewable; }

    /** Reintentos de un segmento cortado al reanudarlo (ver {@link #positionRetryDelayMs}). */
    static final int POSITION_RETRIES = 4;

    @Override public long getRetryDelayMsFor(LoadErrorInfo info) {
        android.util.Log.i("VibeM3U-Playback", "error de carga #" + info.errorCount
                + " bytes=" + info.loadEventInfo.bytesLoaded
                + " · " + info.exception.getClass().getSimpleName());
        if (requiresFreshSource(info)) return C.TIME_UNSET;
        if (DataSourceException.isCausedByPositionOutOfRange(info.exception)) {
            return positionRetryDelayMs(info.errorCount);
        }
        return super.getRetryDelayMsFor(info);
    }

    /**
     * Segmento cortado a mitad de descarga (0.6.12): Media3 lo reanuda pidiendo un rango; muchos
     * servidores IP lo ignoran y mandan el segmento entero, y si la conexión vuelve a cortarse
     * mientras se saltan los bytes ya recibidos, Media3 lo llama «posición fuera de rango» y no
     * lo reintenta: el canal entero se reconectaba. Aquí se reintenta el mismo segmento unas
     * pocas veces antes de darlo por perdido.
     */
    static long positionRetryDelayMs(int errorCount) {
        if (errorCount > POSITION_RETRIES) return C.TIME_UNSET;
        return Math.min(Math.max(1, errorCount) * 500L, 2_000L);
    }

    @Override public FallbackSelection getFallbackSelectionFor(FallbackOptions options, LoadErrorInfo info) {
        if (requiresFreshSource(info)) return null;
        return super.getFallbackSelectionFor(options, info);
    }

    private boolean requiresFreshSource(LoadErrorInfo info) {
        if (!renewable) return false;
        Throwable error = info.exception;
        for (int depth = 0; error != null && depth < 20; depth++, error = error.getCause()) {
            if (error instanceof HttpDataSource.InvalidResponseCodeException) {
                HttpDataSource.InvalidResponseCodeException http =
                        (HttpDataSource.InvalidResponseCodeException) error;
                URI uri;
                try { uri = URI.create(http.dataSpec.uri.toString()); }
                catch (IllegalArgumentException invalid) { uri = null; }
                return ResolvedSourceRefreshPolicy.shouldRefresh(http.responseCode, uri);
            }
        }
        return false;
    }
}

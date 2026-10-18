package cl.streambox.tv;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Fuente de datos que aplica {@link TsAudioPmtFilter} a los segmentos MPEG-TS (0.6.12). Todo lo
 * demás (listas, MP4, claves) pasa igual: el filtro solo actúa si el contenido es TS.
 *
 * <p>También acepta un segmento TS que el servidor corta al final («unexpected end of stream»)
 * cuando ya llegó al menos el {@link #ACCEPTED_TRUNCATION_RATIO} de lo anunciado. Pasa con
 * servidores IP que publican el segmento más nuevo antes de terminar de escribirlo: faltan unos
 * pocos bytes del final (a veces con la sincronía TS rota en los últimos kilobytes). Media3 lo
 * reanudaría pidiendo un rango que esos servidores ignoran, fallaría con «posición fuera de
 * rango» y la app reconectaría el canal entero (segundos en negro). Aceptándolo, el extractor
 * se resincroniza y a lo más se pierde un trozo del último cuadro.
 */
@UnstableApi
final class TsAudioFilterDataSource implements DataSource {
    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        private final TsAudioSession session;

        Factory(DataSource.Factory upstream, TsAudioSession session) {
            this.upstream = upstream;
            this.session = session;
        }

        @Override
        public DataSource createDataSource() {
            return new TsAudioFilterDataSource(upstream.createDataSource(), session);
        }
    }

    static final double ACCEPTED_TRUNCATION_RATIO = 0.9;

    private final DataSource upstream;
    private final TsAudioSession session;
    @Nullable private TsAudioStreamRewriter rewriter;
    private boolean upstreamEnded;
    private long announcedLength = C.LENGTH_UNSET;
    private long received;

    TsAudioFilterDataSource(DataSource upstream, TsAudioSession session) {
        this.upstream = upstream;
        this.session = session;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        long length = upstream.open(dataSpec);
        rewriter = new TsAudioStreamRewriter(session.newFilter(), dataSpec.position);
        upstreamEnded = false;
        announcedLength = length;
        received = 0;
        return length;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) return 0;
        TsAudioStreamRewriter current = rewriter;
        if (current == null) return upstream.read(target, offset, length);
        while (!current.hasReady()) {
            if (upstreamEnded) return C.RESULT_END_OF_INPUT;
            int writeAt = current.prepareWrite();
            int count;
            try {
                count = upstream.read(current.buffer(), writeAt, current.writableSpace());
            } catch (IOException error) {
                if (!acceptsTruncation(current, error)) throw error;
                android.util.Log.i("VibeM3U-Playback", "segmento TS incompleto aceptado: "
                        + received + " de " + announcedLength + " bytes"
                        + (current.syncLostAt >= 0 ? " (sincronía perdida en " + current.syncLostAt + ")" : ""));
                count = C.RESULT_END_OF_INPUT;
            }
            if (count > 0) received += count;
            if (count == C.RESULT_END_OF_INPUT) {
                upstreamEnded = true;
                current.commit(0, true);
            } else {
                current.commit(count, false);
            }
        }
        return current.drain(target, offset, length);
    }

    private boolean acceptsTruncation(TsAudioStreamRewriter current, IOException error) {
        return current.isTransportStream()
                && isUnexpectedEndOfStream(error)
                && shouldAcceptTruncation(received, announcedLength);
    }

    static boolean shouldAcceptTruncation(long received, long announcedLength) {
        return announcedLength > 0 && received > 0
                && received >= announcedLength * ACCEPTED_TRUNCATION_RATIO;
    }

    static boolean isUnexpectedEndOfStream(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof java.net.ProtocolException && error.getMessage() != null
                    && error.getMessage().contains("unexpected end of stream")) {
                return true;
            }
        }
        return false;
    }

    @Nullable
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
        rewriter = null;
        upstreamEnded = false;
        upstream.close();
    }
}

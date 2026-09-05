package cl.streambox.tv;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serves one validated playlist handoff, delegating segments and keys upstream. */
@UnstableApi
public final class ManifestHandoffDataSource implements DataSource {
    private final DataSource upstream;
    private final ManifestHandoffCache cache;

    private ManifestHandoffData handoff;
    private byte[] body;
    private long position;
    private long endPosition;
    private Uri openedUri;
    private boolean upstreamOpened;

    public ManifestHandoffDataSource(DataSource upstream, ManifestHandoffCache cache) {
        if (upstream == null) throw new NullPointerException("upstream");
        if (cache == null) throw new NullPointerException("cache");
        this.upstream = upstream;
        this.cache = cache;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        resetState();
        if (dataSpec == null || dataSpec.uri == null) throw new IOException("URI ausente.");

        // A handoff is looked up only for playlist URIs. Segment and key
        // requests can never consume or enter this cache.
        URIHolder uri = URIHolder.from(dataSpec.uri);
        if (uri.playlist) {
            handoff = cache.consume(uri.javaUri);
        }
        if (handoff != null) {
            body = handoff.getRawBytes();
            long requestedPosition = Math.max(0L, dataSpec.position);
            if (requestedPosition > body.length) {
                throw new IOException("Posición de playlist fuera de rango.");
            }
            position = requestedPosition;
            long requestedEnd = body.length;
            if (dataSpec.length != C.LENGTH_UNSET) {
                if (dataSpec.length < 0L) throw new IOException("Longitud de playlist inválida.");
                requestedEnd = Math.min(body.length, requestedPosition + dataSpec.length);
            }
            endPosition = Math.max(position, requestedEnd);
            openedUri = Uri.parse(handoff.getFinalUri().toString());
            return endPosition - position;
        }

        upstreamOpened = true;
        long length = upstream.open(dataSpec);
        openedUri = upstream.getUri();
        return length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (buffer == null || offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException("buffer");
        }
        if (body == null) return upstream.read(buffer, offset, length);
        if (position >= endPosition) return C.RESULT_END_OF_INPUT;
        int count = (int) Math.min((long) length, endPosition - position);
        System.arraycopy(body, (int) position, buffer, offset, count);
        position += count;
        return count;
    }

    @Override
    public Uri getUri() {
        return body == null ? upstream.getUri() : openedUri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        if (body == null) return upstream.getResponseHeaders();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : handoff.getHeaders().entrySet()) {
            result.put(entry.getKey(), Collections.singletonList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void close() throws IOException {
        try {
            if (upstreamOpened) upstream.close();
        } finally {
            resetState();
        }
    }

    private void resetState() {
        handoff = null;
        body = null;
        position = 0L;
        endPosition = 0L;
        openedUri = null;
        upstreamOpened = false;
    }

    /** Factory required by DefaultMediaSourceFactory/OkHttpDataSource composition. */
    @UnstableApi
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;
        private final ManifestHandoffCache cache;

        public Factory(DataSource.Factory upstream, ManifestHandoffCache cache) {
            if (upstream == null) throw new NullPointerException("upstream");
            if (cache == null) throw new NullPointerException("cache");
            this.upstreamFactory = upstream;
            this.cache = cache;
        }

        @Override
        public DataSource createDataSource() {
            return new ManifestHandoffDataSource(upstreamFactory.createDataSource(), cache);
        }
    }

    private static final class URIHolder {
        final java.net.URI javaUri;
        final boolean playlist;

        private URIHolder(java.net.URI javaUri) {
            this.javaUri = javaUri;
            this.playlist = ManifestHandoffCache.isPlaylist(javaUri);
        }

        static URIHolder from(Uri uri) throws IOException {
            try {
                return new URIHolder(java.net.URI.create(uri.toString()));
            } catch (IllegalArgumentException error) {
                throw new IOException("URI de playlist inválida.", error);
            }
        }
    }
}

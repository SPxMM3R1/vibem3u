package cl.streambox.tv;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import java.net.URI;

/** Surface rejected temporary sources promptly; keep normal CDN/segment recovery in Media3. */
@UnstableApi
final class PlaybackLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {
    private final boolean renewable;
    PlaybackLoadErrorPolicy(boolean renewable) { this.renewable = renewable; }

    @Override public long getRetryDelayMsFor(LoadErrorInfo info) {
        if (requiresFreshSource(info)) return C.TIME_UNSET;
        return super.getRetryDelayMsFor(info);
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

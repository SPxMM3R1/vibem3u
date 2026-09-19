package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * Builds the OkHttp client used by Media3 for a resolved source.
 *
 * <p>Media3 follows HLS redirects and requests child playlists, segments and
 * keys through this client. The network interceptor therefore checks every
 * network hop against the trusted MediaFlow origin carried by the source.
 */
public final class MediaFlowPlaybackClient {
    private MediaFlowPlaybackClient() {}

    public static OkHttpClient forSource(ResolvedPlaybackSource source) throws IOException {
        if (source == null || !source.isMediaFlow()) return SharedHttpClient.get();
        URI origin = source.getMediaFlowOriginUri();
        if (origin == null) throw new IOException("Falta el origen MediaFlow.");
        MediaFlowRequestPolicy.requirePlaybackUri(source.getPlaybackUri(), origin);
        Interceptor guard = chain -> {
            URI request;
            try {
                request = chain.request().url().uri();
            } catch (RuntimeException error) {
                throw new IOException("URL MediaFlow inválida.", error);
            }
            MediaFlowRequestPolicy.requireConfiguredOrigin(request, origin);
            Response response = chain.proceed(chain.request());
            URI finalUri;
            try {
                finalUri = response.request().url().uri();
            } catch (RuntimeException error) {
                response.close();
                throw new IOException("Redirección MediaFlow inválida.", error);
            }
            try {
                MediaFlowRequestPolicy.requireConfiguredOrigin(finalUri, origin);
            } catch (IOException error) {
                response.close();
                throw error;
            }
            if (response.code() >= 300 && response.code() < 400) {
                String location = response.header("Location");
                try {
                    MediaFlowRequestPolicy.resolveRedirect(finalUri, location, origin);
                } catch (IOException error) {
                    response.close();
                    throw error;
                }
            }
            return response;
        };
        return SharedHttpClient.get().newBuilder()
                .addInterceptor(chain -> {
                    URI request;
                    try {
                        request = chain.request().url().uri();
                    } catch (RuntimeException error) {
                        throw new IOException("URL MediaFlow inválida.", error);
                    }
                    MediaFlowRequestPolicy.requireConfiguredOrigin(request, origin);
                    return chain.proceed(chain.request());
                })
                .addNetworkInterceptor(guard)
                .build();
    }
}

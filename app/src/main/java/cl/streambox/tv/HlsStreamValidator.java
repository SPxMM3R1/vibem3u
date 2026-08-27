package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates a master/media playlist and samples a recent media segment. */
public final class HlsStreamValidator {
    private static final int MAX_PLAYLIST_BYTES = 1024 * 1024;
    private static final int MAX_SEGMENT_PROBE_BYTES = 8 * 1024;
    private static final int MAX_PLAYLIST_DEPTH = 3;

    private final TokenHttpClient httpClient;

    public HlsStreamValidator() {
        this(new TokenHttpClient());
    }

    HlsStreamValidator(TokenHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void validate(URI playbackUri, Map<String, String> headers) throws IOException {
        validate(playbackUri, headers, ResolutionProgressListener.NONE);
    }

    /**
     * Performs the cheap validation used immediately before Media3 playback.
     *
     * <p>The resolver has already paid the provider/API cost by the time this
     * method is called. Downloading a variant and a media segment here would
     * make Media3 download the same HLS chain a second time. The playlist is
     * still checked for a real HLS response and for at least one playable
     * reference; Media3 remains responsible for opening the selected variant
     * and segment.</p>
     */
    public void validateForPlayback(
            URI playbackUri,
            Map<String, String> headers,
            ResolutionProgressListener listener
    ) throws IOException {
        if (playbackUri == null) throw new IOException("Fuente HLS inválida.");
        Map<String, String> safeHeaders = headers == null
                ? Collections.emptyMap()
                : headers;
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "GET " + SafePlaybackText.url(playbackUri) + " · esperando #EXTM3U"
        ));
        PlaylistResponse response = loadPlaylist(playbackUri, safeHeaders);
        if (firstVariant(response.lines) == null
                && mediaReferences(response.lines).isEmpty()) {
            throw new IOException("El HLS no publicó una fuente reproducible.");
        }
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "HTTP " + response.statusCode + " · #EXTM3U válido · referencias "
                        + mediaReferenceCount(response.lines)
                        + " · Media3 continúa con variante/segmento"
        ));
    }

    public void validate(
            URI playbackUri,
            Map<String, String> headers,
            ResolutionProgressListener listener
    ) throws IOException {
        if (playbackUri == null) throw new IOException("Fuente HLS inválida.");
        Map<String, String> safeHeaders = headers == null
                ? Collections.emptyMap()
                : headers;
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "GET " + SafePlaybackText.url(playbackUri) + " · esperando #EXTM3U"
        ));
        PlaylistResponse current = loadPlaylist(playbackUri, safeHeaders);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "HTTP " + current.statusCode + " · #EXTM3U válido · referencias "
                        + mediaReferenceCount(current.lines)
        ));

        for (int depth = 0; depth < MAX_PLAYLIST_DEPTH; depth++) {
            String variant = firstVariant(current.lines);
            if (variant == null) break;
            URI variantUri = resolve(current.finalUri, variant);
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.HLS_VARIANT,
                    "GET " + SafePlaybackText.url(variantUri) + " · playlist secundaria"
            ));
            current = loadPlaylist(variantUri, safeHeaders);
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.HLS_VARIANT,
                    "HTTP " + current.statusCode + " · variante #EXTM3U válida"
            ));
        }

        List<String> segments = mediaReferences(current.lines);
        if (segments.isEmpty()) throw new IOException("El HLS no publicó segmentos.");

        IOException lastError = null;
        for (int index : recentProbeOrder(segments.size())) {
            try {
                URI segmentUri = resolve(current.finalUri, segments.get(index));
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.HLS_SEGMENT,
                        "GET " + SafePlaybackText.url(segmentUri)
                                + " · Range bytes=0-4095"
                ));
                TokenHttpClient.Response response = httpClient.getPrefix(
                        segmentUri.toString(),
                        safeHeaders,
                        MAX_SEGMENT_PROBE_BYTES,
                        "bytes=0-4095"
                );
                if (response.getBody().length == 0
                        || looksLikeErrorDocument(response.getBody())) {
                    throw new IOException("El segmento HLS no es reproducible.");
                }
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.HLS_SEGMENT,
                        "HTTP " + response.getStatusCode() + " · segmento reproducible"
                ));
                return;
            } catch (IOException error) {
                lastError = error;
            }
        }
        throw new IOException("El HLS no publicó un segmento reciente reproducible.", lastError);
    }

    private PlaylistResponse loadPlaylist(URI uri, Map<String, String> headers)
            throws IOException {
        TokenHttpClient.Response response = httpClient.get(
                uri.toString(),
                headers,
                MAX_PLAYLIST_BYTES,
                null
        );
        byte[] playlistBody = MeganoticiasHlsDecoder.decodeIfNeeded(response.getBody());
        String content = new String(playlistBody, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        if (!content.startsWith("#EXTM3U") || looksLikeErrorDocument(playlistBody)) {
            throw new IOException("La respuesta no es una playlist HLS.");
        }
        return new PlaylistResponse(
                response.getFinalUri(),
                response.getStatusCode(),
                Arrays.asList(content.split("\\r?\\n"))
        );
    }

    private static String firstVariant(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).trim().toUpperCase(Locale.ROOT)
                    .startsWith("#EXT-X-STREAM-INF:")) continue;
            for (int candidate = index + 1; candidate < lines.size(); candidate++) {
                String value = lines.get(candidate).trim();
                if (!value.isBlank() && !value.startsWith("#")) return value;
            }
        }
        return null;
    }

    private static List<String> mediaReferences(List<String> lines) {
        java.util.ArrayList<String> references = new java.util.ArrayList<>();
        for (String line : lines) {
            String value = line.trim();
            if (!value.isBlank() && !value.startsWith("#")) references.add(value);
        }
        return references;
    }

    private static int mediaReferenceCount(List<String> lines) {
        return mediaReferences(lines).size();
    }

    static int[] recentProbeOrder(int segmentCount) {
        if (segmentCount <= 0) return new int[0];
        if (segmentCount == 1) return new int[]{0};
        if (segmentCount == 2) return new int[]{0, 1};
        return new int[]{segmentCount - 2, segmentCount - 1, segmentCount - 3};
    }

    private static URI resolve(URI base, String value) throws IOException {
        try {
            return base.resolve(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("El HLS publicó una referencia inválida.", error);
        }
    }

    private static boolean looksLikeErrorDocument(byte[] body) {
        if (body == null || body.length == 0) return true;
        int length = Math.min(body.length, 256);
        String prefix = new String(body, 0, length, StandardCharsets.UTF_8)
                .trim()
                .toLowerCase(Locale.ROOT);
        return prefix.startsWith("<html")
                || prefix.startsWith("<!doctype html")
                || prefix.startsWith("{")
                || prefix.startsWith("[");
    }

    private static final class PlaylistResponse {
        final URI finalUri;
        final int statusCode;
        final List<String> lines;

        PlaylistResponse(URI finalUri, int statusCode, List<String> lines) {
            this.finalUri = finalUri;
            this.statusCode = statusCode;
            this.lines = lines;
        }
    }
}

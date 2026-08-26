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
        progress.onProgress(ResolutionProgress.of(ResolutionStage.HLS_PLAYLIST));
        PlaylistResponse current = loadPlaylist(playbackUri, safeHeaders);

        for (int depth = 0; depth < MAX_PLAYLIST_DEPTH; depth++) {
            String variant = firstVariant(current.lines);
            if (variant == null) break;
            progress.onProgress(ResolutionProgress.of(ResolutionStage.HLS_VARIANT));
            current = loadPlaylist(resolve(current.finalUri, variant), safeHeaders);
        }

        List<String> segments = mediaReferences(current.lines);
        if (segments.isEmpty()) throw new IOException("El HLS no publicó segmentos.");

        progress.onProgress(ResolutionProgress.of(ResolutionStage.HLS_SEGMENT));
        IOException lastError = null;
        for (int index : recentProbeOrder(segments.size())) {
            try {
                TokenHttpClient.Response response = httpClient.getPrefix(
                        resolve(current.finalUri, segments.get(index)).toString(),
                        safeHeaders,
                        MAX_SEGMENT_PROBE_BYTES,
                        "bytes=0-4095"
                );
                if (response.getBody().length == 0
                        || looksLikeErrorDocument(response.getBody())) {
                    throw new IOException("El segmento HLS no es reproducible.");
                }
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
        String content = new String(response.getBody(), StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        if (!content.startsWith("#EXTM3U") || looksLikeErrorDocument(response.getBody())) {
            throw new IOException("La respuesta no es una playlist HLS.");
        }
        return new PlaylistResponse(
                response.getFinalUri(),
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
        final List<String> lines;

        PlaylistResponse(URI finalUri, List<String> lines) {
            this.finalUri = finalUri;
            this.lines = lines;
        }
    }
}

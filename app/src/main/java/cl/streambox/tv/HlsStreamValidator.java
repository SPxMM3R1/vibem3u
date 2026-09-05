package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates a master/media playlist and samples a recent media segment. */
public final class HlsStreamValidator {
    private static final int MAX_PLAYLIST_BYTES = 1024 * 1024;
    private static final int MAX_SEGMENT_PROBE_BYTES = 8 * 1024;
    private static final int MAX_PLAYLIST_DEPTH = 3;

    private final TokenHttpClient httpClient;

    public HlsStreamValidator() {
        this(new TokenHttpClient());
    }

    /** Public for provider tests that inject a local/fake TokenHttpClient. */
    public HlsStreamValidator(TokenHttpClient httpClient) {
        if (httpClient == null) throw new NullPointerException("httpClient");
        this.httpClient = httpClient;
    }

    public void validate(URI playbackUri, Map<String, String> headers) throws IOException {
        validate(playbackUri, headers, ResolutionProgressListener.NONE);
    }

    /**
     * Performs the cheap validation used immediately before Media3 playback.
     * The accepted root playlist is handed to the current context so Media3
     * can consume the exact bytes without downloading it a second time.
     */
    public void validateForPlayback(
            URI playbackUri,
            Map<String, String> headers,
            ResolutionProgressListener listener
    ) throws IOException {
        validateForPlayback(
                playbackUri,
                headers,
                ResolutionContext.current(),
                listener
        );
    }

    /** Explicit-context overload for worker code that does not use ThreadLocal. */
    public void validateForPlayback(
            URI playbackUri,
            Map<String, String> headers,
            ResolutionContext context,
            ResolutionProgressListener listener
    ) throws IOException {
        if (playbackUri == null) throw new IOException("Fuente HLS inválida.");
        Map<String, String> safeHeaders = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(headers));
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        check(context);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "GET " + SafePlaybackText.url(playbackUri) + " · esperando #EXTM3U"
        ));
        PlaylistResponse response = loadPlaylist(playbackUri, safeHeaders, context);
        if (variantUris(response).isEmpty() && mediaReferences(response.lines).isEmpty()) {
            throw new IOException("El HLS no publicó una fuente reproducible.");
        }
        cacheAccepted(Collections.singletonList(response), context);
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
        validate(playbackUri, headers, ResolutionContext.current(), listener);
    }

    /** Explicit-context overload that makes the total budget visible to callers. */
    public void validate(
            URI playbackUri,
            Map<String, String> headers,
            ResolutionContext context,
            ResolutionProgressListener listener
    ) throws IOException {
        if (playbackUri == null) throw new IOException("Fuente HLS inválida.");
        Map<String, String> safeHeaders = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(headers));
        ResolutionProgressListener progress = listener == null
                ? ResolutionProgressListener.NONE
                : listener;
        check(context);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "GET " + SafePlaybackText.url(playbackUri) + " · esperando #EXTM3U"
        ));
        PlaylistResponse root = loadPlaylist(playbackUri, safeHeaders, context);
        progress.onProgress(ResolutionProgress.of(
                ResolutionStage.HLS_PLAYLIST,
                "HTTP " + root.statusCode + " · #EXTM3U válido · referencias "
                        + mediaReferenceCount(root.lines)
        ));

        List<PlaylistResponse> accepted = validateChain(
                root,
                safeHeaders,
                progress,
                context,
                0,
                new LinkedHashSet<>()
        );
        cacheAccepted(accepted, context);
    }

    /**
     * Validates and returns the context handoff cache used for this attempt.
     * This convenience method is useful to coordinators that retain the cache
     * after the worker's context scope is closed.
     */
    public ManifestHandoffCache validateAndCapture(
            URI playbackUri,
            Map<String, String> headers,
            ResolutionContext context,
            boolean strict,
            ResolutionProgressListener listener
    ) throws IOException {
        if (strict) validate(playbackUri, headers, context, listener);
        else validateForPlayback(playbackUri, headers, context, listener);
        return context == null ? null : context.manifests();
    }

    private List<PlaylistResponse> validateChain(
            PlaylistResponse current,
            Map<String, String> headers,
            ResolutionProgressListener progress,
            ResolutionContext context,
            int depth,
            Set<URI> visited
    ) throws IOException {
        check(context);
        if (depth >= MAX_PLAYLIST_DEPTH) {
            if (!variantUris(current).isEmpty()) {
                throw new IOException("El HLS publicó demasiadas variantes anidadas.");
            }
            sampleSegments(current, headers, progress, context);
            return Collections.singletonList(current);
        }

        List<URI> variants = variantUris(current);
        if (variants.isEmpty()) {
            sampleSegments(current, headers, progress, context);
            return Collections.singletonList(current);
        }

        IOException lastError = null;
        for (URI variantUri : variants) {
            check(context);
            if (!visited.add(variantUri)) {
                lastError = new IOException("El HLS publicó una variante circular.");
                continue;
            }
            progress.onProgress(ResolutionProgress.of(
                    ResolutionStage.HLS_VARIANT,
                    "GET " + SafePlaybackText.url(variantUri) + " · playlist secundaria"
            ));
            try {
                PlaylistResponse variant = loadPlaylist(variantUri, headers, context);
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.HLS_VARIANT,
                        "HTTP " + variant.statusCode + " · variante #EXTM3U válida"
                ));
                List<PlaylistResponse> accepted = validateChain(
                        variant,
                        headers,
                        progress,
                        context,
                        depth + 1,
                        visited
                );
                List<PlaylistResponse> chain = new ArrayList<>();
                chain.add(current);
                chain.addAll(accepted);
                return chain;
            } catch (IOException error) {
                lastError = error;
            } finally {
                visited.remove(variantUri);
            }
        }
        throw new IOException("El HLS no publicó una variante reproducible.", lastError);
    }

    private void sampleSegments(
            PlaylistResponse current,
            Map<String, String> headers,
            ResolutionProgressListener progress,
            ResolutionContext context
    ) throws IOException {
        List<String> segments = mediaReferences(current.lines);
        if (segments.isEmpty()) throw new IOException("El HLS no publicó segmentos.");
        boolean encryptedSegments = hasEncryptedSegments(current.lines);

        IOException lastError = null;
        for (int index : recentProbeOrder(segments.size())) {
            try {
                check(context);
                URI segmentUri = resolve(current.finalUri, segments.get(index));
                PublicStreamPolicy.requirePublicHttp(segmentUri);
                progress.onProgress(ResolutionProgress.of(
                        ResolutionStage.HLS_SEGMENT,
                        "GET " + SafePlaybackText.url(segmentUri)
                                + " · Range bytes=0-4095"
                ));
                TokenHttpClient.Response response = httpClient.getPublicPrefix(
                        segmentUri.toString(),
                        headers,
                        MAX_SEGMENT_PROBE_BYTES,
                        "bytes=0-4095"
                );
                check(context);
                PublicStreamPolicy.requirePublicHttp(response.getFinalUri());
                if (response.getBody().length == 0
                        || looksLikeErrorDocument(response.getBody())
                        || (!encryptedSegments && !isRecognizedMediaSample(
                                response.getBody(), response.getContentType()
                        ))) {
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

    private PlaylistResponse loadPlaylist(
            URI uri,
            Map<String, String> headers,
            ResolutionContext context
    ) throws IOException {
        check(context);
        PublicStreamPolicy.requirePublicHttp(uri);
        TokenHttpClient.Response response = httpClient.getPublic(
                uri.toString(),
                headers,
                MAX_PLAYLIST_BYTES,
                null
        );
        check(context);
        PublicStreamPolicy.requirePublicHttp(response.getFinalUri());
        // The Mega adapter's numeric representation is decoded before this
        // handoff is captured, so Media3 receives ordinary #EXTM3U bytes.
        byte[] playlistBody = MeganoticiasHlsDecoder.decodeIfNeeded(response.getBody());
        String content = new String(playlistBody, StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        if (!content.startsWith("#EXTM3U") || looksLikeErrorDocument(playlistBody)) {
            throw new IOException("La respuesta no es una playlist HLS.");
        }
        return new PlaylistResponse(
                uri,
                response.getFinalUri(),
                response.getStatusCode(),
                headers,
                playlistBody,
                Arrays.asList(content.split("\\r?\\n"))
        );
    }

    private static void cacheAccepted(
            List<PlaylistResponse> responses,
            ResolutionContext context
    ) {
        if (context == null || responses == null) return;
        ManifestHandoffCache cache = context.manifests();
        for (PlaylistResponse response : responses) {
            if (response != null) {
                cache.put(response.originalUri, response.finalUri, response.requestHeaders,
                        response.rawBody);
            }
        }
    }

    private static List<URI> variantUris(PlaylistResponse response) throws IOException {
        List<URI> result = new ArrayList<>();
        if (response == null) return result;
        for (String value : variantReferences(response.lines)) {
            try {
                URI resolved = response.finalUri.resolve(value);
                PublicStreamPolicy.requirePublicHttp(resolved);
                if (!result.contains(resolved)) result.add(resolved);
            } catch (IllegalArgumentException | IOException error) {
                // A malformed alternative is treated as a failed alternative;
                // another variant can still be playable.
            }
        }
        return result;
    }

    private static List<String> variantReferences(List<String> lines) {
        List<String> result = new ArrayList<>();
        if (lines == null) return result;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (!line.toUpperCase(Locale.ROOT).startsWith("#EXT-X-STREAM-INF:")) continue;
            for (int candidate = index + 1; candidate < lines.size(); candidate++) {
                String value = lines.get(candidate).trim();
                if (!value.isBlank() && !value.startsWith("#")) {
                    result.add(value);
                    break;
                }
            }
        }
        return result;
    }

    private static String firstVariant(List<String> lines) {
        List<String> variants = variantReferences(lines);
        return variants.isEmpty() ? null : variants.get(0);
    }

    private static List<String> mediaReferences(List<String> lines) {
        java.util.ArrayList<String> references = new java.util.ArrayList<>();
        for (String line : lines) {
            String value = line.trim();
            if (!value.isBlank() && !value.startsWith("#")) references.add(value);
        }
        // Master variant URIs are references too; callers that need segments
        // invoke this only after variantUris is empty.
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

    /** Recognises common HLS media containers without decoding the programme. */
    static boolean isRecognizedMediaSample(byte[] body, String contentType) {
        if (body == null || body.length == 0 || looksLikeErrorDocument(body)) return false;
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
            return false;
        }

        // MPEG-TS: require two packets with the same 188-byte sync offset.
        for (int offset = 0; offset < Math.min(188, body.length); offset++) {
            if (body[offset] == 0x47 && offset + 188 < body.length
                    && body[offset + 188] == 0x47) {
                return true;
            }
        }

        // ISO BMFF/fMP4 boxes used by CMAF HLS.
        if (body.length >= 8) {
            String box = new String(body, 4, 4, StandardCharsets.US_ASCII);
            if ("ftyp".equals(box) || "styp".equals(box)
                    || "moof".equals(box) || "sidx".equals(box)) {
                return true;
            }
        }

        // ADTS AAC, MP3 frame sync and ID3-prefixed audio segments.
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF
                && (((body[1] & 0xF6) == 0xF0) || ((body[1] & 0xE0) == 0xE0))) {
            return true;
        }
        return body.length >= 10 && body[0] == 'I' && body[1] == 'D' && body[2] == '3';
    }

    private static boolean hasEncryptedSegments(List<String> lines) {
        for (String line : lines) {
            String value = line.trim().toUpperCase(Locale.ROOT);
            if (value.startsWith("#EXT-X-KEY:")
                    && !value.contains("METHOD=NONE")) return true;
        }
        return false;
    }

    private static URI resolve(URI base, String value) throws IOException {
        try {
            return base.resolve(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("El HLS publicó una referencia inválida.", error);
        }
    }

    private static void check(ResolutionContext context) throws IOException {
        if (context != null) context.check();
        else if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Solicitud cancelada.");
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
        final URI originalUri;
        final URI finalUri;
        final int statusCode;
        final Map<String, String> requestHeaders;
        final byte[] rawBody;
        final List<String> lines;

        PlaylistResponse(
                URI originalUri,
                URI finalUri,
                int statusCode,
                Map<String, String> requestHeaders,
                byte[] rawBody,
                List<String> lines
        ) {
            this.originalUri = originalUri;
            this.finalUri = finalUri;
            this.statusCode = statusCode;
            this.requestHeaders = requestHeaders == null
                    ? Collections.emptyMap()
                    : requestHeaders;
            this.rawBody = rawBody == null ? new byte[0] : rawBody;
            this.lines = lines;
        }
    }
}

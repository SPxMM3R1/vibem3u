package cl.streambox.tv;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Stable numeric playback diagnostics. The code is intentionally URL-free so it
 * can be shown to a user without exposing tokens or signed stream addresses.
 *
 * Format: PPSSCCC
 * PP = subsystem, SS = stage, CCC = cause or HTTP status.
 */
final class PlaybackDiagnosticCode {
    private static final int SUBSYSTEM_RESOLVER = 20;
    private static final int SUBSYSTEM_HLS = 30;
    private static final int SUBSYSTEM_MEDIA3 = 40;
    private static final int SUBSYSTEM_RECOVERY = 50;
    private static final int SUBSYSTEM_RESOURCES = 60;

    private PlaybackDiagnosticCode() {
    }

    static long forPlaybackError(Throwable error, boolean manifestRequest) {
        return forPlaybackError(error, -1, manifestRequest);
    }

    static long forPlaybackError(
            Throwable error,
            int media3ErrorCode,
            boolean manifestRequest
    ) {
        int responseCode = httpResponseCode(error);
        if (responseCode >= 100 && responseCode <= 999) {
            return encode(SUBSYSTEM_HLS, manifestRequest ? 4 : 5, responseCode);
        }
        long media3Code = forMedia3Error(media3ErrorCode, manifestRequest);
        if (media3Code > 0) return media3Code;
        if (containsType(error, "DecoderInitializationException")) {
            return encode(SUBSYSTEM_MEDIA3, 6, 1);
        }
        if (containsType(error, "ParserException")
                || containsType(error, "MalformedManifestException")) {
            return encode(SUBSYSTEM_MEDIA3, 7, 1);
        }
        if (isTimeout(error)) {
            return encode(SUBSYSTEM_HLS, manifestRequest ? 4 : 5, 408);
        }
        if (containsType(error, "MediaCodec") || containsType(error, "Decoder")) {
            return encode(SUBSYSTEM_MEDIA3, 6, 2);
        }
        if (containsType(error, "EOFException")) {
            return encode(SUBSYSTEM_HLS, manifestRequest ? 4 : 5, 499);
        }
        if (media3ErrorCode > 0) {
            return encodeUnknownMedia3Error(
                    manifestRequest ? 4 : 5,
                    media3ErrorCode,
                    error
            );
        }
        return encodeUnknownPlaybackFailure(
                manifestRequest ? 4 : 5,
                error
        );
    }

    private static long forMedia3Error(int errorCode, boolean manifestRequest) {
        int hlsStage = manifestRequest ? 4 : 5;
        if (errorCode == PlaybackException.ERROR_CODE_TIMEOUT
                || errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return encode(SUBSYSTEM_HLS, hlsStage, 408);
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
            return encode(SUBSYSTEM_HLS, hlsStage, 2);
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE) {
            return encode(SUBSYSTEM_HLS, hlsStage, 415);
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return encode(SUBSYSTEM_HLS, hlsStage, 400);
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return encode(SUBSYSTEM_HLS, hlsStage, 404);
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) {
            return encode(SUBSYSTEM_HLS, hlsStage, 470);
        }
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) {
            return encode(SUBSYSTEM_MEDIA3, 7, 1);
        }
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return encode(SUBSYSTEM_MEDIA3, 7, 2);
        }
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
            return encode(SUBSYSTEM_MEDIA3, 7, 3);
        }
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            return encode(SUBSYSTEM_MEDIA3, 7, 4);
        }
        if (errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED) {
            return encode(SUBSYSTEM_MEDIA3, 6, 1);
        }
        if (errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) {
            return encode(SUBSYSTEM_MEDIA3, 6, 2);
        }
        if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED) {
            return encode(SUBSYSTEM_MEDIA3, 8, 1);
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            return encode(SUBSYSTEM_HLS, hlsStage, 416);
        }
        return 0;
    }

    static long forResolverFailure(Throwable error) {
        int responseCode = httpResponseCode(error);
        if (responseCode >= 100 && responseCode <= 999) {
            return encode(SUBSYSTEM_RESOLVER, 2, responseCode);
        }
        if (isTimeout(error)) {
            return encode(SUBSYSTEM_RESOLVER, 2, 408);
        }
        if (containsType(error, "Json")
                || containsType(error, "Parse")
                || containsType(error, "Malformed")) {
            return encode(SUBSYSTEM_RESOLVER, 3, 1);
        }
        if (error instanceof UnknownHostException || containsType(error, "ConnectException")) {
            return encode(SUBSYSTEM_RESOLVER, 2, 0);
        }
        return encodeUnknownResolverFailure(error);
    }

    static long forWatchdog(String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("audio y vídeo") || normalized.contains("audio y video")) {
            return encode(SUBSYSTEM_RECOVERY, 9, 3);
        }
        if (normalized.contains("decoder")) {
            return encode(SUBSYSTEM_RECOVERY, 9, 5);
        }
        if (normalized.contains("audio detenido")) {
            return encode(SUBSYSTEM_RECOVERY, 9, 4);
        }
        if (normalized.contains("playlist") || normalized.contains("segmento")) {
            return encode(SUBSYSTEM_HLS, 9, 1);
        }
        if (normalized.contains("carga")) {
            return encode(SUBSYSTEM_RECOVERY, 9, 2);
        }
        if (normalized.contains("audio")) {
            return encode(SUBSYSTEM_RECOVERY, 9, 4);
        }
        return encode(SUBSYSTEM_RECOVERY, 9, 1);
    }

    static long recoveryExhausted() {
        return encode(SUBSYSTEM_RECOVERY, 10, 1);
    }

    static long resourceFailure() {
        return encode(SUBSYSTEM_RESOURCES, 8, 1);
    }

    static String display(long code) {
        return code <= 0 ? "" : String.valueOf(code);
    }

    private static long encode(int subsystem, int stage, int cause) {
        return subsystem * 100_000L + stage * 1_000L + Math.max(0, Math.min(cause, 999));
    }

    /**
     * Extended fallback: PP SS EEEE RR. It preserves the raw Media3 error
     * code and a stable root-cause family when the error is not in the known
     * mapping above. It intentionally uses long because the value can exceed
     * the signed 32-bit integer range.
     */
    private static long encodeUnknownMedia3Error(
            int stage,
            int media3ErrorCode,
            Throwable error
    ) {
        long rawCode = Math.max(0L, Math.min(media3ErrorCode, 9_999L));
        return 40L * 100_000_000L
                + stage * 1_000_000L
                + rawCode * 100L
                + rootCauseFamily(error);
    }

    private static long encodeUnknownPlaybackFailure(int stage, Throwable error) {
        return 40L * 100_000_000L
                + stage * 1_000_000L
                + rootCauseFamily(error);
    }

    private static long encodeUnknownResolverFailure(Throwable error) {
        return 20L * 100_000_000L
                + 9L * 1_000_000L
                + rootCauseFamily(error);
    }

    private static int rootCauseFamily(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++, current = current.getCause()) {
            String type = current.getClass().getName();
            if (type.contains("SocketTimeout") || type.contains("Timeout")) return 3;
            if (type.contains("EOFException")) return 2;
            if (type.contains("ParserException")) return 4;
            if (type.contains("HttpDataSource")) return 5;
            if (type.contains("Decoder") || type.contains("MediaCodec")) return 6;
            if (type.contains("AudioTrack")) return 7;
            if (type.contains("IllegalStateException")) return 8;
            if (type.contains("PlaybackException")) return 9;
            if (type.contains("IOException")) return 1;
        }
        return 99;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++, current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            String type = current.getClass().getSimpleName();
            if (type.contains("Timeout") || type.contains("ConnectException")) return true;
        }
        return false;
    }

    private static boolean containsType(Throwable error, String fragment) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++, current = current.getCause()) {
            if (current.getClass().getName().contains(fragment)) return true;
        }
        return false;
    }

    private static int httpResponseCode(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++, current = current.getCause()) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) current).responseCode;
            }
        }
        return -1;
    }
}

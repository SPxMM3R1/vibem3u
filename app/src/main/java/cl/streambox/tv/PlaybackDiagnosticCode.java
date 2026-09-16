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

    static int forPlaybackError(Throwable error, boolean manifestRequest) {
        return forPlaybackError(error, PlaybackException.ERROR_CODE_UNSPECIFIED, manifestRequest);
    }

    static int forPlaybackError(
            Throwable error,
            int media3ErrorCode,
            boolean manifestRequest
    ) {
        int responseCode = httpResponseCode(error);
        if (responseCode >= 100 && responseCode <= 999) {
            return encode(SUBSYSTEM_HLS, manifestRequest ? 4 : 5, responseCode);
        }
        int media3Code = forMedia3Error(media3ErrorCode, manifestRequest);
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
        return encode(SUBSYSTEM_MEDIA3, manifestRequest ? 4 : 5, 1);
    }

    private static int forMedia3Error(int errorCode, boolean manifestRequest) {
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

    static int forResolverFailure(Throwable error) {
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
        return encode(SUBSYSTEM_RESOLVER, 9, 1);
    }

    static int forWatchdog(String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("carga")) {
            return encode(SUBSYSTEM_RECOVERY, 9, 2);
        }
        if (normalized.contains("audio")) {
            return encode(SUBSYSTEM_RECOVERY, 9, 4);
        }
        return encode(SUBSYSTEM_RECOVERY, 9, 1);
    }

    static int recoveryExhausted() {
        return encode(SUBSYSTEM_RECOVERY, 10, 1);
    }

    static int resourceFailure() {
        return encode(SUBSYSTEM_RESOURCES, 8, 1);
    }

    static String display(int code) {
        return code <= 0 ? "" : String.valueOf(code);
    }

    private static int encode(int subsystem, int stage, int cause) {
        return subsystem * 100_000 + stage * 1_000 + Math.max(0, Math.min(cause, 999));
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

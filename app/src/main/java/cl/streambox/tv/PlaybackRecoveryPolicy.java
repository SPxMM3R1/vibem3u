package cl.streambox.tv;

import androidx.media3.common.PlaybackException;

/** Limits automatic playback recovery to transient network and HTTP failures. */
public final class PlaybackRecoveryPolicy {
    public static final int MAX_AUTOMATIC_RETRIES = 3;

    private int automaticRetries;

    public void reset() {
        automaticRetries = 0;
    }

    public boolean tryConsumeRetry(int errorCode) {
        if (!isRecoverable(errorCode) || automaticRetries >= MAX_AUTOMATIC_RETRIES) {
            return false;
        }
        automaticRetries++;
        return true;
    }

    public static boolean isRecoverable(int errorCode) {
        return switch (errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_TIMEOUT -> true;
            default -> false;
        };
    }
}

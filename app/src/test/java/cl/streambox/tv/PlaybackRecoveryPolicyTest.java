package cl.streambox.tv;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackRecoveryPolicyTest {
    @Test
    public void retriesOnlyTransientNetworkAndHttpErrors() {
        assertTrue(PlaybackRecoveryPolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ));
        assertTrue(PlaybackRecoveryPolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ));
        assertTrue(PlaybackRecoveryPolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        ));
        assertTrue(PlaybackRecoveryPolicy.isRecoverable(
                PlaybackException.ERROR_CODE_TIMEOUT
        ));
        assertFalse(PlaybackRecoveryPolicy.isRecoverable(
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
        ));
        assertFalse(PlaybackRecoveryPolicy.isRecoverable(
                PlaybackException.ERROR_CODE_NOT_SUPPORTED
        ));
    }

    @Test
    public void automaticRetriesStopAtTheBudget() {
        PlaybackRecoveryPolicy policy = new PlaybackRecoveryPolicy();

        assertTrue(policy.tryConsumeRetry(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ));
        assertTrue(policy.tryConsumeRetry(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ));
        assertTrue(policy.tryConsumeRetry(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        ));
        assertFalse(policy.tryConsumeRetry(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ));
    }

    @Test
    public void resetAllowsAUserInitiatedRecoveryAgain() {
        PlaybackRecoveryPolicy policy = new PlaybackRecoveryPolicy();
        for (int attempt = 0; attempt < PlaybackRecoveryPolicy.MAX_AUTOMATIC_RETRIES; attempt++) {
            assertTrue(policy.tryConsumeRetry(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            ));
        }

        policy.reset();

        assertTrue(policy.tryConsumeRetry(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ));
    }
}

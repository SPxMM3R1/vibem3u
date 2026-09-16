package cl.streambox.tv;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;

public final class PlaybackDiagnosticCodeTest {
    @Test
    public void keepsResolverTimeoutAtTheResolverStage() {
        assertEquals(2002408,
                PlaybackDiagnosticCode.forResolverFailure(new SocketTimeoutException()));
    }

    @Test
    public void keepsPlaybackTimeoutAtTheHlsStage() {
        assertEquals(3005408,
                PlaybackDiagnosticCode.forPlaybackError(
                        new SocketTimeoutException(),
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        false
                ));
    }

    @Test
    public void classifiesMedia3NetworkAndFormatErrors() {
        assertEquals(3005002, PlaybackDiagnosticCode.forPlaybackError(
                new IllegalStateException(),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                false
        ));
        assertEquals(4007001, PlaybackDiagnosticCode.forPlaybackError(
                new IllegalStateException(),
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                false
        ));
        assertEquals(4006001, PlaybackDiagnosticCode.forPlaybackError(
                new IllegalStateException(),
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                false
        ));
    }

    @Test
    public void separatesWatchdogReasons() {
        assertEquals(5009001, PlaybackDiagnosticCode.forWatchdog("vídeo detenido"));
        assertEquals(5009002, PlaybackDiagnosticCode.forWatchdog("carga prolongada"));
    }

    @Test
    public void genericErrorsRemainNumericAndStable() {
        assertEquals("4005001", PlaybackDiagnosticCode.display(
                PlaybackDiagnosticCode.forPlaybackError(new IOException("x"), false)));
        assertEquals("5010001", PlaybackDiagnosticCode.display(
                PlaybackDiagnosticCode.recoveryExhausted()));
    }
}

package cl.streambox.tv;

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
                PlaybackDiagnosticCode.forPlaybackError(new SocketTimeoutException(), false));
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
        assertEquals("5001001", PlaybackDiagnosticCode.display(
                PlaybackDiagnosticCode.recoveryExhausted()));
    }
}

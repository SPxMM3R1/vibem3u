package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class PlaybackStallPolicyTest {
    @Test
    public void doesNotExpireBeforeTheFiveSecondBoundary() {
        assertFalse(PlaybackStallPolicy.isExpired(1_000L, 5_999L, 5_000L));
        assertTrue(PlaybackStallPolicy.isExpired(1_000L, 6_000L, 5_000L));
    }

    @Test
    public void ignoresUnsetOrBackwardsClocks() {
        assertFalse(PlaybackStallPolicy.isExpired(-1L, 6_000L, 5_000L));
        assertFalse(PlaybackStallPolicy.isExpired(6_000L, 5_999L, 5_000L));
        assertFalse(PlaybackStallPolicy.isExpired(1_000L, 6_000L, 0L));
    }

    @Test
    public void bufferingIsNotMislabelledAsDecoderFailure() {
        assertEquals("carga detenida", PlaybackStallPolicy.classify(true, false, false, 700L));
        assertEquals("carga detenida", PlaybackStallPolicy.classify(true, true, false, 0L));
        assertEquals("audio y vídeo detenidos", PlaybackStallPolicy.classify(false, true, false, 0L));
        assertEquals("audio detenido", PlaybackStallPolicy.classify(false, false, true, 0L));
        assertEquals("decoder detenido", PlaybackStallPolicy.classify(false, false, false, 10_000L));
    }

    @Test
    public void slowButReceivingSegmentsAreNotRestartedUntilTheCap() {
        // Corte desde 10 s; bytes de video hace 1 s: se espera (hay servidores que tardan >5 s por segmento).
        assertTrue(PlaybackStallPolicy.keepWaitingWhileReceiving(10_000L, 17_000L, 16_000L, 4_000L, 30_000L));
        // Sin bytes en los últimos 4 s: se reinicia como antes.
        assertFalse(PlaybackStallPolicy.keepWaitingWhileReceiving(10_000L, 17_000L, 12_000L, 4_000L, 30_000L));
        // Aun recibiendo, a los 30 s de corte se reinicia.
        assertFalse(PlaybackStallPolicy.keepWaitingWhileReceiving(10_000L, 40_000L, 39_500L, 4_000L, 30_000L));
        // Sin datos de transferencia no se cambia el comportamiento.
        assertFalse(PlaybackStallPolicy.keepWaitingWhileReceiving(10_000L, 17_000L, -1L, 4_000L, 30_000L));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EarlyStallPolicyTest {
    private static final String PRINCIPAL = "canal|principal";
    private static final String BACKUP = "canal|directo-0";

    @Test
    public void earlyWindowIsFifteenSecondsFromTheFirstFrame() {
        assertFalse(EarlyStallPolicy.isEarly(-1L, 5_000L));
        assertFalse(EarlyStallPolicy.isEarly(10_000L, 9_999L));
        assertTrue(EarlyStallPolicy.isEarly(10_000L, 10_000L));
        assertTrue(EarlyStallPolicy.isEarly(10_000L, 24_999L));
        assertFalse(EarlyStallPolicy.isEarly(10_000L, 25_000L));
    }

    @Test
    public void firstEarlyStallReconnectsTheSameSourceAndSecondSwitches() {
        EarlyStallPolicy policy = new EarlyStallPolicy();

        assertEquals(EarlyStallPolicy.Action.RECONNECT_SAME,
                policy.onStall(PRINCIPAL, true, true));
        assertEquals(EarlyStallPolicy.Action.SWITCH_SOURCE,
                policy.onStall(PRINCIPAL, true, true));
        assertEquals(1, policy.consecutiveDecoderStops());
    }

    @Test
    public void stallAfterTheWindowAlwaysSwitchesAndResetsTheHistory() {
        EarlyStallPolicy policy = new EarlyStallPolicy();
        policy.onStall(PRINCIPAL, true, true);
        policy.onStall(PRINCIPAL, true, true);
        assertEquals(1, policy.consecutiveDecoderStops());

        // Una señal que anduvo 15 s se trabó: comportamiento de siempre, historial limpio.
        assertEquals(EarlyStallPolicy.Action.SWITCH_SOURCE,
                policy.onStall(PRINCIPAL, false, true));
        assertEquals(0, policy.consecutiveDecoderStops());
        // Y la principal vuelve a tener su reconexión temprana.
        assertEquals(EarlyStallPolicy.Action.RECONNECT_SAME,
                policy.onStall(PRINCIPAL, true, true));
    }

    @Test
    public void twoSourcesInASowEndingInDecoderStallStopTheCascade() {
        EarlyStallPolicy policy = new EarlyStallPolicy();

        // Principal: reconexión, luego se da por perdida y pasa al respaldo.
        assertEquals(EarlyStallPolicy.Action.RECONNECT_SAME, policy.onStall(PRINCIPAL, true, true));
        assertEquals(EarlyStallPolicy.Action.SWITCH_SOURCE, policy.onStall(PRINCIPAL, true, true));
        // Respaldo: reconexión propia; al trabarse otra vez, dos fuentes seguidas: se frena.
        assertEquals(EarlyStallPolicy.Action.RECONNECT_SAME, policy.onStall(BACKUP, true, true));
        assertEquals(EarlyStallPolicy.Action.STOP_CASCADE, policy.onStall(BACKUP, true, true));
    }

    @Test
    public void aNonDecoderStopBreaksTheCascadeCount() {
        EarlyStallPolicy policy = new EarlyStallPolicy();
        policy.onStall(PRINCIPAL, true, true);
        assertEquals(EarlyStallPolicy.Action.SWITCH_SOURCE, policy.onStall(PRINCIPAL, true, true));

        // El respaldo termina por carga detenida: no cuenta como cascada de decodificador.
        assertEquals(EarlyStallPolicy.Action.RECONNECT_SAME, policy.onStall(BACKUP, true, false));
        assertEquals(EarlyStallPolicy.Action.SWITCH_SOURCE, policy.onStall(BACKUP, true, false));
        assertEquals(0, policy.consecutiveDecoderStops());

        // Otra fuente que termina por decodificador empieza la cuenta de nuevo (1, no 2).
        String third = "canal|directo-1";
        assertEquals(EarlyStallPolicy.Action.RECONNECT_SAME, policy.onStall(third, true, true));
        assertEquals(EarlyStallPolicy.Action.SWITCH_SOURCE, policy.onStall(third, true, true));
        assertEquals(1, policy.consecutiveDecoderStops());
    }

    @Test
    public void resetForgetsRetriesAndCounts() {
        EarlyStallPolicy policy = new EarlyStallPolicy();
        policy.onStall(PRINCIPAL, true, true);
        policy.onStall(PRINCIPAL, true, true);

        policy.reset();

        assertEquals(0, policy.consecutiveDecoderStops());
        assertEquals(EarlyStallPolicy.Action.RECONNECT_SAME,
                policy.onStall(PRINCIPAL, true, true));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DecoderProgressTrackerTest {
    @Test
    public void totalCountsShownDroppedAndSkippedBuffers() {
        assertEquals(10L, DecoderProgressTracker.totalOf(4, 3, 2, 1));
        assertEquals(0L, DecoderProgressTracker.totalOf(0, 0, 0, 0));
    }

    @Test
    public void noProgressUntilTheDecoderProducesSomething() {
        DecoderProgressTracker tracker = new DecoderProgressTracker();

        assertEquals(DecoderProgressTracker.NO_PROGRESS, tracker.observe(0L, 1_000L));
    }

    @Test
    public void lastChangeMovesOnlyWhenTheTotalMoves() {
        DecoderProgressTracker tracker = new DecoderProgressTracker();

        assertEquals(1_000L, tracker.observe(5L, 1_000L));
        // Sin cambio: sigue valiendo el último avance.
        assertEquals(1_000L, tracker.observe(5L, 3_000L));
        // Descartar cuadros también cuenta como avance aunque no se muestre ninguno.
        assertEquals(4_000L, tracker.observe(6L, 4_000L));
    }

    @Test
    public void resetStartsOverForANewSource() {
        DecoderProgressTracker tracker = new DecoderProgressTracker();
        tracker.observe(5L, 1_000L);

        tracker.reset();

        assertEquals(DecoderProgressTracker.NO_PROGRESS, tracker.observe(0L, 9_000L));
        // Con el contador de la fuente nueva, el primer valor cuenta como avance en ese instante.
        assertEquals(9_000L, tracker.observe(5L, 9_000L));
    }
}

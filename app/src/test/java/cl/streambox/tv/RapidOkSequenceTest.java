package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RapidOkSequenceTest {
    @Test
    public void triggersOnThirdSeparatePressWithinWindow() {
        RapidOkSequence sequence = new RapidOkSequence();

        assertFalse(sequence.registerPress(1_000L));
        assertFalse(sequence.registerPress(1_600L));
        assertTrue(sequence.registerPress(2_300L));
    }

    @Test
    public void gapOverWindowStartsANewSequence() {
        RapidOkSequence sequence = new RapidOkSequence();

        assertFalse(sequence.registerPress(1_000L));
        assertFalse(sequence.registerPress(1_701L));
        assertFalse(sequence.registerPress(2_000L));
        assertFalse(sequence.registerPress(2_701L));
        assertFalse(sequence.registerPress(2_800L));
        assertTrue(sequence.registerPress(2_900L));
    }

    @Test
    public void clockRollbackStartsANewSequence() {
        RapidOkSequence sequence = new RapidOkSequence();

        assertFalse(sequence.registerPress(1_000L));
        assertFalse(sequence.registerPress(1_100L));
        assertFalse(sequence.registerPress(900L));
        assertFalse(sequence.registerPress(950L));
        assertTrue(sequence.registerPress(1_000L));
    }

    @Test
    public void resetClearsAnIncompleteSequence() {
        RapidOkSequence sequence = new RapidOkSequence();

        assertFalse(sequence.registerPress(1_000L));
        assertFalse(sequence.registerPress(1_100L));
        sequence.reset();
        assertFalse(sequence.registerPress(1_200L));
        assertFalse(sequence.registerPress(1_300L));
        assertTrue(sequence.registerPress(1_400L));
    }

    @Test
    public void completedSequenceDoesNotCarryIntoTheNextOne() {
        RapidOkSequence sequence = new RapidOkSequence();

        assertFalse(sequence.registerPress(1_000L));
        assertFalse(sequence.registerPress(1_100L));
        assertTrue(sequence.registerPress(1_200L));
        assertFalse(sequence.registerPress(1_250L));
        assertFalse(sequence.registerPress(1_300L));
    }
}

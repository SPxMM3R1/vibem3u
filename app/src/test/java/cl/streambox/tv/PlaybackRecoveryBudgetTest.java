package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackRecoveryBudgetTest {
    @Test
    public void allowsThreeAttemptsWithGrowingDelays() {
        PlaybackRecoveryBudget budget = new PlaybackRecoveryBudget();

        assertEquals(1, budget.consume());
        assertEquals(2, budget.consume());
        assertEquals(3, budget.consume());
        assertFalse(budget.hasAttemptLeft());
        assertEquals(2_000L, PlaybackRecoveryBudget.delayMsFor(1));
        assertEquals(5_000L, PlaybackRecoveryBudget.delayMsFor(2));
        assertEquals(10_000L, PlaybackRecoveryBudget.delayMsFor(3));
    }

    @Test
    public void firstAttemptKeepsTheSourceAndLaterOnesRenewIt() {
        assertFalse(PlaybackRecoveryBudget.renewsSource(1));
        assertTrue(PlaybackRecoveryBudget.renewsSource(2));
        assertTrue(PlaybackRecoveryBudget.renewsSource(3));
    }

    @Test
    public void resetAndRestoreKeepTheBudgetBounded() {
        PlaybackRecoveryBudget budget = new PlaybackRecoveryBudget();
        budget.consume();
        budget.consume();
        int used = budget.used();

        budget.reset();
        assertEquals(0, budget.used());
        budget.restore(used);
        assertEquals(2, budget.used());
        budget.restore(99);
        assertFalse(budget.hasAttemptLeft());
    }

    @Test(expected = IllegalStateException.class)
    public void refusesAFourthAttempt() {
        PlaybackRecoveryBudget budget = new PlaybackRecoveryBudget();
        for (int i = 0; i < PlaybackRecoveryBudget.MAX_ATTEMPTS + 1; i++) budget.consume();
    }
}

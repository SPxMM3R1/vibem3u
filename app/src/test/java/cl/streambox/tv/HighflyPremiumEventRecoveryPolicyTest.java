package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HighflyPremiumEventRecoveryPolicyTest {
    @Test
    public void allowsOnlyThreeFreshReconnectionsPerEvent() {
        HighflyPremiumEventRecoveryPolicy policy = new HighflyPremiumEventRecoveryPolicy();

        assertTrue(policy.tryConsume("streamed:event-1"));
        assertTrue(policy.tryConsume("streamed:event-1"));
        assertTrue(policy.tryConsume("streamed:event-1"));
        assertFalse(policy.tryConsume("streamed:event-1"));
        assertEquals(3, policy.attemptsFor("streamed:event-1"));
    }

    @Test
    public void aValidatedSourceStartsANewBudget() {
        HighflyPremiumEventRecoveryPolicy policy = new HighflyPremiumEventRecoveryPolicy();

        assertTrue(policy.tryConsume("streamed:event-1"));
        assertTrue(policy.tryConsume("streamed:event-1"));
        policy.markAvailable("streamed:event-1");

        assertEquals(0, policy.attemptsFor("streamed:event-1"));
        assertTrue(policy.tryConsume("streamed:event-1"));
    }

    @Test
    public void budgetsAreIndependentForDifferentEvents() {
        HighflyPremiumEventRecoveryPolicy policy = new HighflyPremiumEventRecoveryPolicy();

        assertTrue(policy.tryConsume("streamed:event-1"));
        assertTrue(policy.tryConsume("streamed:event-1"));
        assertTrue(policy.tryConsume("streamed:event-1"));
        assertFalse(policy.tryConsume("streamed:event-1"));

        assertEquals(0, policy.attemptsFor("streamed:event-2"));
        assertTrue(policy.tryConsume("streamed:event-2"));
    }
}

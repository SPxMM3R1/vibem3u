package cl.streambox.tv;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class HighflyPremiumCredentialStoreInstrumentedTest {
    @Test
    public void encryptedCredentialSurvivesClearingTheInMemoryCopy() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HighflyPremiumCredentialStore store = HighflyPremiumCredentialStore.getInstance(context);
        String syntheticCredential = "unit-test-premium-token-2026";
        try {
            store.clearToken();
            assertTrue(store.saveToken(syntheticCredential));
            assertTrue(store.hasCredential());

            store.clearSession();

            assertEquals(syntheticCredential, store.readTokenForRequest());
            assertTrue(store.hasCredential());
            assertTrue(
                    context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                            .contains("highfly_premium_token_ciphertext")
            );
            assertFalse(
                    context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                            .getAll()
                            .toString()
                            .contains(syntheticCredential)
            );
        } finally {
            store.clearToken();
        }
    }
}

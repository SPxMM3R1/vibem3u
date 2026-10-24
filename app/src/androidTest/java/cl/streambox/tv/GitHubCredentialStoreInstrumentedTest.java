package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public final class GitHubCredentialStoreInstrumentedTest {
    @Test
    public void accessAndRotatingRefreshTokensSurviveEncryptedPreferenceReload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("github_link", Context.MODE_PRIVATE);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        GitHubCredentialStore store = new GitHubCredentialStore(context);
        String access = "gho_accessToken0123456789ABCDEFGHIJ";
        String refresh = "ghr_refreshToken0123456789ABCDEFGHIJ";
        long expiresAt = System.currentTimeMillis() + 8 * 60 * 60 * 1000L;
        try {
            store.clear();
            store.save(new GitHubCredentialStore.Credentials(access, refresh, expiresAt));

            GitHubCredentialStore.Credentials restored = store.credentials();

            assertNotNull(restored);
            assertEquals(access, restored.accessToken);
            assertEquals(refresh, restored.refreshToken);
            assertEquals(expiresAt, restored.accessExpiresAtMillis);
            assertFalse(preferences.getAll().containsValue(access));
            assertFalse(preferences.getAll().containsValue(refresh));
        } finally {
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : before.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof java.util.Set) {
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> values = (java.util.Set<String>) value;
                    editor.putStringSet(entry.getKey(), values);
                }
            }
            editor.commit();
        }
    }
}

package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class PlaylistDefaultsInstrumentedTest {
    @Test
    public void unconfiguredPreferencesSeedOnlyThePrimaryList() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        SourcePreferenceSnapshot snapshot = SourcePreferenceSnapshot.capture(preferences);
        try {
            preferences.edit()
                    .remove(SettingsActivity.KEY_PLAYLIST_URL)
                    .remove(SettingsActivity.KEY_PLAYLIST_URL_2)
                    .remove(SettingsActivity.KEY_PLAYLIST_ENABLED)
                    .remove(SettingsActivity.KEY_PLAYLIST_ENABLED_2)
                    .remove(SettingsActivity.KEY_SOURCE_MODEL)
                    .commit();

            SettingsActivity.ensureDefaultPlaylistConfigured(context);

            assertEquals(
                    SettingsActivity.DEFAULT_PLAYLIST_URL,
                    preferences.getString(SettingsActivity.KEY_PLAYLIST_URL, "")
            );
            assertTrue(preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, false));
            assertFalse(preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false));
            assertEquals("", preferences.getString(SettingsActivity.KEY_PLAYLIST_URL_2, ""));
        } finally {
            snapshot.restore(preferences);
        }
    }

    @Test
    public void existingDisabledSourceIsNeverReenabledByDefaultInitialization() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        SourcePreferenceSnapshot snapshot = SourcePreferenceSnapshot.capture(preferences);
        try {
            preferences.edit()
                    .putString(SettingsActivity.KEY_PLAYLIST_URL, "")
                    .putBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, false)
                    .remove(SettingsActivity.KEY_PLAYLIST_URL_2)
                    .remove(SettingsActivity.KEY_PLAYLIST_ENABLED_2)
                    .remove(SettingsActivity.KEY_SOURCE_MODEL)
                    .commit();

            SettingsActivity.ensureDefaultPlaylistConfigured(context);

            assertEquals("", preferences.getString(SettingsActivity.KEY_PLAYLIST_URL, ""));
            assertFalse(preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, true));
            assertEquals("", preferences.getString(SettingsActivity.KEY_PLAYLIST_URL_2, ""));
            // An absent secondary-list flag means the list stays off.
            assertFalse(preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false));
        } finally {
            snapshot.restore(preferences);
        }
    }

    /**
     * The source tab no longer ships a switch per M3U list, so an installation
     * that kept Lista 2 switched off must not start loading it after the update.
     */
    @Test
    public void disabledSecondaryListStaysEmptyAfterTheSourceModelMigration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        SourcePreferenceSnapshot snapshot = SourcePreferenceSnapshot.capture(preferences);
        try {
            preferences.edit()
                    .putString(SettingsActivity.KEY_PLAYLIST_URL, SettingsActivity.DEFAULT_PLAYLIST_URL)
                    .putBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, true)
                    .putString(SettingsActivity.KEY_PLAYLIST_URL_2, SettingsActivity.DEFAULT_PLAYLIST_URL_2)
                    .putBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false)
                    .remove(SettingsActivity.KEY_SOURCE_MODEL)
                    .commit();

            SettingsActivity.ensureDefaultPlaylistConfigured(context);

            assertEquals("", preferences.getString(SettingsActivity.KEY_PLAYLIST_URL_2, ""));
            assertFalse(preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false));
        } finally {
            snapshot.restore(preferences);
        }
    }

    @Test
    public void enabledSecondaryListKeepsItsAddressAfterTheSourceModelMigration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        SourcePreferenceSnapshot snapshot = SourcePreferenceSnapshot.capture(preferences);
        try {
            preferences.edit()
                    .putString(SettingsActivity.KEY_PLAYLIST_URL, SettingsActivity.DEFAULT_PLAYLIST_URL)
                    .putBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, true)
                    .putString(SettingsActivity.KEY_PLAYLIST_URL_2, SettingsActivity.DEFAULT_PLAYLIST_URL_2)
                    .putBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, true)
                    .remove(SettingsActivity.KEY_SOURCE_MODEL)
                    .commit();

            SettingsActivity.ensureDefaultPlaylistConfigured(context);

            assertEquals(
                    SettingsActivity.DEFAULT_PLAYLIST_URL_2,
                    preferences.getString(SettingsActivity.KEY_PLAYLIST_URL_2, "")
            );
            assertTrue(preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false));
        } finally {
            snapshot.restore(preferences);
        }
    }

    private static final class SourcePreferenceSnapshot {
        private final boolean hasUrl1;
        private final String url1;
        private final boolean hasUrl2;
        private final String url2;
        private final boolean hasEnabled1;
        private final boolean enabled1;
        private final boolean hasEnabled2;
        private final boolean enabled2;
        private final boolean hasSourceModel;
        private final int sourceModel;

        private SourcePreferenceSnapshot(
                boolean hasUrl1,
                String url1,
                boolean hasUrl2,
                String url2,
                boolean hasEnabled1,
                boolean enabled1,
                boolean hasEnabled2,
                boolean enabled2,
                boolean hasSourceModel,
                int sourceModel
        ) {
            this.hasUrl1 = hasUrl1;
            this.url1 = url1;
            this.hasUrl2 = hasUrl2;
            this.url2 = url2;
            this.hasEnabled1 = hasEnabled1;
            this.enabled1 = enabled1;
            this.hasEnabled2 = hasEnabled2;
            this.enabled2 = enabled2;
            this.hasSourceModel = hasSourceModel;
            this.sourceModel = sourceModel;
        }

        private static SourcePreferenceSnapshot capture(SharedPreferences preferences) {
            return new SourcePreferenceSnapshot(
                    preferences.contains(SettingsActivity.KEY_PLAYLIST_URL),
                    preferences.getString(SettingsActivity.KEY_PLAYLIST_URL, null),
                    preferences.contains(SettingsActivity.KEY_PLAYLIST_URL_2),
                    preferences.getString(SettingsActivity.KEY_PLAYLIST_URL_2, null),
                    preferences.contains(SettingsActivity.KEY_PLAYLIST_ENABLED),
                    preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, false),
                    preferences.contains(SettingsActivity.KEY_PLAYLIST_ENABLED_2),
                    preferences.getBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, false),
                    preferences.contains(SettingsActivity.KEY_SOURCE_MODEL),
                    preferences.getInt(SettingsActivity.KEY_SOURCE_MODEL, 0)
            );
        }

        private void restore(SharedPreferences preferences) {
            SharedPreferences.Editor editor = preferences.edit()
                    .remove(SettingsActivity.KEY_PLAYLIST_URL)
                    .remove(SettingsActivity.KEY_PLAYLIST_URL_2)
                    .remove(SettingsActivity.KEY_PLAYLIST_ENABLED)
                    .remove(SettingsActivity.KEY_PLAYLIST_ENABLED_2)
                    .remove(SettingsActivity.KEY_SOURCE_MODEL);
            if (hasUrl1) editor.putString(SettingsActivity.KEY_PLAYLIST_URL, url1);
            if (hasUrl2) editor.putString(SettingsActivity.KEY_PLAYLIST_URL_2, url2);
            if (hasEnabled1) {
                editor.putBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED, enabled1);
            }
            if (hasEnabled2) {
                editor.putBoolean(SettingsActivity.KEY_PLAYLIST_ENABLED_2, enabled2);
            }
            if (hasSourceModel) {
                editor.putInt(SettingsActivity.KEY_SOURCE_MODEL, sourceModel);
            }
            editor.commit();
        }
    }
}

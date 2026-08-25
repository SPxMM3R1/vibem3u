package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** User-controlled availability of complete resolver channel groups. */
public final class ResolverPreferences {
    private static final String KEY_PREFIX = "resolver_group_enabled_";
    private static final String KEY_TVVOO_RESOLUTION_MODE = "tvvoo_resolution_mode";
    private final SharedPreferences preferences;

    public ResolverPreferences(Context context) {
        preferences = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled(ResolverDefinition definition) {
        return preferences.getBoolean(
                KEY_PREFIX + definition.getId(),
                definition.isEnabledByDefault()
        );
    }

    public void setEnabled(ResolverDefinition definition, boolean enabled) {
        preferences.edit().putBoolean(KEY_PREFIX + definition.getId(), enabled).apply();
    }

    public TvVooResolutionMode getTvVooResolutionMode() {
        return TvVooResolutionMode.fromPreference(preferences.getString(
                KEY_TVVOO_RESOLUTION_MODE,
                TvVooResolutionMode.BOTH.getPreferenceValue()
        ));
    }

    public void setTvVooResolutionMode(TvVooResolutionMode mode) {
        TvVooResolutionMode safeMode = mode == null ? TvVooResolutionMode.BOTH : mode;
        preferences.edit()
                .putString(KEY_TVVOO_RESOLUTION_MODE, safeMode.getPreferenceValue())
                .apply();
    }
}

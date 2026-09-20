package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** User-controlled availability of complete resolver channel groups. */
public final class ResolverPreferences {
    private static final String KEY_PREFIX = "resolver_group_enabled_";
    private final SharedPreferences preferences;
    private final MediaFlowPreferences mediaFlow;

    public ResolverPreferences(Context context) {
        preferences = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
        mediaFlow = new MediaFlowPreferences(context);
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

    /** MediaFlow is an explicit opt-in and is off on fresh installs. */
    public boolean isMediaFlowEnabled() {
        return mediaFlow.isEnabled() && mediaFlow.isConfigurationValid();
    }

    public void setMediaFlowEnabled(boolean enabled) {
        mediaFlow.setEnabled(enabled);
    }

    public MediaFlowPreferences mediaFlowPreferences() {
        return mediaFlow;
    }

    /**
     * Highfly's public Stremio manifest is configurable for deployments that
     * use a scoped manifest path. A blank or malformed value is rejected by
     * the settings screen and the resolver falls back to its built-in URL.
     */
    public String highflyManifestUrl() {
        String value = preferences.getString(
                SettingsActivity.KEY_HIGHFLY_MANIFEST_URL,
                SettingsActivity.DEFAULT_HIGHFLY_MANIFEST_URL
        );
        return AppStrings.isBlank(value)
                ? SettingsActivity.DEFAULT_HIGHFLY_MANIFEST_URL
                : value.trim();
    }

}

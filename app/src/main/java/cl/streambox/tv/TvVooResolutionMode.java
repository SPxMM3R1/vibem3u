package cl.streambox.tv;

import java.util.Locale;

/** User-selected routing policy for channels handled by the TvVoo provider. */
public enum TvVooResolutionMode {
    BOTH("both"),
    DIRECT_ONLY("direct_only"),
    EXTERNAL_ONLY("external_only");

    private final String preferenceValue;

    TvVooResolutionMode(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public boolean usesExternalResolver() {
        return this != DIRECT_ONLY;
    }

    public boolean usesDirectResolver() {
        return this != EXTERNAL_ONLY;
    }

    public static TvVooResolutionMode fromPreference(String value) {
        if (value == null) return BOTH;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TvVooResolutionMode mode : values()) {
            if (mode.preferenceValue.equals(normalized)) return mode;
        }
        return BOTH;
    }
}

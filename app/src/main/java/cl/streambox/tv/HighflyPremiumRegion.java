package cl.streambox.tv;

import java.util.Locale;

/**
 * Regiones del servicio Highfly Premium (dominio .to desde 2026-10). AUTO usa la
 * principal y, si no responde, prueba las demás en orden.
 */
enum HighflyPremiumRegion {
    AUTO("auto", "https://premium.highfly.to"),
    MAIN("main", "https://premium.highfly.to"),
    US1("us1", "https://premium-us1.highfly.to"),
    US2("us2", "https://premium-us2.highfly.to"),
    EU1("eu1", "https://premium-eu1.highfly.to");

    /** Hosts a los que se puede enviar el token: nunca otro dominio. */
    static final java.util.Set<String> HOSTS = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "premium.highfly.to",
                    "premium-us1.highfly.to",
                    "premium-us2.highfly.to",
                    "premium-eu1.highfly.to"
            )));

    private final String preferenceValue;
    private final String baseUrl;

    HighflyPremiumRegion(String preferenceValue, String baseUrl) {
        this.preferenceValue = preferenceValue;
        this.baseUrl = baseUrl;
    }

    String preferenceValue() {
        return preferenceValue;
    }

    String baseUrl() {
        return baseUrl;
    }

    /** Orden de prueba: la región elegida primero; AUTO recorre todas. */
    HighflyPremiumRegion[] attemptOrder() {
        if (this != AUTO) return new HighflyPremiumRegion[]{this};
        return new HighflyPremiumRegion[]{MAIN, EU1, US1, US2};
    }

    static HighflyPremiumRegion fromPreference(String value) {
        if (value != null) {
            for (HighflyPremiumRegion region : values()) {
                if (region.preferenceValue.equalsIgnoreCase(value.trim())) return region;
            }
        }
        return AUTO;
    }

    /**
     * Región de un enlace pegado por el usuario. Acepta el dominio .to vigente y el
     * .dev anterior (Highfly todavía lo atiende), pero las solicitudes van siempre al .to.
     */
    static HighflyPremiumRegion fromHost(String host) {
        if (host == null) return null;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".highfly.dev")) {
            normalized = normalized.substring(0, normalized.length() - 4) + ".to";
        }
        switch (normalized) {
            case "premium.highfly.to": return MAIN;
            case "premium-us1.highfly.to": return US1;
            case "premium-us2.highfly.to": return US2;
            case "premium-eu1.highfly.to": return EU1;
            default: return null;
        }
    }
}

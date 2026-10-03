package cl.streambox.tv;

import org.json.JSONObject;

import java.io.IOException;

/** Respuesta de {@code verify.json}: plan, vigencia y si la cuenta está activa. */
final class HighflyPremiumAccount {
    enum Plan { MONTHLY, YEARLY, STANDARD, PRO, PREMIUM }

    private final boolean active;
    private final long expiresAtMillis;
    private final Plan plan;

    HighflyPremiumAccount(boolean active, long expiresAtMillis, Plan plan) {
        this.active = active;
        this.expiresAtMillis = Math.max(0L, expiresAtMillis);
        this.plan = plan == null ? Plan.PREMIUM : plan;
    }

    /**
     * Igual que la página de Highfly: un HTTP 200 ya verifica el token; los campos
     * solo describen el plan. Un {@code active=false} explícito sí lo invalida.
     */
    static HighflyPremiumAccount parse(String json, long nowMillis) throws IOException {
        if (json == null || AppStrings.isBlank(json)) throw new IOException("Respuesta Premium vacía.");
        try {
            JSONObject object = new JSONObject(json);
            Object activeValue = object.opt("active");
            boolean active = !(Boolean.FALSE.equals(activeValue)
                    || "false".equalsIgnoreCase(String.valueOf(activeValue))
                    || Integer.valueOf(0).equals(activeValue));
            long expiresSeconds = object.optLong("expires_at", 0L);
            long expiresMillis = expiresSeconds > 0L && expiresSeconds < Long.MAX_VALUE / 1000L
                    ? expiresSeconds * 1000L : 0L;
            Plan plan;
            switch (object.optInt("plan", 0)) {
                case 1:
                    long daysLeft = expiresMillis > 0L ? (expiresMillis - nowMillis) / 86_400_000L : -1L;
                    plan = daysLeft > 300L ? Plan.YEARLY : Plan.MONTHLY;
                    break;
                case 2: plan = Plan.STANDARD; break;
                case 3: plan = Plan.PRO; break;
                default: plan = Plan.PREMIUM;
            }
            return new HighflyPremiumAccount(active, expiresMillis, plan);
        } catch (Exception error) {
            throw new IOException("Respuesta de credencial Premium inválida.");
        }
    }

    boolean isActive() {
        return active;
    }

    long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    Plan getPlan() {
        return plan;
    }

    boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
    }
}

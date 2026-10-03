package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de Highfly Premium ({@code premium*.highfly.to}). Mismo contrato que la
 * página de configuración: {@code /<token>/verify.json} y
 * {@code /<token>/stream/sport/leaf:<slug>.json}. El token solo viaja a esos hosts y
 * nunca aparece en mensajes de error ni en el progreso mostrado.
 */
final class HighflyPremiumClient {
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final int MAX_STREAMS = 16;

    /** Highfly respondió 401/403 o la cuenta está inactiva o vencida. */
    static final class RejectedException extends IOException {
        RejectedException() {
            super("Highfly Premium rechazó el token.");
        }
    }

    private final TokenHttpClient http;

    HighflyPremiumClient(TokenHttpClient http) {
        this.http = http;
    }

    HighflyPremiumAccount verify(String token, HighflyPremiumRegion region) throws IOException {
        String json = get(token, region, "verify.json");
        HighflyPremiumAccount account = HighflyPremiumAccount.parse(json, System.currentTimeMillis());
        if (!account.isActive() || account.isExpired(System.currentTimeMillis())) {
            throw new RejectedException();
        }
        return account;
    }

    List<ResolverPayloadParsers.HighflyCandidate> streams(
            String token, HighflyPremiumRegion region, String slug) throws IOException {
        if (slug == null || !slug.matches("[A-Za-z0-9_-]{2,128}")) {
            throw new IOException("Slug Highfly inválido.");
        }
        String json = get(token, region, "stream/sport/leaf:" + slug + ".json");
        return ResolverPayloadParsers.parseHighflyCandidates(json, "streams", MAX_STREAMS);
    }

    static Map<String, String> headers(String accept) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", TokenHttpClient.BROWSER_USER_AGENT);
        if (!AppStrings.isBlank(accept)) headers.put("Accept", accept);
        return Collections.unmodifiableMap(headers);
    }

    /** Prueba las regiones en orden; un rechazo del token corta de inmediato. */
    private String get(String rawToken, HighflyPremiumRegion region, String path) throws IOException {
        String token = HighflyPremiumTokenRules.normalize(rawToken);
        HighflyPremiumRegion safeRegion = region == null ? HighflyPremiumRegion.AUTO : region;
        IOException last = null;
        for (HighflyPremiumRegion attempt : safeRegion.attemptOrder()) {
            URI uri = URI.create(attempt.baseUrl() + "/" + token + "/" + path);
            try {
                TokenHttpClient.Response response = http.getPublicOnHosts(
                        uri.toString(), headers("application/json"), MAX_JSON_BYTES, null,
                        HighflyPremiumRegion.HOSTS);
                ResolutionContext active = ResolutionContext.current();
                if (active != null) active.check();
                return new String(response.getBody(), StandardCharsets.UTF_8);
            } catch (TokenHttpClient.HttpStatusException error) {
                if (error.getStatusCode() == 401 || error.getStatusCode() == 403) {
                    throw new RejectedException();
                }
                last = new IOException("Highfly Premium respondió HTTP " + error.getStatusCode() + ".");
            } catch (IOException error) {
                ResolutionContext active = ResolutionContext.current();
                if (active != null) active.check();
                // Sin el mensaje original: podría incluir la URL con el token.
                last = new IOException("Highfly Premium no respondió.");
            }
        }
        throw last == null ? new IOException("Highfly Premium no respondió.") : last;
    }
}

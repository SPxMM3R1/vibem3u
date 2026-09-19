package cl.streambox.tv;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Builds the TvVoo configuration route that emits MediaFlow extractor URLs. */
final class MediaFlowTvVooEndpoint {
    private static final String DEFAULT_BASE = "https://tvvoo.hayd.uk/stream/tv";
    private static final String PLACEHOLDER = "placeholder";
    private static final URI DISCOVERY_ORIGIN = URI.create("https://proxy.invalid");

    private MediaFlowTvVooEndpoint() {}

    static URI forChannel(
            ResolverDefinition definition,
            Channel channel,
            URI mediaFlowOrigin,
            String alias
    ) throws IOException {
        URI base = parseBase(definition, channel);
        String country = countryToken(channel, alias);
        // Discovery is deliberately configured with a non-routable placeholder.
        // The user's LAN origin and API password must never be sent to TvVoo.
        String origin = encodeBase64(DISCOVERY_ORIGIN.toString());
        String placeholder = encodeBase64(PLACEHOLDER);
        String path = "/cfg-" + country
                + "-pxt_mfl-mfu_" + origin
                + "-mfp_" + placeholder
                + "/stream/tv/" + withVmuNamespace(alias) + ".json";
        try {
            String authority = base.getHost();
            if (base.getPort() >= 0) authority += ":" + base.getPort();
            return URI.create(base.getScheme() + "://" + authority + path);
        } catch (IllegalArgumentException error) {
            throw new IOException("Endpoint TvVoo MediaFlow inválido.", error);
        }
    }

    static URI parseBase(ResolverDefinition definition, Channel channel) throws IOException {
        String configured = channel == null || channel.getAttributes() == null
                ? ""
                : channel.getAttributes().get("x-resolver-endpoint");
        if (AppStrings.isBlank(configured)) {
            configured = definition.getConfig("endpointBase", DEFAULT_BASE);
        }
        URI base;
        try {
            base = URI.create(configured.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("Endpoint TvVoo inválido.", error);
        }
        if (!"https".equalsIgnoreCase(base.getScheme())
                || !"tvvoo.hayd.uk".equalsIgnoreCase(base.getHost())
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || base.getPort() < -1 || base.getPort() > 65535
                || base.getPort() == 0) {
            throw new IOException("Endpoint TvVoo no permitido.");
        }
        return base;
    }

    static String withVmuNamespace(String alias) throws IOException {
        String canonical = TvVooSourceHistory.canonicalAlias(alias);
        if (canonical.isEmpty()) throw new IOException("Alias TvVoo inválido.");
        String encodedSuffix = canonical.substring("vavoo_".length());
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(
                    encodedSuffix.replace("+", "%2B"),
                    StandardCharsets.UTF_8.name()
            );
        } catch (Exception error) {
            throw new IOException("Alias TvVoo inválido.", error);
        }
        if (!decoded.toLowerCase(Locale.ROOT).contains(".vmu")) {
            int group = decoded.toLowerCase(Locale.ROOT).indexOf("|group:");
            decoded = group >= 0
                    ? decoded.substring(0, group) + ".vmu" + decoded.substring(group)
                    : decoded + ".vmu";
        }
        try {
            return "vavoo_" + URLEncoder.encode(decoded, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception impossible) {
            throw new IOException("Alias TvVoo inválido.", impossible);
        }
    }

    static URI discoveryOrigin() {
        return DISCOVERY_ORIGIN;
    }

    private static String countryToken(Channel channel, String alias) throws IOException {
        String aliasCountry = aliasGroup(alias);
        if (!aliasCountry.isEmpty()) return aliasCountry;
        String value = channel == null || channel.getAttributes() == null
                ? ""
                : channel.getAttributes().get("tvg-country");
        String normalized = TvVooCatalogChannel.countryKey(value);
        String mapped = groupToken(normalized);
        if (!mapped.isEmpty()) return mapped;
        throw new IOException("El alias TvVoo no tiene país válido.");
    }

    private static String aliasGroup(String alias) {
        if (alias == null || alias.trim().isEmpty()) return "";
        try {
            String canonical = TvVooSourceHistory.canonicalAlias(alias);
            if (canonical.isEmpty()) return "";
            String decoded = java.net.URLDecoder.decode(
                    canonical.substring("vavoo_".length()).replace("+", "%2B"),
                    StandardCharsets.UTF_8.name()
            );
            String lower = decoded.toLowerCase(Locale.ROOT);
            int marker = lower.indexOf("|group:");
            if (marker < 0) return "";
            String country = decoded.substring(marker + 7).trim().toLowerCase(Locale.ROOT);
            return country.matches("[a-z]{2}") ? country : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String groupToken(String country) {
        return switch (country) {
            case "unitedkingdom" -> "uk";
            case "unitedstates" -> "us";
            case "argentina" -> "ar";
            case "chile" -> "cl";
            case "spain" -> "es";
            case "france" -> "fr";
            case "germany" -> "de";
            case "italy" -> "it";
            case "portugal" -> "pt";
            case "netherlands" -> "nl";
            case "poland" -> "pl";
            case "turkey" -> "tr";
            case "ireland" -> "ie";
            default -> "";
        };
    }

    private static String encodeBase64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

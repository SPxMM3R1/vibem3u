package cl.streambox.tv;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respaldo de un canal directo (2026-10-04). Puede ser la misma señal en TvVoo
 * ({@code backupTvVoo} del layout, su catalogKey) o, desde la 0.5.65, otra dirección M3U de la
 * misma señal: con {@code preferredM3u} el canal abre primero la señal preferida y deja la suya
 * como respaldo ({@link #DIRECT_ATTRIBUTE}). Si la primera no se recupera, la app pasa sola a
 * la otra.
 */
final class TvVooBackup {
    static final String ATTRIBUTE = "x-backup-tvvoo";
    /** Dirección directa de respaldo; solo existe en memoria, nunca se publica. */
    static final String DIRECT_ATTRIBUTE = "x-backup-stream";

    private TvVooBackup() {}

    /** catalogKey del respaldo, o "" si el canal no tiene. */
    static String stableIdOf(Channel channel) {
        if (channel == null) return "";
        String value = channel.getAttributes().get(ATTRIBUTE);
        return value == null ? "" : value.trim();
    }

    /** Tiene algún respaldo: TvVoo o directo. */
    static boolean has(Channel channel) {
        return hasTvVoo(channel) || directBackupOf(channel) != null;
    }

    static boolean hasTvVoo(Channel channel) {
        return TvVooCatalogChannel.isStableId(stableIdOf(channel));
    }

    /** Dirección directa de respaldo, o null. */
    static URI directBackupOf(Channel channel) {
        if (channel == null) return null;
        String value = channel.getAttributes().get(DIRECT_ATTRIBUTE);
        if (value == null || value.trim().isEmpty()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? uri : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /**
     * El canal {@code primary} reproduce primero la señal de {@code preferred} y guarda la suya
     * como respaldo directo. Conserva identidad, nombre, logo y grupo de {@code primary}.
     */
    static Channel withPreferredStream(Channel primary, Channel preferred) {
        if (primary == null || preferred == null || preferred.getStreamUri() == null
                || primary.getStreamUri() == null) return primary;
        Map<String, String> attributes = new LinkedHashMap<>(primary.getAttributes());
        attributes.put(DIRECT_ATTRIBUTE, primary.getStreamUri().toString());
        return new Channel(primary.getName(), preferred.getStreamUri(), primary.getLogoUri(),
                primary.getGroup(), attributes);
    }

    /** Copia del canal con su respaldo TvVoo declarado (sin tocar la identidad). */
    static Channel withBackup(Channel channel, String stableId) {
        if (channel == null || !TvVooCatalogChannel.isStableId(stableId)) return channel;
        Map<String, String> attributes = new LinkedHashMap<>(channel.getAttributes());
        attributes.put(ATTRIBUTE, stableId);
        return new Channel(channel.getName(), channel.getStreamUri(), channel.getLogoUri(),
                channel.getGroup(), attributes);
    }

    /**
     * Canal TvVoo con el que se resuelve el respaldo. Conserva nombre, grupo y logo del canal
     * directo; solo sirve para pedirle la señal al resolutor TvVoo. Null si no hay respaldo.
     */
    static Channel resolutionChannel(Channel channel) {
        String stableId = stableIdOf(channel);
        if (!TvVooCatalogChannel.isStableId(stableId)) {
            URI direct = directBackupOf(channel);
            if (direct == null) return null;
            Map<String, String> attributes = new LinkedHashMap<>(channel.getAttributes());
            attributes.remove(DIRECT_ATTRIBUTE);
            return new Channel(channel.getName(), direct, channel.getLogoUri(),
                    channel.getGroup(), attributes);
        }
        int separator = stableId.indexOf('|');
        String countryKey = stableId.substring(0, separator);
        String alias = stableId.substring(separator + 1);
        URI logo = channel.getLogoUri();
        try {
            return new TvVooCatalogChannel(
                    stableId,
                    alias,
                    channel.getName(),
                    countryKey,
                    channel.getGroup() == null ? "" : channel.getGroup(),
                    "",
                    logo == null ? "" : logo.toString(),
                    Collections.singletonList(alias)
            ).toChannel();
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}

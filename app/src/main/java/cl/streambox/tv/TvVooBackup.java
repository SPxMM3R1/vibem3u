package cl.streambox.tv;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respaldo TvVoo de un canal directo (2026-10-04): la misma señal en TvVoo, guardada en el
 * layout web como {@code backupTvVoo} (su catalogKey). La app abre primero el directo y, si
 * no se recupera, pasa a esta versión; «Fuentes y calidades» muestra ambas.
 */
final class TvVooBackup {
    static final String ATTRIBUTE = "x-backup-tvvoo";

    private TvVooBackup() {}

    /** catalogKey del respaldo, o "" si el canal no tiene. */
    static String stableIdOf(Channel channel) {
        if (channel == null) return "";
        String value = channel.getAttributes().get(ATTRIBUTE);
        return value == null ? "" : value.trim();
    }

    static boolean has(Channel channel) {
        return TvVooCatalogChannel.isStableId(stableIdOf(channel));
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
        if (!TvVooCatalogChannel.isStableId(stableId)) return null;
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

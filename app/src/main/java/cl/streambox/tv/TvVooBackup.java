package cl.streambox.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** tvg-id propio de cada respaldo, indexado por posición; solo vive en el canal proyectado. */
    private static final String SOURCE_ID_PREFIX = "x-backup-source-id-";

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

    /** Primera dirección directa de respaldo, o null. */
    static URI directBackupOf(Channel channel) {
        List<URI> backups = directBackupsOf(channel);
        return backups.isEmpty() ? null : backups.get(0);
    }

    /**
     * Direcciones directas de respaldo en el orden en que se prueban (desde la 0.5.72 puede
     * haber varias: {@code backupm3u} del layout). Admite únicamente HTTP(S); las referencias
     * internas de resolutores no son destinos de respaldo M3U.
     */
    static List<URI> directBackupsOf(Channel channel) {
        if (channel == null) return Collections.emptyList();
        String value = channel.getAttributes().get(DIRECT_ATTRIBUTE);
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        List<URI> result = new ArrayList<>();
        for (String line : value.split("\n")) {
            URI uri = playbackReference(line.trim());
            if (uri != null && !result.contains(uri)) result.add(uri);
        }
        return Collections.unmodifiableList(result);
    }

    private static URI playbackReference(String value) {
        if (value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return uri;
            return null;
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

    /** Agrega señales de otras filas M3U al final de los respaldos directos del canal. */
    static Channel withDirectBackups(Channel channel, List<Channel> backups) {
        if (channel == null || backups == null || backups.isEmpty()) return channel;
        List<String> lines = new ArrayList<>();
        for (URI uri : directBackupsOf(channel)) lines.add(uri.toString());
        Map<String, String> attributes = new LinkedHashMap<>(channel.getAttributes());
        for (Channel backup : backups) {
            URI uri = backup == null || backup.getStreamUri() == null ? null
                    : playbackReference(backup.getStreamUri().toString());
            if (uri == null || uri.equals(channel.getStreamUri())
                    || lines.contains(uri.toString())) continue;
            // Identidad propia del respaldo: así no lo reclama el resolutor del canal dueño.
            String id = backup.getTvgId();
            if (!AppStrings.isBlank(id)) attributes.put(SOURCE_ID_PREFIX + lines.size(), id);
            lines.add(uri.toString());
        }
        if (lines.isEmpty()) return channel;
        attributes.put(DIRECT_ATTRIBUTE, String.join("\n", lines));
        return new Channel(channel.getName(), channel.getStreamUri(), channel.getLogoUri(),
                channel.getGroup(), attributes);
    }

    /** Canal que reproduce el respaldo directo número {@code index} (0 = el primero), o null. */
    static Channel directResolutionChannel(Channel channel, int index) {
        List<URI> backups = directBackupsOf(channel);
        if (index < 0 || index >= backups.size()) return null;
        Map<String, String> attributes = new LinkedHashMap<>(channel.getAttributes());
        URI uri = backups.get(index);
        String backupId = attributes.get(SOURCE_ID_PREFIX + index);
        attributes.remove(DIRECT_ATTRIBUTE);
        attributes.keySet().removeIf(key -> key.startsWith(SOURCE_ID_PREFIX));
        // Respaldo HTTP: se reproduce tal cual, con su propia identidad y sin los datos de
        // resolutor del canal dueño (TVN 0104 → su resolutor abría la señal principal).
        attributes.keySet().removeIf(key -> key.startsWith("x-resolver"));
        if (AppStrings.isBlank(backupId)) {
            attributes.remove("tvg-id");
        } else {
            attributes.put("tvg-id", backupId);
        }
        return new Channel(channel.getName(), uri, channel.getLogoUri(),
                channel.getGroup(), attributes);
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
        if (!TvVooCatalogChannel.isStableId(stableId)) return directResolutionChannel(channel, 0);
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

package cl.streambox.tv;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pistas de audio elegibles de un canal (0.6.11): idioma y canales en español, sin repetidas.
 * La elección se guarda por canal con {@link Option#key}; al volver al canal se busca la misma
 * pista y, si cambió, otra del mismo idioma.
 */
final class AudioTrackOptions {
    private static final Locale SPANISH = new Locale("es");

    private AudioTrackOptions() {}

    static final class Option {
        final int groupIndex;
        final int trackIndex;
        final String language;
        final String label;
        final String key;
        final boolean selected;

        Option(int groupIndex, int trackIndex, String language, String label, String key,
                boolean selected) {
            this.groupIndex = groupIndex;
            this.trackIndex = trackIndex;
            this.language = language;
            this.label = label;
            this.key = key;
            this.selected = selected;
        }
    }

    /** Pistas soportadas por el equipo, en el orden del stream; las idénticas se cuentan una vez. */
    static List<Option> collect(Tracks tracks) {
        if (tracks == null) return Collections.emptyList();
        List<Option> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int group = 0; group < tracks.getGroups().size(); group++) {
            Tracks.Group trackGroup = tracks.getGroups().get(group);
            if (trackGroup.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int track = 0; track < trackGroup.length; track++) {
                if (!trackGroup.isTrackSupported(track)) continue;
                Format format = trackGroup.getTrackFormat(track);
                String language = normalizeLanguage(format.language);
                String name = format.label == null ? "" : format.label.trim();
                String key = keyFor(language, format.channelCount, name);
                if (!seen.add(key)) continue;
                result.add(new Option(group, track, language,
                        labelFor(language, name, format.channelCount, result.size() + 1),
                        key, trackGroup.isTrackSelected(track)));
            }
        }
        return Collections.unmodifiableList(withDistinctLabels(result));
    }

    /** Dos pistas con el mismo nombre visible se numeran: «Español · estéreo 2». */
    static List<Option> withDistinctLabels(List<Option> options) {
        List<Option> result = new ArrayList<>(options.size());
        for (int index = 0; index < options.size(); index++) {
            Option option = options.get(index);
            int same = 0;
            int position = 0;
            for (int other = 0; other < options.size(); other++) {
                if (!options.get(other).label.equals(option.label)) continue;
                same++;
                if (other <= index) position++;
            }
            result.add(same < 2 || position < 2 ? option : new Option(option.groupIndex,
                    option.trackIndex, option.language, option.label + " " + position,
                    option.key, option.selected));
        }
        return result;
    }

    /** La pista guardada; si ya no está, la primera del mismo idioma; si no, ninguna. */
    static Option find(List<Option> options, String savedKey) {
        if (options == null || savedKey == null || savedKey.isEmpty()) return null;
        for (Option option : options) if (option.key.equals(savedKey)) return option;
        String language = savedKey.substring(0, Math.max(0, savedKey.indexOf('|')));
        if (language.isEmpty()) return null;
        for (Option option : options) if (option.language.equals(language)) return option;
        return null;
    }

    static String keyFor(String language, int channelCount, String name) {
        return language + "|" + Math.max(0, channelCount) + "|" + (name == null ? "" : name);
    }

    /** «Español · estéreo»; sin idioma, el nombre que trae el stream o «Pista N». */
    static String labelFor(String language, String name, int channelCount, int position) {
        String base = languageName(language);
        if (base.isEmpty()) base = name == null || name.isEmpty() ? "Pista " + position : name;
        String channels = channelsLabel(channelCount);
        return channels.isEmpty() ? base : base + " · " + channels;
    }

    /** Idioma en español con mayúscula inicial («Inglés»); vacío si no se conoce. */
    static String languageName(String language) {
        if (language == null || language.isEmpty()) return "";
        String name = new Locale(language).getDisplayLanguage(SPANISH);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(language)) {
            return language.toUpperCase(Locale.ROOT);
        }
        return name.substring(0, 1).toUpperCase(SPANISH) + name.substring(1);
    }

    static String channelsLabel(int channelCount) {
        switch (channelCount) {
            case 1: return "mono";
            case 2: return "estéreo";
            case 6: return "5.1";
            case 8: return "7.1";
            default: return channelCount > 0 ? channelCount + " canales" : "";
        }
    }

    /**
     * Código de dos letras. Media3 ya convierte «spa»/«eng»; aquí se cubren los que no
     * (los satélites usan «esl»/«esp») y se descartan los indeterminados.
     */
    static String normalizeLanguage(String language) {
        if (language == null) return "";
        String value = language.trim().toLowerCase(Locale.ROOT);
        int separator = value.indexOf('-');
        if (separator > 0) value = value.substring(0, separator);
        switch (value) {
            case "": case "und": case "mul": case "zxx": case "mis": return "";
            case "esl": case "esp": case "spa": return "es";
            case "eng": return "en";
            case "por": return "pt";
            default: return value;
        }
    }
}

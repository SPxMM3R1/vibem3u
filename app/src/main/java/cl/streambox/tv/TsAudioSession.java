package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lo que se sabe del audio MPEG-TS de la fuente en reproducción (0.6.12): una por fuente, creada
 * con el idioma guardado para el canal. Las lecturas de segmentos (hilos de carga de Media3) la
 * alimentan; Opciones la consulta para armar la fila «Audio».
 */
final class TsAudioSession implements TsAudioPmtFilter.Listener {
    private final String preferredLanguage;
    private final Set<Integer> pmtPids = ConcurrentHashMap.newKeySet();
    private final Map<Integer, TsAudioPmtFilter.AudioEntry> entries = new TreeMap<>();
    private volatile String appliedLanguage = "";

    TsAudioSession(String preferredLanguage) {
        this.preferredLanguage = AudioTrackOptions.normalizeLanguage(preferredLanguage);
    }

    TsAudioPmtFilter newFilter() {
        return new TsAudioPmtFilter(preferredLanguage, this);
    }

    String getPreferredLanguage() {
        return preferredLanguage;
    }

    String getAppliedLanguage() {
        return appliedLanguage;
    }

    @Override
    public void onPmtPid(int pid) {
        pmtPids.add(pid);
    }

    @Override
    public boolean isPmtPid(int pid) {
        return pmtPids.contains(pid);
    }

    @Override
    public synchronized void onAudioEntries(List<TsAudioPmtFilter.AudioEntry> found) {
        for (TsAudioPmtFilter.AudioEntry entry : found) entries.putIfAbsent(entry.pid, entry);
    }

    @Override
    public void onLanguageApplied(String language) {
        appliedLanguage = language;
    }

    /** Idiomas distintos y conocidos, en orden de PID (el primero es el que Media3 elige solo). */
    synchronized List<String> languages() {
        List<String> result = new ArrayList<>();
        for (TsAudioPmtFilter.AudioEntry entry : entries.values()) {
            if (!entry.language.isEmpty() && !result.contains(entry.language)) {
                result.add(entry.language);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Idioma guardado en una preferencia de audio («en|2|» → «en»). */
    static String languageOfPreference(String savedKey) {
        if (savedKey == null) return "";
        int separator = savedKey.indexOf('|');
        return AudioTrackOptions.normalizeLanguage(
                separator < 0 ? savedKey : savedKey.substring(0, separator));
    }
}

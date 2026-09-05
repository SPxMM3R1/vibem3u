package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EpgData {
    private static final EpgData EMPTY = new EpgData(Collections.emptyList());

    private final Map<String, List<EpgProgramme>> programmesByChannel;
    private final List<EpgProgramme> programmes;
    private final int programmeCount;
    private final long snapshotSignature;

    public EpgData(List<EpgProgramme> programmes) {
        List<EpgProgramme> sorted = new ArrayList<>();
        if (programmes != null) {
            for (EpgProgramme programme : programmes) {
                if (programme != null) sorted.add(programme);
            }
        }
        // Keep the immutable snapshot in a deterministic order. Parsing and
        // merging happen on a worker, so the main thread only receives this
        // ready-to-query index and never sorts a large XMLTV guide.
        sorted.sort(Comparator
                .comparing(EpgProgramme::getChannelId, Comparator.nullsFirst(String::compareTo))
                .thenComparingLong(EpgProgramme::getStartMillis)
                .thenComparingLong(EpgProgramme::getStopMillis)
                .thenComparing(
                        EpgProgramme::getTitle,
                        Comparator.nullsFirst(String::compareTo)
                ));
        this.programmes = Collections.unmodifiableList(sorted);
        Map<String, List<EpgProgramme>> mutable = new LinkedHashMap<>();
        for (EpgProgramme programme : this.programmes) {
            List<EpgProgramme> channelProgrammes = mutable.get(programme.getChannelId());
            if (channelProgrammes == null) {
                channelProgrammes = new ArrayList<>();
                mutable.put(programme.getChannelId(), channelProgrammes);
            }
            channelProgrammes.add(programme);
        }

        Map<String, List<EpgProgramme>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<EpgProgramme>> entry : mutable.entrySet()) {
            Collections.sort(entry.getValue(), (left, right) ->
                    Long.compare(left.getStartMillis(), right.getStartMillis()));
            immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        programmesByChannel = Collections.unmodifiableMap(immutable);
        programmeCount = this.programmes.size();
        snapshotSignature = signatureFor(this.programmes);
    }

    public static EpgData empty() { return EMPTY; }

    /**
     * Combines the independent XMLTV snapshots used by multiple M3U sources.
     * The constructor reindexes and sorts the result, so channels from both
     * lists can use the same lookup path as a single EPG.
     */
    public static EpgData merge(List<EpgData> dataSets) {
        if (dataSets == null || dataSets.isEmpty()) return EMPTY;
        List<EpgProgramme> merged = new ArrayList<>();
        EpgData only = null;
        int nonNullDataSets = 0;
        for (EpgData data : dataSets) {
            if (data != null) {
                nonNullDataSets++;
                only = data;
                merged.addAll(data.programmes);
            }
        }
        if (merged.isEmpty()) return EMPTY;
        if (nonNullDataSets == 1 && only != null) return only;
        return new EpgData(merged);
    }

    /**
     * Compact identity for a set of immutable snapshots. It lets callers
     * discard duplicate EPG callbacks before scheduling another merge.
     */
    static String mergeSignature(Map<String, EpgData> dataByUrl) {
        if (dataByUrl == null || dataByUrl.isEmpty()) return "";
        StringBuilder result = new StringBuilder(dataByUrl.size() * 32);
        for (Map.Entry<String, EpgData> entry : dataByUrl.entrySet()) {
            result.append(entry.getKey()).append('=');
            EpgData data = entry.getValue();
            if (data == null) {
                result.append("null");
            } else {
                result.append(data.programmeCount)
                        .append(':')
                        .append(data.snapshotSignature);
            }
            result.append(';');
        }
        return result.toString();
    }

    public EpgProgramme findCurrent(String channelId, long nowMillis) {
        if (channelId == null || channelId.isBlank()) return null;
        List<EpgProgramme> programmes = programmesByChannel.get(channelId);
        if (programmes == null) return null;
        for (EpgProgramme programme : programmes) {
            if (programme.getStartMillis() > nowMillis) break;
            if (programme.getStartMillis() <= nowMillis && nowMillis < programme.getStopMillis()) {
                return programme;
            }
        }
        return null;
    }

    /**
     * Returns the first programme after the programme currently in progress.
     * If there is no current programme, the first future programme is used.
     */
    public EpgProgramme findNext(String channelId, long nowMillis) {
        if (channelId == null || channelId.isBlank()) return null;
        List<EpgProgramme> programmes = programmesByChannel.get(channelId);
        if (programmes == null) return null;

        boolean currentFound = false;
        for (EpgProgramme programme : programmes) {
            if (programme.getStartMillis() <= nowMillis
                    && nowMillis < programme.getStopMillis()) {
                currentFound = true;
                continue;
            }
            if (programme.getStartMillis() > nowMillis
                    && (currentFound || programme.getStartMillis() >= nowMillis)) {
                return programme;
            }
        }
        return null;
    }

    public int getProgrammeCount() { return programmeCount; }

    List<EpgProgramme> getProgrammes() { return programmes; }

    private static long signatureFor(List<EpgProgramme> programmes) {
        long result = 1125899906842597L;
        for (EpgProgramme programme : programmes) {
            result = 31L * result + safeHash(programme.getChannelId());
            result = 31L * result + safeHash(programme.getTitle());
            result = 31L * result + programme.getStartMillis();
            result = 31L * result + programme.getStopMillis();
        }
        return 31L * result + programmes.size();
    }

    private static int safeHash(String value) {
        return value == null ? 0 : value.hashCode();
    }
}

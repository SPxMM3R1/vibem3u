package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EpgData {
    private static final EpgData EMPTY = new EpgData(Collections.emptyList());

    private final Map<String, List<EpgProgramme>> programmesByChannel;
    private final List<EpgProgramme> programmes;
    private final int programmeCount;

    public EpgData(List<EpgProgramme> programmes) {
        this.programmes = Collections.unmodifiableList(new ArrayList<>(programmes));
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
        for (EpgData data : dataSets) {
            if (data != null) merged.addAll(data.programmes);
        }
        return merged.isEmpty() ? EMPTY : new EpgData(merged);
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
}

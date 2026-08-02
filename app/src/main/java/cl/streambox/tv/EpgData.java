package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EpgData {
    private static final EpgData EMPTY = new EpgData(Collections.emptyList());

    private final Map<String, List<EpgProgramme>> programmesByChannel;
    private final int programmeCount;

    public EpgData(List<EpgProgramme> programmes) {
        Map<String, List<EpgProgramme>> mutable = new LinkedHashMap<>();
        for (EpgProgramme programme : programmes) {
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
        programmeCount = programmes.size();
    }

    public static EpgData empty() { return EMPTY; }

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

    public List<EpgProgramme> getProgrammes(String channelId) {
        if (channelId == null || channelId.isBlank()) return Collections.emptyList();
        List<EpgProgramme> programmes = programmesByChannel.get(channelId);
        return programmes == null ? Collections.emptyList() : programmes;
    }

    public int findProgrammeIndex(String channelId, long nowMillis) {
        List<EpgProgramme> programmes = getProgrammes(channelId);
        for (int index = 0; index < programmes.size(); index++) {
            EpgProgramme programme = programmes.get(index);
            if (programme.getStartMillis() <= nowMillis && nowMillis < programme.getStopMillis()) {
                return index;
            }
            if (programme.getStartMillis() > nowMillis) break;
        }
        return programmes.isEmpty() ? -1 : 0;
    }

    public int getProgrammeCount() { return programmeCount; }
}

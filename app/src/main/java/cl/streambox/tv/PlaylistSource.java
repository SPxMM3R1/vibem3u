package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One user-configured M3U source. The position is part of the contract: when
 * both sources are enabled, source 1 is always flattened before source 2.
 */
public final class PlaylistSource {
    private final int position;
    private final String url;

    public PlaylistSource(int position, String url) {
        if (position < 1) throw new IllegalArgumentException("position");
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url");
        }
        this.position = position;
        this.url = url.trim();
    }

    public int getPosition() {
        return position;
    }

    public String getUrl() {
        return url;
    }

    /**
     * Stable configuration identity used to decide whether the visible list
     * must be rebuilt. It deliberately excludes disabled sources because they
     * do not participate in playback or numbering.
     */
    public static String signature(List<PlaylistSource> sources) {
        if (sources == null || sources.isEmpty()) return "";
        List<PlaylistSource> ordered = new ArrayList<>(sources);
        Collections.sort(ordered, (left, right) ->
                Integer.compare(left.position, right.position));
        StringBuilder result = new StringBuilder();
        for (PlaylistSource source : ordered) {
            if (result.length() > 0) result.append('|');
            result.append(source.position).append('=').append(source.url);
        }
        return result.toString();
    }
}

package cl.streambox.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Flattens ordinary sources and appends the selected temporary events. */
final class HighflyPremiumPlaylistMerger {
    private HighflyPremiumPlaylistMerger() {}

    static List<Channel> merge(
            Map<Integer, Playlist> playlists,
            int eventSourcePosition
    ) {
        List<Channel> channels = new ArrayList<>();
        Playlist eventPlaylist = playlists == null
                ? null
                : playlists.get(eventSourcePosition);

        if (playlists != null) {
            List<Map.Entry<Integer, Playlist>> ordered = new ArrayList<>(playlists.entrySet());
            Collections.sort(ordered, (left, right) ->
                    Integer.compare(left.getKey(), right.getKey()));
            for (Map.Entry<Integer, Playlist> entry : ordered) {
                if (entry.getKey() == eventSourcePosition) continue;
                Playlist playlist = entry.getValue();
                if (playlist != null) channels.addAll(playlist.getChannels());
            }
        }

        if (eventPlaylist != null && !eventPlaylist.getChannels().isEmpty()) {
            channels.addAll(eventPlaylist.getChannels());
        }
        return channels;
    }
}

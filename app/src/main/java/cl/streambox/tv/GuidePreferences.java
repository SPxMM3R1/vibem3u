package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** Small persistent state for the guide only; it never stores playback URLs. */
final class GuidePreferences {
    private static final String PREFS = "epg_guide";
    private static final String FAVORITES = "favorites";

    private final SharedPreferences preferences;

    GuidePreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isFavorite(Channel channel) {
        if (channel == null) return false;
        return favorites().contains(PlaybackPreferences.channelIdentity(channel));
    }

    boolean toggleFavorite(Channel channel) {
        if (channel == null) return false;
        Set<String> values = favorites();
        String identity = PlaybackPreferences.channelIdentity(channel);
        boolean added = values.add(identity);
        if (!added) values.remove(identity);
        preferences.edit().putStringSet(FAVORITES, values).apply();
        return added;
    }

    private Set<String> favorites() {
        return new LinkedHashSet<>(preferences.getStringSet(FAVORITES, new LinkedHashSet<>()));
    }
}

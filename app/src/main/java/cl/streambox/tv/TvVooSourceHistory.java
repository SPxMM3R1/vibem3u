package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small alias-only learning store for TvVoo.
 *
 * <p>The resolver publishes short-lived URLs and tokens. They never enter
 * this store. Only the bounded, allow-listed alias and a small score are
 * persisted so a later resolve can try a recently stable alias first.</p>
 */
final class TvVooSourceHistory {
    private static final String PREFS = "tvvoo_source_history";
    private static final String ENTRY_PREFIX = "entry_";
    private static final int MAX_CHANNELS = 64;
    private static final int MAX_ALIASES_PER_CHANNEL = 8;
    private static final int MAX_SCORE = 12;
    private static final int MIN_SCORE = -6;
    /** A playback failure must overcome the +2 HLS-validation reward. */
    static final int PLAYBACK_FAILURE_PENALTY = -3;
    static final long FAILURE_DECAY_INTERVAL_MS = 15L * 60L * 1_000L;
    static final long FAILURE_TTL_MS = 60L * 60L * 1_000L;

    /** The activity installs its context-backed store before constructing the registry. */
    private static volatile TvVooSourceHistory active;

    private final SharedPreferences preferences;
    private final Clock clock;

    TvVooSourceHistory(Context context) {
        this(context, System::currentTimeMillis);
    }

    TvVooSourceHistory(Context context, Clock clock) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.clock = clock == null ? System::currentTimeMillis : clock;
        active = this;
    }

    interface Clock {
        long now();
    }

    static List<String> orderAliases(String channelIdentity, List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) return Collections.emptyList();
        TvVooSourceHistory store = active;
        if (store == null) return new ArrayList<>(aliases);
        return store.order(channelIdentity, aliases);
    }

    static void recordSuccess(String channelIdentity, String alias) {
        TvVooSourceHistory store = active;
        if (store != null) store.update(channelIdentity, alias, 2);
    }

    static void recordFailure(String channelIdentity, String alias) {
        // A single outage must not permanently evict an alias that may recover.
        TvVooSourceHistory store = active;
        if (store != null) store.update(channelIdentity, alias, -1);
    }

    /**
     * Records a source that passed HLS validation but then failed at playback.
     * This is stronger than an endpoint/validation failure: the next resolve
     * should prefer another known alias, while keeping this one eligible for
     * later recovery through the normal score decay.
     */
    static void recordPlaybackFailure(String channelIdentity, String alias) {
        TvVooSourceHistory store = active;
        if (store != null) store.update(channelIdentity, alias, PLAYBACK_FAILURE_PENALTY);
    }

    static boolean isSafeAlias(String alias) {
        if (alias == null || alias.length() > 256) return false;
        String value = alias.trim();
        if (!value.regionMatches(true, 0, "vavoo_", 0, 6)
                || value.length() <= 6) return false;
        for (int index = 6; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f
                    || character == '/' || character == '\\'
                    || character == '?' || character == '#'
                    || character == '&' || character == '=') {
                return false;
            }
        }
        return true;
    }

    /** Canonical key form; the resolver still receives the caller's alias. */
    static String canonicalAlias(String alias) {
        if (!isSafeAlias(alias)) return "";
        String value = alias.trim();
        String suffix = value.substring(6);
        String decoded;
        try {
            // URLDecoder treats '+' as a form-encoded space. Escape it first:
            // TvVoo aliases use '+' literally in some catalogue entries.
            decoded = URLDecoder.decode(
                    suffix.replace("+", "%2B"),
                    StandardCharsets.UTF_8.name()
            );
        } catch (Exception ignored) {
            decoded = suffix;
        }
        try {
            return "vavoo_" + URLEncoder.encode(
                    decoded,
                    StandardCharsets.UTF_8.name()
            ).replace("+", "%20");
        } catch (Exception impossible) {
            return "";
        }
    }

    private static String canonicalKey(String alias) {
        String canonical = canonicalAlias(alias);
        return canonical.isEmpty() ? (alias == null ? "" : alias.trim()) : canonical;
    }

    private List<String> order(String channelIdentity, List<String> aliases) {
        String channelKey = key(channelIdentity);
        Map<String, Entry> known = entries(channelKey);
        long now = clock.now();
        List<String> result = new ArrayList<>(aliases.size());
        for (String alias : aliases) {
            // History is an ordering hint, never a source filter.
            if (alias != null && !alias.trim().isEmpty()) result.add(alias);
        }
        result.sort((left, right) -> {
            Entry a = known.get(canonicalKey(left));
            Entry b = known.get(canonicalKey(right));
            int scoreA = a == null ? 0 : a.effectiveScore(now);
            int scoreB = b == null ? 0 : b.effectiveScore(now);
            if (scoreA != scoreB) return Integer.compare(scoreB, scoreA);
            long usedA = a == null ? 0L : a.lastUsed;
            long usedB = b == null ? 0L : b.lastUsed;
            return Long.compare(usedB, usedA);
        });
        return result;
    }

    private void update(String channelIdentity, String alias, int delta) {
        if (channelIdentity == null || channelIdentity.trim().isEmpty()
                || !isSafeAlias(alias)) return;
        String channelKey = key(channelIdentity);
        String canonical = canonicalAlias(alias);
        if (canonical.isEmpty()) return;
        String aliasKey = key(canonical);
        String preferenceKey = ENTRY_PREFIX + channelKey + "_" + aliasKey;
        String previous = preferences.getString(preferenceKey, "");
        Entry entry = Entry.parse(alias, previous);
        if (entry == null) entry = new Entry(alias, 0, 0L);
        long now = clock.now();
        int nextScore = Math.max(MIN_SCORE, Math.min(
                MAX_SCORE,
                entry.effectiveScore(now) + delta
        ));
        String value = alias + "|" + nextScore + "|" + now;
        preferences.edit().putString(preferenceKey, value).apply();
        trimChannel(channelKey);
        trimGlobalChannels();
    }

    private Map<String, Entry> entries(String channelKey) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> preference : preferences.getAll().entrySet()) {
            String key = preference.getKey();
            if (!key.startsWith(ENTRY_PREFIX + channelKey + "_")) continue;
            if (!(preference.getValue() instanceof String)) continue;
            Entry parsed = Entry.parse(null, (String) preference.getValue());
            if (parsed != null && isSafeAlias(parsed.alias)) {
                result.put(canonicalKey(parsed.alias), parsed);
            }
        }
        return result;
    }

    private void trimChannel(String channelKey) {
        Map<String, Entry> values = entries(channelKey);
        if (values.size() <= MAX_ALIASES_PER_CHANNEL) return;
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(values.entrySet());
        sorted.sort(Comparator.comparingLong(entry -> entry.getValue().lastUsed));
        SharedPreferences.Editor editor = preferences.edit();
        int remove = values.size() - MAX_ALIASES_PER_CHANNEL;
        for (int index = 0; index < remove; index++) {
            editor.remove(ENTRY_PREFIX + channelKey + "_" + key(sorted.get(index).getKey()));
        }
        editor.apply();
    }

    private void trimGlobalChannels() {
        Map<String, Long> latestByChannel = new LinkedHashMap<>();
        for (Map.Entry<String, ?> preference : preferences.getAll().entrySet()) {
            String key = preference.getKey();
            if (!key.startsWith(ENTRY_PREFIX) || !(preference.getValue() instanceof String)) continue;
            int separator = key.indexOf('_', ENTRY_PREFIX.length());
            if (separator < 0) continue;
            String channelKey = key.substring(ENTRY_PREFIX.length(), separator);
            Entry entry = Entry.parse(null, (String) preference.getValue());
            if (entry == null) continue;
            Long previous = latestByChannel.get(channelKey);
            if (previous == null || entry.lastUsed > previous) latestByChannel.put(channelKey, entry.lastUsed);
        }
        if (latestByChannel.size() <= MAX_CHANNELS) return;
        List<Map.Entry<String, Long>> channels = new ArrayList<>(latestByChannel.entrySet());
        channels.sort(Map.Entry.comparingByValue());
        SharedPreferences.Editor editor = preferences.edit();
        int remove = latestByChannel.size() - MAX_CHANNELS;
        for (int index = 0; index < remove; index++) {
            String prefix = ENTRY_PREFIX + channels.get(index).getKey() + "_";
            for (String preferenceKey : preferences.getAll().keySet()) {
                if (preferenceKey.startsWith(prefix)) editor.remove(preferenceKey);
            }
        }
        editor.apply();
    }

    private static String key(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static final class Entry {
        final String alias;
        final int score;
        final long lastUsed;

        Entry(String alias, int score, long lastUsed) {
            this.alias = alias;
            this.score = score;
            this.lastUsed = lastUsed;
        }

        static Entry parse(String fallbackAlias, String value) {
            if (value == null || value.trim().isEmpty()) {
                return fallbackAlias == null ? null : new Entry(fallbackAlias, 0, 0L);
            }
            // Alias values may contain the raw `|group:country` suffix. Parse
            // from the two rightmost separators for backwards compatibility.
            int lastSeparator = value.lastIndexOf('|');
            int previousSeparator = lastSeparator <= 0
                    ? -1
                    : value.lastIndexOf('|', lastSeparator - 1);
            if (previousSeparator <= 0 || lastSeparator <= previousSeparator) return null;
            String alias = value.substring(0, previousSeparator);
            try {
                return new Entry(
                        alias,
                        Integer.parseInt(value.substring(previousSeparator + 1, lastSeparator)),
                        Long.parseLong(value.substring(lastSeparator + 1))
                );
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        int effectiveScore(long now) {
            if (score >= 0 || lastUsed <= 0L || now <= lastUsed) return score;
            long elapsed = now - lastUsed;
            if (elapsed >= FAILURE_TTL_MS) return 0;
            long steps = elapsed / FAILURE_DECAY_INTERVAL_MS;
            return Math.min(0, score + (int) Math.min(Integer.MAX_VALUE, steps));
        }
    }
}

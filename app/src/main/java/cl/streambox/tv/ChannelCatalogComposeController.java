package cl.streambox.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Non-visual controller for the Compose channel catalogue.
 *
 * <p>The legacy View catalogue remains available as the safe fallback, but
 * all catalogue mutations are kept here so the Compose screen does not own a
 * second identity or persistence model.</p>
 */
public final class ChannelCatalogComposeController {
    private static final int SOURCE_M3U = 0;
    private static final int SOURCE_TVVOO = 1;
    private static final int SOURCE_HIGHFLY = 2;

    private final Context context;
    private final TvVooSelectionStore tvvooSelectionStore;
    private final HighflySelectionStore highflySelectionStore;
    private final HiddenChannelStore hiddenChannelStore;
    private final ChannelCatalogOrderStore orderStore;
    private final PlaylistRepository playlistRepository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable onChanged = () -> {};

    public ChannelCatalogComposeController(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        this.context = context.getApplicationContext();
        tvvooSelectionStore = new TvVooSelectionStore(this.context);
        highflySelectionStore = new HighflySelectionStore(this.context);
        hiddenChannelStore = new HiddenChannelStore(this.context);
        orderStore = new ChannelCatalogOrderStore(this.context);
        playlistRepository = new PlaylistRepository(this.context);
    }

    public void setOnChangedListener(Runnable listener) {
        onChanged = listener == null ? () -> {} : listener;
    }

    public List<Item> getItems() {
        List<ManagedChannel> channels = loadChannels();
        List<Item> result = new ArrayList<>(channels.size());
        for (ManagedChannel channel : channels) {
            result.add(new Item(
                    channel.key,
                    channel.name,
                    channel.group,
                    channel.category,
                    channel.sourceLabel(context),
                    hiddenChannelStore.isHidden(channel.toChannel())
            ));
        }
        return Collections.unmodifiableList(result);
    }

    public void refresh() {
        notifyChanged();
    }

    public void move(String key, int delta) {
        List<ManagedChannel> channels = loadChannels();
        int index = indexOf(channels, key);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= channels.size()) return;
        Collections.swap(channels, index, target);
        persist(channels);
    }

    public void toggleVisibility(String key) {
        List<ManagedChannel> channels = loadChannels();
        int index = indexOf(channels, key);
        if (index < 0) return;
        ManagedChannel channel = channels.get(index);
        boolean hidden = hiddenChannelStore.isHidden(channel.toChannel());
        hiddenChannelStore.setHidden(channel.toChannel(), !hidden);
        notifyChanged();
    }

    public void remove(String key) {
        List<ManagedChannel> channels = loadChannels();
        int index = indexOf(channels, key);
        if (index < 0) return;
        ManagedChannel removed = channels.remove(index);
        hiddenChannelStore.setHidden(removed.toChannel(), false);
        if (removed.source == SOURCE_M3U) orderStore.markRemoved(removed.key);
        persist(channels);
    }

    public void publish(PublishCallback callback) {
        GitHubSelectionPublisher.publishAsync(context, true, result -> mainHandler.post(() -> {
            if (callback != null) callback.onResult(
                    result == null ? "" : result.getMessage(),
                    result != null && result.isPublished()
            );
        }));
    }

    private List<ManagedChannel> loadChannels() {
        Map<String, ManagedChannel> unique = new LinkedHashMap<>();
        loadCachedM3uChannels(unique);
        for (TvVooCatalogChannel channel : tvvooSelectionStore.getSelectedCatalogChannels()) {
            ManagedChannel managed = ManagedChannel.tvvoo(channel);
            if (!orderStore.isRemoved(managed.key)) unique.putIfAbsent(managed.key, managed);
        }
        for (HighflyCatalogChannel channel : highflySelectionStore.getSelectedCatalogChannels()) {
            ManagedChannel managed = ManagedChannel.highfly(channel);
            if (!orderStore.isRemoved(managed.key)) unique.putIfAbsent(managed.key, managed);
        }

        Map<String, ManagedChannel> remaining = new LinkedHashMap<>(unique);
        List<ManagedChannel> result = new ArrayList<>();
        for (String key : orderStore.getOrder()) {
            ManagedChannel channel = remaining.remove(key);
            if (channel != null) result.add(channel);
        }
        result.addAll(remaining.values());
        return result;
    }

    private void loadCachedM3uChannels(Map<String, ManagedChannel> unique) {
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE
        );
        addCachedPlaylist(unique, preferences,
                SettingsActivity.KEY_PLAYLIST_URL,
                SettingsActivity.KEY_PLAYLIST_ENABLED,
                true);
        addCachedPlaylist(unique, preferences,
                SettingsActivity.KEY_PLAYLIST_URL_2,
                SettingsActivity.KEY_PLAYLIST_ENABLED_2,
                false);
    }

    private void addCachedPlaylist(
            Map<String, ManagedChannel> unique,
            SharedPreferences preferences,
            String urlKey,
            String enabledKey,
            boolean defaultEnabled
    ) {
        if (!preferences.getBoolean(enabledKey, defaultEnabled)) return;
        String url = preferences.getString(urlKey, "");
        if (AppStrings.isBlank(url)) return;
        try {
            Playlist playlist = playlistRepository.loadCached(url);
            if (playlist == null) return;
            for (Channel channel : playlist.getChannels()) {
                if (TvVooChannelMerge.isTvVoo(channel)
                        || HighflyChannelMerge.isHighfly(channel)) continue;
                ManagedChannel managed = ManagedChannel.m3u(channel);
                if (orderStore.isRemoved(managed.key)) continue;
                unique.putIfAbsent(managed.key, managed);
            }
        } catch (Exception ignored) {
            // A missing cache must not prevent the Compose screen from opening.
        }
    }

    private void persist(List<ManagedChannel> channels) {
        List<String> globalOrder = new ArrayList<>();
        List<TvVooCatalogChannel> tvvoo = new ArrayList<>();
        List<HighflyCatalogChannel> highfly = new ArrayList<>();
        for (ManagedChannel channel : channels) {
            globalOrder.add(channel.key);
            if (channel.source == SOURCE_TVVOO) tvvoo.add(channel.tvvoo);
            if (channel.source == SOURCE_HIGHFLY) highfly.add(channel.highfly);
        }
        orderStore.applyOrder(globalOrder);
        tvvooSelectionStore.apply(tvvoo);
        highflySelectionStore.apply(highfly);
        notifyChanged();
    }

    private int indexOf(List<ManagedChannel> channels, String key) {
        if (AppStrings.isBlank(key)) return -1;
        for (int index = 0; index < channels.size(); index++) {
            if (key.equals(channels.get(index).key)) return index;
        }
        return -1;
    }

    private void notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onChanged.run();
        } else {
            mainHandler.post(onChanged);
        }
    }

    public interface PublishCallback {
        void onResult(String message, boolean published);
    }

    public static final class Item {
        private final String key;
        private final String name;
        private final String group;
        private final String category;
        private final String sourceLabel;
        private final boolean hidden;

        private Item(
                String key,
                String name,
                String group,
                String category,
                String sourceLabel,
                boolean hidden
        ) {
            this.key = key;
            this.name = name;
            this.group = group;
            this.category = category;
            this.sourceLabel = sourceLabel;
            this.hidden = hidden;
        }

        public String getKey() { return key; }
        public String getName() { return name; }
        public String getGroup() { return group; }
        public String getCategory() { return category; }
        public String getSourceLabel() { return sourceLabel; }
        public boolean isHidden() { return hidden; }
    }

    private static final class ManagedChannel {
        private final int source;
        private final String key;
        private final String name;
        private final String group;
        private final String category;
        private final Channel m3u;
        private final TvVooCatalogChannel tvvoo;
        private final HighflyCatalogChannel highfly;

        private ManagedChannel(
                int source,
                String key,
                String name,
                String group,
                String category,
                Channel m3u,
                TvVooCatalogChannel tvvoo,
                HighflyCatalogChannel highfly
        ) {
            this.source = source;
            this.key = key;
            this.name = clean(name, "Canal sin nombre");
            this.group = clean(group, "General");
            this.category = category == null ? "" : category.trim();
            this.m3u = m3u;
            this.tvvoo = tvvoo;
            this.highfly = highfly;
        }

        static ManagedChannel m3u(Channel channel) {
            return new ManagedChannel(
                    SOURCE_M3U,
                    ChannelCatalogOrderStore.keyFor(channel),
                    channel.getName(),
                    channel.getGroup(),
                    channel.getAttributes().get("x-category"),
                    channel,
                    null,
                    null
            );
        }

        static ManagedChannel tvvoo(TvVooCatalogChannel channel) {
            return new ManagedChannel(
                    SOURCE_TVVOO,
                    ChannelCatalogOrderStore.keyFor(channel),
                    channel.getName(),
                    channel.getGroup(),
                    channel.getCategory(),
                    null,
                    channel,
                    null
            );
        }

        static ManagedChannel highfly(HighflyCatalogChannel channel) {
            return new ManagedChannel(
                    SOURCE_HIGHFLY,
                    ChannelCatalogOrderStore.keyFor(channel),
                    channel.getName(),
                    channel.getGroup(),
                    channel.getCategory(),
                    null,
                    null,
                    channel
            );
        }

        String sourceLabel(Context context) {
            if (source == SOURCE_TVVOO) return context.getString(R.string.channels_provider_tvvoo);
            if (source == SOURCE_HIGHFLY) return context.getString(R.string.channels_provider_highfly);
            return context.getString(R.string.channels_provider_m3u);
        }

        Channel toChannel() {
            if (source == SOURCE_M3U) return m3u;
            return source == SOURCE_TVVOO ? tvvoo.toChannel() : highfly.toChannel();
        }
    }

    private static String clean(String value, String fallback) {
        return AppStrings.isBlank(value) ? fallback : value.trim();
    }
}

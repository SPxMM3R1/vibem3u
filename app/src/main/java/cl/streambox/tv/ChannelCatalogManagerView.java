package cl.streambox.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android TV channel catalogue manager embedded in the settings Canales tab.
 *
 * <p>The list is intentionally unified: cached Lista M3U rows are followed by
 * the selected TvVoo and Highfly rows only when they are not already present.
 * The global order is persisted separately from provider selections, so a
 * dynamic channel can be moved above or below a public M3U channel and that
 * order is used by playback.</p>
 */
final class ChannelCatalogManagerView extends LinearLayout {
    private static final String PREF_USE_MODERN_UI = "channels_catalog_ui_v2";
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
    private final List<ManagedChannel> channels = new ArrayList<>();

    private int selectedIndex = -1;
    private Runnable onCatalogChanged = () -> {};

    private Button addTvvooButton;
    private Button addHighflyButton;
    private Button moveUpButton;
    private Button moveDownButton;
    private Button removeButton;
    private Button visibilityButton;
    private Button publishButton;
    private TextView summary;
    private TextView status;
    private LinearLayout channelContainer;
    private boolean modernUi;

    ChannelCatalogManagerView(Context context) {
        super(context);
        if (context == null) throw new IllegalArgumentException("context");
        this.context = context;
        tvvooSelectionStore = new TvVooSelectionStore(context);
        highflySelectionStore = new HighflySelectionStore(context);
        hiddenChannelStore = new HiddenChannelStore(context);
        orderStore = new ChannelCatalogOrderStore(context);
        playlistRepository = new PlaylistRepository(context.getApplicationContext());
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(8), dp(16), dp(10));
        rebuildLayoutSafely();
    }

    static boolean isModernUiEnabled(Context context) {
        return context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_USE_MODERN_UI, true);
    }

    static void setModernUiEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_USE_MODERN_UI, enabled)
                .apply();
    }

    void reloadLayoutSafely() {
        removeAllViews();
        rebuildLayoutSafely();
    }

    private void rebuildLayoutSafely() {
        try {
            buildLayout();
            render(false);
        } catch (RuntimeException error) {
            if (!isModernUiEnabled(context)) throw error;
            setModernUiEnabled(context, false);
            removeAllViews();
            buildLegacyLayout();
            render(false);
            if (status != null) setStatus(getString(R.string.channels_ui_fallback));
        }
    }

    View getFirstFocus() {
        return addTvvooButton;
    }

    void setFooterFocus(View footer) {
        if (footer != null && publishButton != null) {
            publishButton.setNextFocusDownId(footer.getId());
        }
    }

    void setOnCatalogChangedListener(Runnable listener) {
        onCatalogChanged = listener == null ? () -> {} : listener;
    }

    /** Refreshes the cached M3U rows and both dynamic selections. */
    void refresh() {
        render(false);
    }

    private void buildLayout() {
        if (isModernUiEnabled(context)) {
            buildModernLayout();
        } else {
            buildLegacyLayout();
        }
    }

    private void buildLegacyLayout() {
        modernUi = false;
        TextView heading = textView(getString(R.string.channels_heading), true);
        heading.setTextColor(context.getResources().getColor(R.color.cyan));
        addView(heading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        ));

        TextView description = textView(getString(R.string.channels_description), false);
        description.setTextColor(context.getResources().getColor(R.color.muted));
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        descriptionParams.topMargin = dp(4);
        addView(description, descriptionParams);

        LinearLayout addRow = horizontalRow();
        addTvvooButton = sourceButton(R.string.channels_add_tvvoo);
        addHighflyButton = sourceButton(R.string.channels_add_highfly);
        addRow.addView(addTvvooButton, weightedParams(4));
        addRow.addView(addHighflyButton, weightedParams(0));
        LinearLayout.LayoutParams addRowParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(40)
        );
        addRowParams.topMargin = dp(8);
        addView(addRow, addRowParams);
        addTvvooButton.setOnClickListener(view -> openProviderCatalogue(SOURCE_TVVOO));
        addHighflyButton.setOnClickListener(view -> openProviderCatalogue(SOURCE_HIGHFLY));

        summary = textView("", false);
        summary.setTextColor(context.getResources().getColor(R.color.white));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(5);
        addView(summary, summaryParams);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setNestedScrollingEnabled(false);
        channelContainer = new LinearLayout(context);
        channelContainer.setOrientation(VERTICAL);
        scrollView.addView(channelContainer, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(190)
        );
        scrollParams.topMargin = dp(5);
        addView(scrollView, scrollParams);

        LinearLayout movementRow = horizontalRow();
        moveUpButton = actionButton(getString(R.string.channels_move_up));
        moveDownButton = actionButton(getString(R.string.channels_move_down));
        removeButton = actionButton(getString(R.string.channels_remove));
        visibilityButton = actionButton(getString(R.string.channels_hide));
        movementRow.addView(moveUpButton, weightedParams(4));
        movementRow.addView(moveDownButton, weightedParams(4));
        movementRow.addView(removeButton, weightedParams(4));
        movementRow.addView(visibilityButton, weightedParams(0));
        LinearLayout.LayoutParams movementParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(38)
        );
        movementParams.topMargin = dp(6);
        addView(movementRow, movementParams);

        moveUpButton.setOnClickListener(view -> moveSelected(-1));
        moveDownButton.setOnClickListener(view -> moveSelected(1));
        removeButton.setOnClickListener(view -> confirmRemoveSelected());
        visibilityButton.setOnClickListener(view -> toggleSelectedVisibility());

        publishButton = actionButton(getString(R.string.channels_publish));
        LinearLayout.LayoutParams publishParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(40)
        );
        publishParams.topMargin = dp(6);
        addView(publishButton, publishParams);
        publishButton.setOnClickListener(view -> publishCatalog());

        status = textView("", false);
        status.setTextColor(context.getResources().getColor(R.color.muted));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(4);
        addView(status, statusParams);
    }

    private void buildModernLayout() {
        modernUi = true;
        setPadding(dp(16), dp(8), dp(16), dp(10));

        TextView heading = textView(getString(R.string.channels_heading), true);
        heading.setTextColor(context.getResources().getColor(R.color.cyan));
        addView(heading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        ));

        TextView description = textView(getString(R.string.channels_description_short), false);
        description.setTextColor(context.getResources().getColor(R.color.muted));
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        descriptionParams.topMargin = dp(5);
        addView(description, descriptionParams);

        LinearLayout addRow = horizontalRow();
        addTvvooButton = sourceButton(R.string.channels_add_tvvoo);
        addHighflyButton = sourceButton(R.string.channels_add_highfly);
        addRow.addView(addTvvooButton, weightedParams(8));
        addRow.addView(addHighflyButton, weightedParams(0));
        LinearLayout.LayoutParams addRowParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(46)
        );
        addRowParams.topMargin = dp(10);
        addView(addRow, addRowParams);
        addTvvooButton.setOnClickListener(view -> openProviderCatalogue(SOURCE_TVVOO));
        addHighflyButton.setOnClickListener(view -> openProviderCatalogue(SOURCE_HIGHFLY));

        summary = textView("", false);
        summary.setTextColor(context.getResources().getColor(R.color.white));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(8);
        addView(summary, summaryParams);

        LinearLayout editor = horizontalRow();
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setNestedScrollingEnabled(false);
        channelContainer = new LinearLayout(context);
        channelContainer.setOrientation(VERTICAL);
        scrollView.addView(channelContainer, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                0,
                dp(286),
                1.55f
        );
        listParams.topMargin = dp(8);
        editor.addView(scrollView, listParams);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(VERTICAL);
        actions.setPadding(dp(12), dp(10), dp(12), dp(10));
        actions.setBackgroundResource(R.drawable.settings_section_card);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                0,
                dp(286),
                0.85f
        );
        actionsParams.leftMargin = dp(10);
        actionsParams.topMargin = dp(8);
        editor.addView(actions, actionsParams);

        TextView actionsTitle = textView(getString(R.string.channels_actions_title), true);
        actionsTitle.setTextColor(context.getResources().getColor(R.color.cyan));
        actions.addView(actionsTitle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        ));

        TextView actionsHint = textView(getString(R.string.channels_action_hint), false);
        actionsHint.setTextColor(context.getResources().getColor(R.color.muted));
        LinearLayout.LayoutParams actionsHintParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        actionsHintParams.topMargin = dp(5);
        actions.addView(actionsHint, actionsHintParams);

        moveUpButton = actionButton(getString(R.string.channels_move_up));
        moveDownButton = actionButton(getString(R.string.channels_move_down));
        visibilityButton = actionButton(getString(R.string.channels_hide));
        removeButton = actionButton(getString(R.string.channels_remove));
        addAction(actions, moveUpButton, 10);
        addAction(actions, moveDownButton, 6);
        addAction(actions, visibilityButton, 6);
        addAction(actions, removeButton, 6);
        moveUpButton.setOnClickListener(view -> moveSelected(-1));
        moveDownButton.setOnClickListener(view -> moveSelected(1));
        visibilityButton.setOnClickListener(view -> toggleSelectedVisibility());
        removeButton.setOnClickListener(view -> confirmRemoveSelected());

        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(294)
        );
        addView(editor, editorParams);

        publishButton = actionButton(getString(R.string.channels_publish));
        LinearLayout.LayoutParams publishParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(46)
        );
        publishParams.topMargin = dp(10);
        addView(publishButton, publishParams);
        publishButton.setOnClickListener(view -> publishCatalog());
        removeButton.setNextFocusDownId(publishButton.getId());

        status = textView("", false);
        status.setTextColor(context.getResources().getColor(R.color.muted));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(5);
        addView(status, statusParams);
    }

    private void addAction(LinearLayout parent, Button button, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(42)
        );
        params.topMargin = dp(topMargin);
        parent.addView(button, params);
    }

    private void openProviderCatalogue(int source) {
        Intent intent = new Intent(
                context,
                source == SOURCE_TVVOO
                        ? TvVooCatalogActivity.class
                        : HighflyCatalogActivity.class
        );
        context.startActivity(intent);
    }

    private void render(boolean requestSelectedFocus) {
        String previousKey = selectedKey();
        loadChannels();
        selectedIndex = indexOf(previousKey);
        if (selectedIndex < 0 && !channels.isEmpty()) selectedIndex = 0;

        channelContainer.removeAllViews();
        View focusTarget = null;
        View previous = addHighflyButton;
        if (channels.isEmpty()) {
            TextView empty = textView(getString(R.string.channels_empty), false);
            empty.setTextColor(context.getResources().getColor(R.color.muted));
            channelContainer.addView(empty, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
            ));
            addHighflyButton.setNextFocusDownId(publishButton.getId());
        } else {
            for (int index = 0; index < channels.size(); index++) {
                final int rowIndex = index;
                ManagedChannel channel = channels.get(index);
                View row = modernUi
                        ? modernChannelRow(index, channel)
                        : actionButton(rowLabel(index, channel));
                if (!modernUi) {
                    Button legacyRow = (Button) row;
                    legacyRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    legacyRow.setPadding(dp(10), 0, dp(10), 0);
                    legacyRow.setMinHeight(0);
                }
                row.setOnClickListener(view -> selectRow(rowIndex));
                row.setOnLongClickListener(view -> {
                    selectRow(rowIndex);
                    showLongPressActions();
                    return true;
                });
                row.setOnFocusChangeListener((view, hasFocus) -> {
                    if (hasFocus) selectRow(rowIndex);
                });
                row.setNextFocusUpId(previous.getId());
                previous.setNextFocusDownId(row.getId());
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        dp(modernUi ? 60 : 51)
                );
                rowParams.bottomMargin = dp(modernUi ? 6 : 4);
                channelContainer.addView(row, rowParams);
                if (index == selectedIndex) focusTarget = row;
                previous = row;
            }
            previous.setNextFocusDownId(moveUpButton.getId());
        }
        updateSummary();
        updateActions();
        if (requestSelectedFocus && focusTarget != null) {
            focusTarget.post(focusTarget::requestFocus);
        }
    }

    private View modernChannelRow(int index, ManagedChannel channel) {
        boolean hidden = hiddenChannelStore.isHidden(channel.toChannel());
        LinearLayout row = new LinearLayout(context);
        row.setId(View.generateViewId());
        row.setOrientation(VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setLongClickable(true);
        row.setBackgroundResource(R.drawable.channel_catalog_row);
        row.setPadding(dp(14), dp(5), dp(14), dp(5));
        row.setContentDescription(rowLabel(index, channel));

        TextView title = new TextView(context);
        title.setText((index + 1) + ". " + channel.name);
        title.setTextColor(context.getResources().getColor(
                hidden ? R.color.muted : R.color.white
        ));
        title.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.settings_control_text_size)
        );
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        ));

        String details = channel.sourceLabel(context) + " · " + channel.group;
        if (!AppStrings.isBlank(channel.category)
                && !channel.category.equalsIgnoreCase(channel.group)) {
            details += " · " + channel.category;
        }
        if (hidden) details += getString(R.string.channels_hidden_suffix);
        TextView metadata = textView(details, false);
        metadata.setTextColor(context.getResources().getColor(
                hidden ? R.color.amber : R.color.muted
        ));
        metadata.setMaxLines(1);
        metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        metadataParams.topMargin = dp(3);
        row.addView(metadata, metadataParams);
        return row;
    }

    private void loadChannels() {
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
        channels.clear();
        for (String key : orderStore.getOrder()) {
            ManagedChannel channel = remaining.remove(key);
            if (channel != null) channels.add(channel);
        }
        channels.addAll(remaining.values());
    }

    private void loadCachedM3uChannels(Map<String, ManagedChannel> unique) {
        SharedPreferences preferences = context.getApplicationContext().getSharedPreferences(
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
                // Dynamic rows are app-only after the Lista M3U publication
                // change. Ignore stale cached copies here as well.
                if (TvVooChannelMerge.isTvVoo(channel)
                        || HighflyChannelMerge.isHighfly(channel)) continue;
                ManagedChannel managed = ManagedChannel.m3u(channel);
                if (orderStore.isRemoved(managed.key)) continue;
                unique.putIfAbsent(managed.key, managed);
            }
        } catch (Exception ignored) {
            // The playback screen will report the source error. Settings must
            // remain navigable when a cache is absent or stale.
        }
    }

    private String rowLabel(int index, ManagedChannel channel) {
        String details = channel.sourceLabel(context) + " · " + channel.group;
        if (!AppStrings.isBlank(channel.category)
                && !channel.category.equalsIgnoreCase(channel.group)) {
            details += " · " + channel.category;
        }
        String hidden = hiddenChannelStore.isHidden(channel.toChannel())
                ? getString(R.string.channels_hidden_suffix)
                : "";
        return getString(
                R.string.channels_row_format,
                index + 1,
                channel.name,
                details,
                hidden
        );
    }

    private void selectRow(int index) {
        if (index < 0 || index >= channels.size()) return;
        selectedIndex = index;
        updateSummary();
        updateActions();
    }

    private void moveSelected(int delta) {
        if (selectedIndex < 0 || selectedIndex >= channels.size()) return;
        int target = selectedIndex + delta;
        if (target < 0 || target >= channels.size()) return;
        java.util.Collections.swap(channels, selectedIndex, target);
        selectedIndex = target;
        persistCurrentOrder();
        render(true);
        setStatus(getString(R.string.channels_local_saved));
    }

    private void showLongPressActions() {
        if (selectedIndex < 0 || selectedIndex >= channels.size()) return;
        ManagedChannel channel = channels.get(selectedIndex);
        String visibility = hiddenChannelStore.isHidden(channel.toChannel())
                ? getString(R.string.channels_unhide)
                : getString(R.string.channels_hide);
        CharSequence[] actions = new CharSequence[]{
                visibility,
                getString(R.string.channels_remove)
        };
        new AlertDialog.Builder(context)
                .setTitle(R.string.channels_actions_title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) toggleSelectedVisibility();
                    else if (which == 1) confirmRemoveSelected();
                })
                .setNegativeButton(R.string.channels_cancel, null)
                .show();
    }

    private void confirmRemoveSelected() {
        if (selectedIndex < 0 || selectedIndex >= channels.size()) return;
        ManagedChannel channel = channels.get(selectedIndex);
        new AlertDialog.Builder(context)
                .setTitle(R.string.channels_delete_title)
                .setMessage(getString(
                        R.string.channels_delete_message,
                        channel.name,
                        channel.sourceLabel(context)
                ))
                .setNegativeButton(R.string.channels_cancel, null)
                .setPositiveButton(R.string.channels_delete_confirm,
                        (dialog, which) -> removeSelected())
                .show();
    }

    private void removeSelected() {
        if (selectedIndex < 0 || selectedIndex >= channels.size()) return;
        ManagedChannel removed = channels.remove(selectedIndex);
        hiddenChannelStore.setHidden(removed.toChannel(), false);
        if (removed.source == SOURCE_M3U) orderStore.markRemoved(removed.key);
        if (selectedIndex >= channels.size()) selectedIndex = channels.size() - 1;
        persistCurrentOrder();
        render(true);
        setStatus(getString(R.string.channels_removed_status));
    }

    private void toggleSelectedVisibility() {
        if (selectedIndex < 0 || selectedIndex >= channels.size()) return;
        ManagedChannel channel = channels.get(selectedIndex);
        boolean hidden = hiddenChannelStore.isHidden(channel.toChannel());
        hiddenChannelStore.setHidden(channel.toChannel(), !hidden);
        render(true);
        setStatus(getString(
                hidden ? R.string.channels_unhidden_status : R.string.channels_hidden_status
        ));
        notifyCatalogChanged();
    }

    private void persistCurrentOrder() {
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
        notifyCatalogChanged();
    }

    private void publishCatalog() {
        publishButton.setEnabled(false);
        setStatus(getString(R.string.channels_publish_working));
        GitHubSelectionPublisher.publishAsync(context, true, result -> mainHandler.post(() -> {
            if (publishButton != null) publishButton.setEnabled(true);
            if (result != null) setStatus(result.getMessage());
        }));
    }

    private void updateSummary() {
        String selected = selectedIndex >= 0 && selectedIndex < channels.size()
                ? " · " + getString(R.string.channels_selected, channels.get(selectedIndex).name)
                : "";
        summary.setText(getString(
                R.string.channels_summary,
                getString(R.string.channels_catalog_all),
                channels.size(),
                selected
        ));
    }

    private void updateActions() {
        boolean hasSelection = selectedIndex >= 0 && selectedIndex < channels.size();
        moveUpButton.setEnabled(hasSelection && selectedIndex > 0);
        moveDownButton.setEnabled(hasSelection && selectedIndex < channels.size() - 1);
        removeButton.setEnabled(hasSelection);
        visibilityButton.setEnabled(hasSelection);
        if (hasSelection) {
            visibilityButton.setText(hiddenChannelStore.isHidden(
                    channels.get(selectedIndex).toChannel()
            ) ? R.string.channels_unhide : R.string.channels_hide);
        } else {
            visibilityButton.setText(R.string.channels_hide);
        }
    }

    private void notifyCatalogChanged() {
        onCatalogChanged.run();
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value == null ? "" : value);
    }

    private String selectedKey() {
        return selectedIndex >= 0 && selectedIndex < channels.size()
                ? channels.get(selectedIndex).key
                : "";
    }

    private int indexOf(String key) {
        if (AppStrings.isBlank(key)) return -1;
        for (int index = 0; index < channels.size(); index++) {
            if (key.equals(channels.get(index).key)) return index;
        }
        return -1;
    }

    private TextView textView(String value, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(
                        bold ? R.dimen.settings_heading_text_size
                                : R.dimen.settings_body_text_size
                )
        );
        view.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button sourceButton(int labelRes) {
        Button button = actionButton(getString(labelRes));
        button.setBackgroundResource(R.drawable.settings_tab);
        button.setAllCaps(false);
        return button;
    }

    private Button actionButton(String value) {
        Button button = new Button(context);
        button.setId(View.generateViewId());
        button.setBackgroundResource(R.drawable.focus_button);
        button.setFocusable(true);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setGravity(Gravity.CENTER);
        button.setText(value);
        button.setTextColor(context.getResources().getColor(R.color.white));
        button.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.settings_action_text_size)
        );
        return button;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weightedParams(int marginEnd) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LayoutParams.MATCH_PARENT,
                1f
        );
        params.rightMargin = dp(marginEnd);
        return params;
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }

    private String getString(int resourceId) {
        return context.getString(resourceId);
    }

    private String getString(int resourceId, Object... arguments) {
        return context.getString(resourceId, arguments);
    }

    private static String clean(String value, String fallback) {
        return AppStrings.isBlank(value) ? fallback : value.trim();
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
}

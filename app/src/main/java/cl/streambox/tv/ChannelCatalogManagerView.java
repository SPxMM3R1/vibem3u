package cl.streambox.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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
import java.util.Collections;
import java.util.List;

/**
 * Android TV channel catalogue manager embedded in the settings Canales tab.
 *
 * <p>The two providers keep independent order because the publication contract
 * emits one ordered channel array per source. The manager only persists
 * token-free catalogue metadata; playback references are still renewed by the
 * existing resolvers.</p>
 */
final class ChannelCatalogManagerView extends LinearLayout {
    private static final int PROVIDER_TVVOO = 0;
    private static final int PROVIDER_HIGHFLY = 1;

    private final Context context;
    private final TvVooSelectionStore tvvooSelectionStore;
    private final HighflySelectionStore highflySelectionStore;
    private final HiddenChannelStore hiddenChannelStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ManagedChannel> channels = new ArrayList<>();

    private int provider = PROVIDER_TVVOO;
    private int selectedIndex = -1;
    private Runnable onCatalogChanged = () -> {};

    private Button tvvooButton;
    private Button highflyButton;
    private Button addButton;
    private Button moveUpButton;
    private Button moveDownButton;
    private Button removeButton;
    private Button visibilityButton;
    private Button publishButton;
    private TextView summary;
    private TextView status;
    private LinearLayout channelContainer;

    ChannelCatalogManagerView(Context context) {
        super(context);
        if (context == null) throw new IllegalArgumentException("context");
        this.context = context;
        tvvooSelectionStore = new TvVooSelectionStore(context);
        highflySelectionStore = new HighflySelectionStore(context);
        hiddenChannelStore = new HiddenChannelStore(context);
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(4), dp(10), dp(8));
        buildLayout();
        render(false);
    }

    View getFirstFocus() {
        return tvvooButton;
    }

    void setFooterFocus(View footer) {
        if (footer != null && publishButton != null) {
            publishButton.setNextFocusDownId(footer.getId());
        }
    }

    void setOnCatalogChangedListener(Runnable listener) {
        onCatalogChanged = listener == null ? () -> {} : listener;
    }

    /** Refreshes the local selection after returning from a provider catalogue. */
    void refresh() {
        render(false);
    }

    private void buildLayout() {
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

        LinearLayout providerRow = horizontalRow();
        tvvooButton = providerButton(R.string.channels_provider_tvvoo);
        highflyButton = providerButton(R.string.channels_provider_highfly);
        providerRow.addView(tvvooButton, weightedParams(4));
        providerRow.addView(highflyButton, weightedParams(0));
        LinearLayout.LayoutParams providerParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(38)
        );
        providerParams.topMargin = dp(8);
        addView(providerRow, providerParams);

        tvvooButton.setOnClickListener(view -> selectProvider(PROVIDER_TVVOO));
        highflyButton.setOnClickListener(view -> selectProvider(PROVIDER_HIGHFLY));

        addButton = actionButton("");
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(38)
        );
        addParams.topMargin = dp(5);
        addButton.setOnClickListener(view -> openProviderCatalogue());
        addView(addButton, addParams);

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

    private void selectProvider(int nextProvider) {
        if (provider == nextProvider) return;
        provider = nextProvider;
        selectedIndex = -1;
        render(false);
    }

    private void openProviderCatalogue() {
        Intent intent = new Intent(
                context,
                provider == PROVIDER_TVVOO
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
        View previous = addButton;
        if (channels.isEmpty()) {
            TextView empty = textView(getString(R.string.channels_empty), false);
            empty.setTextColor(context.getResources().getColor(R.color.muted));
            channelContainer.addView(empty, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
            ));
            addButton.setNextFocusDownId(publishButton.getId());
        } else {
            for (int index = 0; index < channels.size(); index++) {
                final int rowIndex = index;
                ManagedChannel channel = channels.get(index);
                Button row = actionButton(rowLabel(index, channel));
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), 0, dp(10), 0);
                row.setMinHeight(0);
                row.setOnClickListener(view -> selectRow(rowIndex));
                row.setOnFocusChangeListener((view, hasFocus) -> {
                    if (hasFocus) selectRow(rowIndex);
                });
                row.setNextFocusUpId(previous.getId());
                previous.setNextFocusDownId(row.getId());
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        dp(51)
                );
                rowParams.bottomMargin = dp(4);
                channelContainer.addView(row, rowParams);
                if (index == selectedIndex) focusTarget = row;
                previous = row;
            }
            previous.setNextFocusDownId(moveUpButton.getId());
        }
        updateProviderButtons();
        addButton.setText(provider == PROVIDER_TVVOO
                ? R.string.channels_add_tvvoo
                : R.string.channels_add_highfly);
        updateSummary();
        updateActions();
        if (requestSelectedFocus && focusTarget != null) {
            focusTarget.post(focusTarget::requestFocus);
        }
    }

    private void loadChannels() {
        channels.clear();
        if (provider == PROVIDER_TVVOO) {
            for (TvVooCatalogChannel channel : tvvooSelectionStore.getSelectedCatalogChannels()) {
                channels.add(ManagedChannel.tvvoo(channel));
            }
        } else {
            for (HighflyCatalogChannel channel : highflySelectionStore.getSelectedCatalogChannels()) {
                channels.add(ManagedChannel.highfly(channel));
            }
        }
    }

    private String rowLabel(int index, ManagedChannel channel) {
        String details = channel.group;
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
        Collections.swap(channels, selectedIndex, target);
        selectedIndex = target;
        persistCurrentOrder();
        render(true);
        setStatus(getString(R.string.channels_local_saved));
    }

    private void confirmRemoveSelected() {
        if (selectedIndex < 0 || selectedIndex >= channels.size()) return;
        ManagedChannel channel = channels.get(selectedIndex);
        new AlertDialog.Builder(context)
                .setTitle(R.string.channels_delete_title)
                .setMessage(getString(
                        R.string.channels_delete_message,
                        channel.name,
                        providerLabel()
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
        if (provider == PROVIDER_TVVOO) {
            List<TvVooCatalogChannel> values = new ArrayList<>();
            for (ManagedChannel channel : channels) values.add(channel.tvvoo);
            tvvooSelectionStore.apply(values);
        } else {
            List<HighflyCatalogChannel> values = new ArrayList<>();
            for (ManagedChannel channel : channels) values.add(channel.highfly);
            highflySelectionStore.apply(values);
        }
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

    private void updateProviderButtons() {
        tvvooButton.setSelected(provider == PROVIDER_TVVOO);
        highflyButton.setSelected(provider == PROVIDER_HIGHFLY);
        int selectedColor = context.getResources().getColor(R.color.black);
        int normalColor = context.getResources().getColor(R.color.white);
        tvvooButton.setTextColor(provider == PROVIDER_TVVOO ? selectedColor : normalColor);
        highflyButton.setTextColor(provider == PROVIDER_HIGHFLY ? selectedColor : normalColor);
    }

    private void updateSummary() {
        String selected = selectedIndex >= 0 && selectedIndex < channels.size()
                ? " · " + getString(R.string.channels_selected, channels.get(selectedIndex).name)
                : "";
        summary.setText(getString(
                R.string.channels_summary,
                providerLabel(),
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

    private String providerLabel() {
        return context.getString(provider == PROVIDER_TVVOO
                ? R.string.channels_provider_tvvoo
                : R.string.channels_provider_highfly);
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value == null ? "" : value);
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

    private Button providerButton(int labelRes) {
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

    private static final class ManagedChannel {
        private final String key;
        private final String name;
        private final String group;
        private final String category;
        private final TvVooCatalogChannel tvvoo;
        private final HighflyCatalogChannel highfly;

        private ManagedChannel(
                String key,
                String name,
                String group,
                String category,
                TvVooCatalogChannel tvvoo,
                HighflyCatalogChannel highfly
        ) {
            this.key = key;
            this.name = name;
            this.group = group;
            this.category = category;
            this.tvvoo = tvvoo;
            this.highfly = highfly;
        }

        static ManagedChannel tvvoo(TvVooCatalogChannel channel) {
            return new ManagedChannel(
                    channel.getStableId(),
                    channel.getName(),
                    channel.getGroup(),
                    channel.getCategory(),
                    channel,
                    null
            );
        }

        static ManagedChannel highfly(HighflyCatalogChannel channel) {
            return new ManagedChannel(
                    channel.getResourceId(),
                    channel.getName(),
                    channel.getGroup(),
                    channel.getCategory(),
                    null,
                    channel
            );
        }

        Channel toChannel() {
            return tvvoo != null ? tvvoo.toChannel() : highfly.toChannel();
        }
    }
}

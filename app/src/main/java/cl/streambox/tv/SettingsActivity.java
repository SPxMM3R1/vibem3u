package cl.streambox.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private static final float SETTINGS_PANEL_ASPECT_RATIO = 16f / 10f;
    public static final int TAB_GENERAL = 0;
    public static final int TAB_PLAYBACK = 1;
    public static final int TAB_SOURCE = 2;
    public static final int TAB_RESOLVERS = 3;
    public static final int TAB_INTERFACE = 4;
    public static final int TAB_UPDATES = 5;
    public static final String PREFS = "streambox_settings";
    public static final String KEY_PLAYLIST_URL = "playlist_url";
    public static final String KEY_INVERT_CHANNEL_KEYS = "invert_channel_keys";
    public static final String KEY_NORMALIZE_VOLUME = "normalize_volume";
    public static final String EXTRA_INITIAL_TAB = "initial_tab";
    public static final String EXTRA_CHANNEL_INDEX = "channel_index";
    public static final String EXTRA_CHANNEL_TVG_ID = "channel_tvg_id";
    public static final String EXTRA_CHANNEL_NAME = "channel_name";
    public static final String EXTRA_QUALITY_LABELS = "quality_labels";
    public static final String EXTRA_QUALITY_BITRATES = "quality_bitrates";
    public static final String EXTRA_QUALITY_WIDTHS = "quality_widths";
    public static final String EXTRA_QUALITY_HEIGHTS = "quality_heights";
    public static final String EXTRA_QUALITY_SELECTED_INDEX = "quality_selected_index";
    public static final String EXTRA_QUALITY_AUTOMATIC = "quality_automatic";
    public static final String EXTRA_QUALITY_BITRATE = "quality_bitrate";
    public static final String EXTRA_QUALITY_WIDTH = "quality_width";
    public static final String EXTRA_QUALITY_HEIGHT = "quality_height";
    public static final String EXTRA_SUBTITLES_AVAILABLE = "subtitles_available";
    public static final String EXTRA_SUBTITLES_ENABLED = "subtitles_enabled";
    public static final String EXTRA_RESOLVER_CATALOG_VERSION = "resolver_catalog_version";
    public static final String EXTRA_RESOLVER_IDS = "resolver_ids";
    public static final String EXTRA_RESOLVER_COUNTS = "resolver_counts";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();

    private EditText urlInput;
    private TextView errorText;
    private Switch invertChannelKeys;
    private Switch normalizeVolume;
    private Button updateButton;
    private TextView updateStatus;
    private AppUpdater appUpdater;
    private boolean hasExistingUrl;
    private TextView[] tabs;
    private View[] tabPages;
    private Button saveButton;
    private Button cancelButton;
    private int selectedTabIndex;
    private TextView currentChannelName;
    private LinearLayout qualityOptionsContainer;
    private TextView qualityStatus;
    private Switch subtitlesSwitch;
    private TextView subtitlesStatus;
    private TextView resolverCatalogVersion;
    private Button resolverUpdateButton;
    private TextView resolverUpdateStatus;
    private LinearLayout resolverGroupsContainer;
    private RadioGroup tvVooResolutionModeGroup;
    private RadioButton tvVooModeBoth;
    private RadioButton tvVooModeDirect;
    private RadioButton tvVooModeExternal;
    private ResolverCatalogRepository resolverCatalogRepository;
    private ResolverPreferences resolverPreferences;
    private ResolverCatalog resolverCatalog;
    private final Map<String, Switch> resolverGroupSwitches = new LinkedHashMap<>();
    private final Map<String, Integer> resolverGroupCounts = new LinkedHashMap<>();
    private View playbackFirstFocus;
    private int currentChannelIndex = -1;
    private String currentChannelTvgId = "";
    private boolean hasCurrentChannel;
    private boolean subtitlesAvailable;
    private boolean automaticQuality;
    private int selectedQualityIndex = -1;
    private ArrayList<String> qualityLabels = new ArrayList<>();
    private ArrayList<Integer> qualityBitrates = new ArrayList<>();
    private ArrayList<Integer> qualityWidths = new ArrayList<>();
    private ArrayList<Integer> qualityHeights = new ArrayList<>();
    private Button automaticQualityButton;
    private final List<Button> qualityOptionButtons = new ArrayList<>();
    private final List<Button> qualityFocusButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        enterImmersiveMode();
        fitSettingsPanelToAspectRatio();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existingUrl = prefs.getString(KEY_PLAYLIST_URL, "");
        hasExistingUrl = existingUrl != null && !existingUrl.isBlank();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (hasExistingUrl) finish();
                    });
        }

        urlInput = findViewById(R.id.playlist_url);
        errorText = findViewById(R.id.url_error);
        invertChannelKeys = findViewById(R.id.invert_channel_keys);
        normalizeVolume = findViewById(R.id.normalize_volume);
        updateButton = findViewById(R.id.check_updates_button);
        updateStatus = findViewById(R.id.update_status);
        cancelButton = findViewById(R.id.cancel_button);
        saveButton = findViewById(R.id.save_button);
        currentChannelName = findViewById(R.id.settings_current_channel_name);
        qualityOptionsContainer = findViewById(R.id.settings_quality_options);
        qualityStatus = findViewById(R.id.settings_quality_status);
        subtitlesSwitch = findViewById(R.id.settings_subtitles_switch);
        subtitlesStatus = findViewById(R.id.settings_subtitles_status);
        resolverCatalogVersion = findViewById(R.id.resolver_catalog_version);
        resolverUpdateButton = findViewById(R.id.update_resolvers_button);
        resolverUpdateStatus = findViewById(R.id.resolver_update_status);
        resolverGroupsContainer = findViewById(R.id.resolver_groups_container);
        tvVooResolutionModeGroup = findViewById(R.id.tvvoo_resolution_mode_group);
        tvVooModeBoth = findViewById(R.id.tvvoo_mode_both);
        tvVooModeDirect = findViewById(R.id.tvvoo_mode_direct);
        tvVooModeExternal = findViewById(R.id.tvvoo_mode_external);
        tabs = new TextView[]{
                findViewById(R.id.tab_general),
                findViewById(R.id.tab_playback),
                findViewById(R.id.tab_source),
                findViewById(R.id.tab_resolvers),
                findViewById(R.id.tab_interface),
                findViewById(R.id.tab_updates)
        };
        tabPages = new View[]{
                findViewById(R.id.tab_page_general),
                findViewById(R.id.tab_page_playback),
                findViewById(R.id.tab_page_source),
                findViewById(R.id.tab_page_resolvers),
                findViewById(R.id.tab_page_interface),
                findViewById(R.id.tab_page_updates)
        };

        appUpdater = new AppUpdater(this, updateExecutor, mainHandler);
        resolverCatalogRepository = new ResolverCatalogRepository(this);
        resolverPreferences = new ResolverPreferences(this);
        initializeTvVooResolutionMode();
        urlInput.setText(existingUrl);
        urlInput.setSelection(urlInput.length());
        invertChannelKeys.setChecked(prefs.getBoolean(KEY_INVERT_CHANNEL_KEYS, false));
        normalizeVolume.setChecked(prefs.getBoolean(KEY_NORMALIZE_VOLUME, false));
        TextView versionText = findViewById(R.id.current_version);
        versionText.setText(getString(R.string.current_version, BuildConfig.VERSION_NAME));
        initializeCurrentChannelOptions(getIntent());
        initializeResolverOptions(getIntent());

        cancelButton.setVisibility(hasExistingUrl ? View.VISIBLE : View.GONE);
        cancelButton.setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> save());
        if (BuildConfig.ENABLE_APP_UPDATES) {
            updateButton.setOnClickListener(v -> checkForUpdates());
        } else {
            updateButton.setEnabled(false);
            updateStatus.setText(R.string.experimental_updates_disabled);
            updateStatus.setVisibility(View.VISIBLE);
        }
        resolverUpdateButton.setOnClickListener(v -> checkResolverUpdates());
        for (int index = 0; index < tabs.length; index++) {
            final int tabIndex = index;
            tabs[index].setOnClickListener(v -> showTab(tabIndex, true));
        }
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            save();
            return true;
        });

        int defaultTab = hasExistingUrl ? TAB_GENERAL : TAB_SOURCE;
        int initialTab = getIntent().getIntExtra(EXTRA_INITIAL_TAB, defaultTab);
        showTab(initialTab, false);
        firstFocusForTab(selectedTabIndex).requestFocus();
    }

    private void initializeCurrentChannelOptions(Intent intent) {
        currentChannelIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1);
        currentChannelTvgId = safeString(intent.getStringExtra(EXTRA_CHANNEL_TVG_ID));
        String channelName = safeString(intent.getStringExtra(EXTRA_CHANNEL_NAME));
        hasCurrentChannel = currentChannelIndex >= 0 && !channelName.isBlank();
        currentChannelName.setText(hasCurrentChannel
                ? channelName
                : getString(R.string.settings_no_current_channel));

        ArrayList<String> labels = intent.getStringArrayListExtra(EXTRA_QUALITY_LABELS);
        if (labels != null) qualityLabels = labels;
        ArrayList<Integer> bitrates = intent.getIntegerArrayListExtra(EXTRA_QUALITY_BITRATES);
        if (bitrates != null) qualityBitrates = bitrates;
        ArrayList<Integer> widths = intent.getIntegerArrayListExtra(EXTRA_QUALITY_WIDTHS);
        if (widths != null) qualityWidths = widths;
        ArrayList<Integer> heights = intent.getIntegerArrayListExtra(EXTRA_QUALITY_HEIGHTS);
        if (heights != null) qualityHeights = heights;

        automaticQuality = intent.getBooleanExtra(EXTRA_QUALITY_AUTOMATIC, false)
                && qualityLabels.size() > 1;
        selectedQualityIndex = intent.getIntExtra(EXTRA_QUALITY_SELECTED_INDEX, -1);
        if (selectedQualityIndex < 0
                || selectedQualityIndex >= qualityLabels.size()) {
            selectedQualityIndex = automaticQuality || qualityLabels.isEmpty()
                    ? -1
                    : 0;
        }
        renderQualityOptions();

        subtitlesAvailable = intent.getBooleanExtra(EXTRA_SUBTITLES_AVAILABLE, false);
        subtitlesSwitch.setVisibility(subtitlesAvailable ? View.VISIBLE : View.GONE);
        subtitlesStatus.setVisibility(subtitlesAvailable ? View.GONE : View.VISIBLE);
        if (subtitlesAvailable) {
            subtitlesSwitch.setChecked(intent.getBooleanExtra(EXTRA_SUBTITLES_ENABLED, true));
            updateSubtitleSwitchLabel();
            subtitlesSwitch.setOnCheckedChangeListener((button, checked) ->
                    updateSubtitleSwitchLabel());
        }
        updatePlaybackFirstFocus();
    }

    private void renderQualityOptions() {
        qualityOptionsContainer.removeAllViews();
        qualityOptionButtons.clear();
        qualityFocusButtons.clear();
        automaticQualityButton = null;

        if (qualityLabels.isEmpty()) {
            qualityStatus.setVisibility(View.VISIBLE);
            return;
        }
        qualityStatus.setVisibility(View.GONE);

        if (qualityLabels.size() > 1) {
            automaticQualityButton = createQualityOptionButton(
                    getString(R.string.stream_quality_automatic)
            );
            automaticQualityButton.setOnClickListener(view -> {
                automaticQuality = true;
                selectedQualityIndex = -1;
                updateQualityOptionLabels();
            });
            qualityOptionsContainer.addView(automaticQualityButton);
            qualityFocusButtons.add(automaticQualityButton);
        }

        for (int index = 0; index < qualityLabels.size(); index++) {
            final int optionIndex = index;
            Button button = createQualityOptionButton(qualityLabels.get(index));
            button.setOnClickListener(view -> {
                automaticQuality = false;
                selectedQualityIndex = optionIndex;
                updateQualityOptionLabels();
            });
            qualityOptionsContainer.addView(button);
            qualityOptionButtons.add(button);
            qualityFocusButtons.add(button);
        }
        updateQualityOptionLabels();
    }

    private Button createQualityOptionButton(String text) {
        Button button = new Button(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.settings_control_height)
        );
        params.bottomMargin = dp(6);
        button.setLayoutParams(params);
        button.setId(View.generateViewId());
        button.setBackgroundResource(R.drawable.settings_section_card);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextColor(getColor(R.color.white));
        button.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.settings_control_text_size)
        );
        button.setAllCaps(false);
        button.setText(text);
        return button;
    }

    private void updateQualityOptionLabels() {
        if (automaticQualityButton != null) {
            automaticQualityButton.setText(
                    (automaticQuality ? "\u2713 " : "")
                            + getString(R.string.stream_quality_automatic)
            );
        }
        for (int index = 0; index < qualityOptionButtons.size(); index++) {
            qualityOptionButtons.get(index).setText(
                    (index == selectedQualityIndex ? "\u2713 " : "")
                            + qualityLabels.get(index)
            );
        }
    }

    private void updateSubtitleSwitchLabel() {
        subtitlesSwitch.setText(subtitlesSwitch.isChecked()
                ? R.string.subtitles_enabled
                : R.string.subtitles_disabled);
    }

    private void updatePlaybackFirstFocus() {
        if (!qualityFocusButtons.isEmpty()) {
            playbackFirstFocus = qualityFocusButtons.get(0);
        } else if (subtitlesAvailable) {
            playbackFirstFocus = subtitlesSwitch;
        } else {
            playbackFirstFocus = invertChannelKeys;
        }
        if (tabs != null && tabs.length > TAB_PLAYBACK) {
            tabs[TAB_PLAYBACK].setNextFocusDownId(playbackFirstFocus.getId());
        }
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }

    private void fitSettingsPanelToAspectRatio() {
        View root = findViewById(R.id.settings_root);
        View panel = findViewById(R.id.settings_panel);
        root.post(() -> {
            int availableWidth = root.getWidth()
                    - root.getPaddingLeft()
                    - root.getPaddingRight();
            int availableHeight = root.getHeight()
                    - root.getPaddingTop()
                    - root.getPaddingBottom();
            if (availableWidth <= 0 || availableHeight <= 0) return;

            int panelWidth;
            int panelHeight;
            if ((float) availableWidth / availableHeight > SETTINGS_PANEL_ASPECT_RATIO) {
                panelHeight = availableHeight;
                panelWidth = Math.round(panelHeight * SETTINGS_PANEL_ASPECT_RATIO);
            } else {
                panelWidth = availableWidth;
                panelHeight = Math.round(panelWidth / SETTINGS_PANEL_ASPECT_RATIO);
            }

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            params.width = panelWidth;
            params.height = panelHeight;
            params.gravity = Gravity.CENTER;
            panel.setLayoutParams(params);
        });
    }

    private void showTab(int selectedIndex, boolean requestFocus) {
        if (tabs == null || tabPages == null) return;
        int safeIndex = Math.max(0, Math.min(selectedIndex, tabs.length - 1));
        selectedTabIndex = safeIndex;
        for (int index = 0; index < tabs.length; index++) {
            boolean selected = index == safeIndex;
            tabs[index].setSelected(selected);
            tabs[index].setTextColor(getColor(selected ? R.color.black : R.color.white));
            tabPages[index].setVisibility(selected ? View.VISIBLE : View.GONE);
        }
        if (requestFocus) {
            View focusTarget = firstFocusForTab(safeIndex);
            focusTarget.requestFocus();
        }
    }

    private View firstFocusForTab(int tabIndex) {
        switch (tabIndex) {
            case 1:
                return playbackFirstFocus == null ? invertChannelKeys : playbackFirstFocus;
            case 2:
                return urlInput;
            case 3:
                return resolverUpdateButton;
            case 4:
                return findViewById(R.id.interface_info);
            case 5:
                return updateButton;
            default:
                return normalizeVolume;
        }
    }

    private void initializeResolverOptions(Intent intent) {
        ArrayList<String> resolverIds = intent.getStringArrayListExtra(EXTRA_RESOLVER_IDS);
        ArrayList<Integer> resolverCounts = intent.getIntegerArrayListExtra(EXTRA_RESOLVER_COUNTS);
        if (resolverIds != null) {
            for (int index = 0; index < resolverIds.size(); index++) {
                int count = resolverCounts != null && index < resolverCounts.size()
                        ? resolverCounts.get(index)
                        : 0;
                resolverGroupCounts.put(resolverIds.get(index), Math.max(0, count));
            }
        }

        try {
            resolverCatalog = resolverCatalogRepository.load();
            renderResolverOptions();
        } catch (Exception error) {
            String fallbackVersion = safeString(intent.getStringExtra(
                    EXTRA_RESOLVER_CATALOG_VERSION
            ));
            resolverCatalogVersion.setText(getString(
                    R.string.resolver_catalog_version,
                    fallbackVersion.isBlank() ? getString(R.string.unknown_version) : fallbackVersion
            ));
            resolverUpdateStatus.setText(R.string.resolver_catalog_load_error);
            resolverUpdateStatus.setVisibility(View.VISIBLE);
        }
    }

    private void initializeTvVooResolutionMode() {
        int checkedId = switch (resolverPreferences.getTvVooResolutionMode()) {
            case DIRECT_ONLY -> R.id.tvvoo_mode_direct;
            case EXTERNAL_ONLY -> R.id.tvvoo_mode_external;
            default -> R.id.tvvoo_mode_both;
        };
        tvVooResolutionModeGroup.check(checkedId);
    }

    private TvVooResolutionMode selectedTvVooResolutionMode() {
        int checkedId = tvVooResolutionModeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.tvvoo_mode_direct) {
            return TvVooResolutionMode.DIRECT_ONLY;
        }
        if (checkedId == R.id.tvvoo_mode_external) {
            return TvVooResolutionMode.EXTERNAL_ONLY;
        }
        return TvVooResolutionMode.BOTH;
    }

    private void renderResolverOptions() {
        if (resolverCatalog == null) return;
        resolverCatalogVersion.setText(getString(
                R.string.resolver_catalog_version,
                resolverCatalog.getVersion()
        ));
        resolverGroupsContainer.removeAllViews();
        resolverGroupSwitches.clear();

        resolverUpdateButton.setNextFocusDownId(tvVooModeBoth.getId());
        View previous = tvVooModeExternal;
        for (ResolverDefinition definition : resolverCatalog.getProviders()) {
            Switch groupSwitch = createResolverGroupSwitch(definition);
            resolverGroupsContainer.addView(groupSwitch);
            resolverGroupSwitches.put(definition.getId(), groupSwitch);
            previous.setNextFocusDownId(groupSwitch.getId());
            groupSwitch.setNextFocusUpId(previous.getId());
            previous = groupSwitch;
        }
        View lastResolverControl = previous;
        lastResolverControl.setNextFocusDownId(saveButton.getId());
        lastResolverControl.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                saveButton.requestFocus();
                return true;
            }
            return false;
        });
    }

    private Switch createResolverGroupSwitch(ResolverDefinition definition) {
        Switch groupSwitch = new Switch(this);
        groupSwitch.setId(View.generateViewId());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.settings_control_height)
        );
        params.topMargin = dp(7);
        groupSwitch.setLayoutParams(params);
        groupSwitch.setBackgroundResource(R.drawable.settings_section_card);
        groupSwitch.setFocusable(true);
        groupSwitch.setGravity(Gravity.CENTER_VERTICAL);
        groupSwitch.setPadding(dp(12), 0, dp(12), 0);
        groupSwitch.setShowText(false);
        groupSwitch.setTextColor(getColor(R.color.white));
        groupSwitch.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.settings_control_text_size)
        );
        Integer storedCount = resolverGroupCounts.get(definition.getId());
        int channelCount = storedCount == null ? 0 : storedCount;
        groupSwitch.setText(getResources().getQuantityString(
                R.plurals.resolver_group_channels,
                channelCount,
                definition.getDisplayName(),
                channelCount
        ));
        groupSwitch.setChecked(resolverPreferences.isEnabled(definition));
        if (Build.VERSION.SDK_INT >= 21) {
            groupSwitch.setThumbTintList(getColorStateList(R.color.cyan));
        }
        return groupSwitch;
    }

    private void checkResolverUpdates() {
        resolverUpdateButton.setEnabled(false);
        resolverUpdateStatus.setText(R.string.resolver_update_checking);
        resolverUpdateStatus.setVisibility(View.VISIBLE);
        updateExecutor.submit(() -> {
            try {
                ResolverCatalogRepository.UpdateResult update =
                        resolverCatalogRepository.downloadAndInstall();
                mainHandler.post(() -> {
                    resolverUpdateButton.setEnabled(true);
                    resolverCatalog = update.getCatalog();
                    renderResolverOptions();
                    resolverUpdateStatus.setText(update.isChanged()
                            ? getString(
                                    R.string.resolver_update_installed,
                                    resolverCatalog.getVersion()
                            )
                            : getString(R.string.resolver_update_up_to_date));
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    resolverUpdateButton.setEnabled(true);
                    resolverUpdateStatus.setText(R.string.resolver_update_error);
                });
            }
        });
    }

    private void moveTabFromRemote(int delta) {
        int nextIndex = (selectedTabIndex + delta + tabs.length) % tabs.length;
        showTab(nextIndex, false);
        tabs[nextIndex].requestFocus();
    }

    private void checkForUpdates() {
        if (!BuildConfig.ENABLE_APP_UPDATES) return;
        updateButton.setEnabled(false);
        updateStatus.setText(R.string.update_checking);
        updateStatus.setVisibility(View.VISIBLE);
        appUpdater.checkForUpdates(new AppUpdater.CheckListener() {
            @Override
            public void onUpdateAvailable(UpdateInfo update) {
                updateButton.setEnabled(true);
                updateStatus.setText(getString(
                        R.string.update_found,
                        update.getVersionName()
                ));
            }

            @Override
            public void onUpToDate() {
                updateButton.setEnabled(true);
                updateStatus.setText(R.string.update_up_to_date);
            }

            @Override
            public void onError(Throwable error) {
                updateButton.setEnabled(true);
                updateStatus.setText(getString(
                        R.string.update_check_error,
                        error == null || error.getMessage() == null
                                ? getString(R.string.unknown_error)
                                : error.getMessage()
                ));
            }
        });
    }

    private void save() {
        String value = urlInput.getText().toString().trim();
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (value.isEmpty() || scheme == null ||
                !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) ||
                uri.getHost() == null) {
            errorText.setText(R.string.url_required);
            errorText.setVisibility(View.VISIBLE);
            urlInput.requestFocus();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAYLIST_URL, value)
                .putBoolean(KEY_INVERT_CHANNEL_KEYS, invertChannelKeys.isChecked())
                .putBoolean(KEY_NORMALIZE_VOLUME, normalizeVolume.isChecked())
                .apply();
        for (Map.Entry<String, Switch> entry : resolverGroupSwitches.entrySet()) {
            ResolverDefinition definition = resolverCatalog == null
                    ? null
                    : resolverCatalog.getById(entry.getKey());
            if (definition != null) {
                resolverPreferences.setEnabled(definition, entry.getValue().isChecked());
            }
        }
        resolverPreferences.setTvVooResolutionMode(selectedTvVooResolutionMode());
        Intent result = new Intent().putExtra(KEY_PLAYLIST_URL, value);
        if (hasCurrentChannel) {
            result.putExtra(EXTRA_CHANNEL_INDEX, currentChannelIndex)
                    .putExtra(EXTRA_CHANNEL_TVG_ID, currentChannelTvgId)
                    .putExtra(EXTRA_CHANNEL_NAME, currentChannelName.getText().toString());
            if (!qualityLabels.isEmpty() && selectedQualityIndex >= 0
                && selectedQualityIndex < qualityLabels.size()) {
                result.putExtra(EXTRA_QUALITY_AUTOMATIC, false)
                        .putExtra(
                                EXTRA_QUALITY_BITRATE,
                                selectedQualityIndex < qualityBitrates.size()
                                        ? qualityBitrates.get(selectedQualityIndex)
                                        : 0
                        )
                        .putExtra(
                                EXTRA_QUALITY_WIDTH,
                                selectedQualityIndex < qualityWidths.size()
                                        ? qualityWidths.get(selectedQualityIndex)
                                        : 0
                        )
                        .putExtra(
                                EXTRA_QUALITY_HEIGHT,
                                selectedQualityIndex < qualityHeights.size()
                                        ? qualityHeights.get(selectedQualityIndex)
                                        : 0
                        );
            } else if (!qualityLabels.isEmpty()) {
                result.putExtra(EXTRA_QUALITY_AUTOMATIC, true);
            }
            if (subtitlesAvailable) {
                result.putExtra(EXTRA_SUBTITLES_ENABLED, subtitlesSwitch.isChecked());
            }
        }
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (!hasExistingUrl && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                moveTabFromRemote(-1);
                return true;
            }
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveTabFromRemote(1);
                return true;
            }
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_UP
                    && getCurrentFocus() == firstFocusForTab(selectedTabIndex)) {
                tabs[selectedTabIndex].requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (appUpdater != null && appUpdater.onActivityResult(requestCode)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (appUpdater != null) appUpdater.onHostResume();
    }

    @Override
    protected void onPause() {
        if (appUpdater != null) appUpdater.onHostPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (appUpdater != null) appUpdater.destroy();
        updateExecutor.shutdownNow();
        super.onDestroy();
    }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}

package cl.streambox.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
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
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    /** Public Lista 1 used on a new installation. */
    public static final String DEFAULT_PLAYLIST_URL =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/m3u.m3u";
    /** Public Lista 2 shown in the second source field on a new installation. */
    public static final String DEFAULT_PLAYLIST_URL_2 =
            "https://raw.githubusercontent.com/SPxMM3R1/lista-m3u/main/m3u-externa.m3u";
    /** Public Highfly/Stremio manifest used when a channel has no override. */
    public static final String DEFAULT_HIGHFLY_MANIFEST_URL =
            "https://sports.highfly.to/manifest.json";
    public static final String KEY_PLAYLIST_URL = "playlist_url";
    public static final String KEY_PLAYLIST_URL_2 = "playlist_url_2";
    public static final String KEY_PLAYLIST_ENABLED = "playlist_enabled";
    public static final String KEY_PLAYLIST_ENABLED_2 = "playlist_enabled_2";
    public static final String KEY_HIGHFLY_MANIFEST_URL = "highfly_manifest_url";
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
    public static final String EXTRA_HIDDEN_CHANNELS_CHANGED = "hidden_channels_changed";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();

    private EditText urlInput;
    private EditText urlInput2;
    private EditText highflyManifestUrlInput;
    private TextView errorText;
    private Switch playlistOneEnabled;
    private Switch playlistTwoEnabled;
    private Switch tvvooSourceEnabled;
    private Button tvvooCatalogButton;
    private Switch highflySourceEnabled;
    private Button highflyCatalogButton;
    private Switch githubAutoPublish;
    private EditText githubTokenInput;
    private TextView githubTokenStatus;
    private Button githubPublishButton;
    private TextView githubPublishStatus;
    private Button mediaFlowSettingsButton;
    private TextView tvvooSelectionStatus;
    private TextView highflySelectionStatus;
    private TvVooSelectionStore tvvooSelectionStore;
    private HighflySelectionStore highflySelectionStore;
    private GitHubPublicationPreferences githubPublicationPreferences;
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
    private ScrollView settingsContent;
    private ViewTreeObserver.OnGlobalFocusChangeListener focusVisibilityListener;
    private int selectedTabIndex;
    private TextView currentChannelName;
    private LinearLayout qualityOptionsContainer;
    private TextView qualityStatus;
    private Switch subtitlesSwitch;
    private TextView subtitlesStatus;
    private TextView resolverCatalogVersion;
    private LinearLayout resolverGroupsContainer;
    private LinearLayout hiddenChannelsContainer;
    private TextView hiddenChannelsStatus;
    private ResolverCatalogRepository resolverCatalogRepository;
    private ResolverPreferences resolverPreferences;
    private ResolverCatalog resolverCatalog;
    private HiddenChannelStore hiddenChannelStore;
    private final Map<String, Switch> resolverGroupSwitches = new LinkedHashMap<>();
    private final Map<String, Integer> resolverGroupCounts = new LinkedHashMap<>();
    private final Map<String, Switch> hiddenChannelSwitches = new LinkedHashMap<>();
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
    /**
     * Seeds a completely unconfigured installation and fills only a missing
     * Lista 2 URL. Existing playlist choices remain authoritative, including
     * an intentionally disabled or empty source.
     */
    public static void ensureDefaultPlaylistConfigured(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        removeObsoleteHighflyCredentialState(prefs);
        removeObsoleteHighflyKeyMaterial();
        boolean hasPlaylistPreferences = prefs.contains(KEY_PLAYLIST_URL)
                || prefs.contains(KEY_PLAYLIST_URL_2)
                || prefs.contains(KEY_PLAYLIST_ENABLED)
                || prefs.contains(KEY_PLAYLIST_ENABLED_2);
        SharedPreferences.Editor editor = null;
        if (!prefs.contains(KEY_PLAYLIST_URL_2)) {
            editor = prefs.edit()
                    .putString(KEY_PLAYLIST_URL_2, DEFAULT_PLAYLIST_URL_2);
        }
        if (!prefs.contains(KEY_HIGHFLY_MANIFEST_URL)) {
            if (editor == null) editor = prefs.edit();
            editor.putString(KEY_HIGHFLY_MANIFEST_URL, DEFAULT_HIGHFLY_MANIFEST_URL);
        }
        if (!hasPlaylistPreferences) {
            if (editor == null) editor = prefs.edit();
            editor.putString(KEY_PLAYLIST_URL, DEFAULT_PLAYLIST_URL)
                    .putBoolean(KEY_PLAYLIST_ENABLED, true);
        }
        if (editor != null) editor.apply();
    }

    /** Removes credential state written by versions that exposed this provider separately. */
    private static void removeObsoleteHighflyCredentialState(SharedPreferences preferences) {
        SharedPreferences.Editor editor = null;
        for (String key : preferences.getAll().keySet()) {
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
            boolean obsoleteCredential = normalized.contains("highfly")
                    && (normalized.contains("token")
                    || normalized.contains("credential")
                    || normalized.contains("storage_mode")
                    || normalized.endsWith("_status")
                    || normalized.endsWith("_plan")
                    || normalized.endsWith("_expires_at"));
            if (!obsoleteCredential) continue;
            if (editor == null) editor = preferences.edit();
            editor.remove(key);
        }
        if (editor != null) editor.commit();
    }

    /** Removes inert Android Keystore entries left by the retired provider integration. */
    private static void removeObsoleteHighflyKeyMaterial() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            Enumeration<String> aliases = keyStore.aliases();
            List<String> obsoleteAliases = new ArrayList<>();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (alias != null
                        && alias.toLowerCase(Locale.ROOT).startsWith("vibem3u_highfly")) {
                    obsoleteAliases.add(alias);
                }
            }
            for (String alias : obsoleteAliases) keyStore.deleteEntry(alias);
        } catch (Exception ignored) {
            // A missing/unsupported keystore must not prevent the app from opening.
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        enterImmersiveMode();
        fitSettingsPanelToAspectRatio();

        ensureDefaultPlaylistConfigured(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existingUrl = prefs.getString(KEY_PLAYLIST_URL, "");
        String existingUrl2 = prefs.getString(KEY_PLAYLIST_URL_2, "");
        hasExistingUrl = (existingUrl != null && !AppStrings.isBlank(existingUrl))
                || (existingUrl2 != null && !AppStrings.isBlank(existingUrl2));
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (hasExistingUrl) finish();
                    });
        }

        urlInput = findViewById(R.id.playlist_url);
        urlInput2 = findViewById(R.id.playlist_url_2);
        highflyManifestUrlInput = findViewById(R.id.highfly_manifest_url);
        errorText = findViewById(R.id.url_error);
        playlistOneEnabled = findViewById(R.id.playlist_1_enabled);
        playlistTwoEnabled = findViewById(R.id.playlist_2_enabled);
        tvvooSourceEnabled = findViewById(R.id.tvvoo_source_enabled);
        tvvooCatalogButton = findViewById(R.id.tvvoo_catalog_button);
        highflySourceEnabled = findViewById(R.id.highfly_source_enabled);
        highflyCatalogButton = findViewById(R.id.highfly_catalog_button);
        githubAutoPublish = findViewById(R.id.github_auto_publish);
        githubTokenInput = findViewById(R.id.github_token);
        githubTokenStatus = findViewById(R.id.github_token_status);
        githubPublishButton = findViewById(R.id.github_publish_now);
        githubPublishStatus = findViewById(R.id.github_publish_status);
        mediaFlowSettingsButton = findViewById(R.id.mediaflow_settings_button);
        tvvooSelectionStatus = findViewById(R.id.tvvoo_selection_status);
        highflySelectionStatus = findViewById(R.id.highfly_selection_status);
        invertChannelKeys = findViewById(R.id.invert_channel_keys);
        normalizeVolume = findViewById(R.id.normalize_volume);
        updateButton = findViewById(R.id.check_updates_button);
        updateStatus = findViewById(R.id.update_status);
        cancelButton = findViewById(R.id.cancel_button);
        saveButton = findViewById(R.id.save_button);
        settingsContent = findViewById(R.id.settings_content);
        currentChannelName = findViewById(R.id.settings_current_channel_name);
        qualityOptionsContainer = findViewById(R.id.settings_quality_options);
        qualityStatus = findViewById(R.id.settings_quality_status);
        subtitlesSwitch = findViewById(R.id.settings_subtitles_switch);
        subtitlesStatus = findViewById(R.id.settings_subtitles_status);
        resolverCatalogVersion = findViewById(R.id.resolver_catalog_version);
        resolverGroupsContainer = findViewById(R.id.resolver_groups_container);
        hiddenChannelsContainer = findViewById(R.id.hidden_channels_container);
        hiddenChannelsStatus = findViewById(R.id.hidden_channels_status);
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
        tvvooSelectionStore = new TvVooSelectionStore(this);
        highflySelectionStore = new HighflySelectionStore(this);
        githubPublicationPreferences = new GitHubPublicationPreferences(this);
        hiddenChannelStore = new HiddenChannelStore(this);
        urlInput.setText(existingUrl);
        urlInput.setSelection(urlInput.length());
        String initialUrl2 = prefs.contains(KEY_PLAYLIST_URL_2)
                ? existingUrl2
                : DEFAULT_PLAYLIST_URL_2;
        urlInput2.setText(initialUrl2 == null ? "" : initialUrl2);
        String initialHighflyManifest = prefs.getString(
                KEY_HIGHFLY_MANIFEST_URL,
                DEFAULT_HIGHFLY_MANIFEST_URL
        );
        highflyManifestUrlInput.setText(AppStrings.isBlank(initialHighflyManifest)
                ? DEFAULT_HIGHFLY_MANIFEST_URL
                : initialHighflyManifest);
        boolean firstPlaylistEnabled = prefs.contains(KEY_PLAYLIST_ENABLED)
                ? prefs.getBoolean(KEY_PLAYLIST_ENABLED, true)
                : existingUrl != null && !AppStrings.isBlank(existingUrl);
        playlistOneEnabled.setChecked(firstPlaylistEnabled);
        playlistTwoEnabled.setChecked(prefs.getBoolean(
                KEY_PLAYLIST_ENABLED_2,
                false
        ));
        tvvooSourceEnabled.setChecked(tvvooSelectionStore.isEnabled());
        highflySourceEnabled.setChecked(highflySelectionStore.isEnabled());
        githubAutoPublish.setChecked(githubPublicationPreferences.isAutoPublishEnabled());
        updateGitHubPublicationStatus();
        updateTvVooSelectionStatus();
        updateHighflySelectionStatus();
        invertChannelKeys.setChecked(prefs.getBoolean(KEY_INVERT_CHANNEL_KEYS, false));
        normalizeVolume.setChecked(prefs.getBoolean(KEY_NORMALIZE_VOLUME, false));
        TextView versionText = findViewById(R.id.current_version);
        versionText.setText(getString(R.string.current_version, BuildConfig.VERSION_NAME));
        initializeCurrentChannelOptions(getIntent());
        initializeResolverOptions(getIntent());
        renderHiddenChannels();

        tvvooSourceEnabled.setOnCheckedChangeListener((button, checked) -> {
            // A selected TvVoo source must have its resolver engine available;
            // turning the source off leaves the user's resolver preference intact.
            if (checked) {
                Switch tvvooResolver = resolverGroupSwitches.get("tvvoo");
                if (tvvooResolver != null) tvvooResolver.setChecked(true);
            }
            updateTvVooSelectionStatus();
        });
        highflySourceEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                Switch highflyResolver = resolverGroupSwitches.get("highfly");
                if (highflyResolver != null) highflyResolver.setChecked(true);
            }
            updateHighflySelectionStatus();
        });
        tvvooCatalogButton.setOnClickListener(view ->
                startActivity(new Intent(this, TvVooCatalogActivity.class)));
        highflyCatalogButton.setOnClickListener(view ->
                startActivity(new Intent(this, HighflyCatalogActivity.class)));
        githubPublishButton.setOnClickListener(view -> publishGitHubSelectionNow());
        mediaFlowSettingsButton.setOnClickListener(view ->
                startActivity(new Intent(this, MediaFlowSettingsActivity.class)));

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
        for (int index = 0; index < tabs.length; index++) {
            final int tabIndex = index;
            tabs[index].setOnClickListener(v -> showTab(tabIndex, true));
        }
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            save();
            return true;
        });
        urlInput2.setOnEditorActionListener((v, actionId, event) -> {
            save();
            return true;
        });
        focusVisibilityListener = (oldFocus, newFocus) -> {
            if (newFocus != null && settingsContent != null
                    && isDescendantOf(newFocus, settingsContent)) {
                ensureSettingsControlVisible(newFocus);
            }
        };
        getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalFocusChangeListener(focusVisibilityListener);

        int defaultTab = hasExistingUrl ? TAB_GENERAL : TAB_SOURCE;
        int initialTab = getIntent().getIntExtra(EXTRA_INITIAL_TAB, defaultTab);
        showTab(initialTab, false);
        tabs[selectedTabIndex].requestFocus();
    }

    private void initializeCurrentChannelOptions(Intent intent) {
        currentChannelIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1);
        currentChannelTvgId = safeString(intent.getStringExtra(EXTRA_CHANNEL_TVG_ID));
        String channelName = safeString(intent.getStringExtra(EXTRA_CHANNEL_NAME));
        hasCurrentChannel = currentChannelIndex >= 0 && !AppStrings.isBlank(channelName);
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

    private void updateTvVooSelectionStatus() {
        if (tvvooSelectionStore == null || tvvooSelectionStatus == null) return;
        int count = tvvooSelectionStore.getSelectedCatalogChannels().size();
        String state = tvvooSourceEnabled != null && tvvooSourceEnabled.isChecked()
                ? getString(R.string.tvvoo_source_enabled)
                : getString(R.string.tvvoo_source_disabled);
        tvvooSelectionStatus.setText(getString(R.string.tvvoo_selection_status, state, count));
    }

    private void updateHighflySelectionStatus() {
        if (highflySelectionStore == null || highflySelectionStatus == null) return;
        int count = highflySelectionStore.getSelectedCatalogChannels().size();
        String state = highflySourceEnabled != null && highflySourceEnabled.isChecked()
                ? getString(R.string.highfly_source_enabled)
                : getString(R.string.highfly_source_disabled);
        highflySelectionStatus.setText(getString(
                R.string.highfly_selection_status,
                state,
                count
        ));
    }

    private void updateGitHubPublicationStatus() {
        if (githubPublicationPreferences == null) return;
        if (githubTokenStatus != null) {
            githubTokenStatus.setText(
                    githubPublicationPreferences.hasToken()
                            ? getString(R.string.github_token_saved)
                            : getString(R.string.github_publish_not_configured)
            );
        }
        if (githubPublishStatus != null) {
            String saved = githubPublicationPreferences.getLastStatus();
            githubPublishStatus.setText(AppStrings.isBlank(saved)
                    ? getString(R.string.github_publish_not_configured)
                    : saved);
        }
    }

    private boolean persistGitHubPublicationConfiguration() {
        if (githubPublicationPreferences == null) return true;
        String typedToken = githubTokenInput == null || githubTokenInput.getText() == null
                ? ""
                : githubTokenInput.getText().toString().trim();
        try {
            // Empty input intentionally preserves the encrypted token. This
            // prevents the user from having to paste it on every visit.
            if (!typedToken.isEmpty()) githubPublicationPreferences.setToken(typedToken);
            boolean enabled = githubAutoPublish != null && githubAutoPublish.isChecked();
            if (enabled && !githubPublicationPreferences.hasToken()) {
                errorText.setText(R.string.github_token_required);
                errorText.setVisibility(View.VISIBLE);
                if (githubTokenInput != null) githubTokenInput.requestFocus();
                return false;
            }
            githubPublicationPreferences.setAutoPublishEnabled(enabled);
            if (githubTokenInput != null) githubTokenInput.setText("");
            updateGitHubPublicationStatus();
            return true;
        } catch (Exception error) {
            errorText.setText(R.string.github_token_invalid);
            errorText.setVisibility(View.VISIBLE);
            if (githubTokenInput != null) githubTokenInput.requestFocus();
            return false;
        }
    }

    private void publishGitHubSelectionNow() {
        if (!persistGitHubPublicationConfiguration()) return;
        if (!githubPublicationPreferences.hasToken()) {
            if (githubPublishStatus != null) {
                githubPublishStatus.setText(R.string.github_token_required);
            }
            if (githubTokenInput != null) githubTokenInput.requestFocus();
            return;
        }
        if (githubPublishStatus != null) {
            githubPublishStatus.setText(R.string.github_publish_working);
        }
        githubPublishButton.setEnabled(false);
        GitHubSelectionPublisher.publishAsync(this, true, result -> mainHandler.post(() -> {
            if (isFinishing()) return;
            githubPublishButton.setEnabled(true);
            if (result == null) {
                githubPublishStatus.setText(R.string.github_publish_generic_error);
            } else if (result.isPublished()) {
                githubPublishStatus.setText(R.string.github_publish_success);
            } else {
                githubPublishStatus.setText(getString(
                        R.string.github_publish_error,
                        result.getMessage()
                ));
            }
            updateGitHubPublicationStatus();
        }));
    }

    private void renderHiddenChannels() {
        if (hiddenChannelStore == null
                || hiddenChannelsContainer == null
                || hiddenChannelsStatus == null) return;
        hiddenChannelsContainer.removeAllViews();
        hiddenChannelSwitches.clear();

        List<HiddenChannelStore.Entry> entries = hiddenChannelStore.getEntries();
        hiddenChannelsStatus.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        View previous = findViewById(R.id.interface_info);
        for (HiddenChannelStore.Entry entry : entries) {
            Switch channelSwitch = new Switch(this);
            channelSwitch.setId(View.generateViewId());
            channelSwitch.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    getResources().getDimensionPixelSize(R.dimen.settings_control_height)
            ));
            channelSwitch.setBackgroundResource(R.drawable.settings_section_card);
            channelSwitch.setFocusable(true);
            channelSwitch.setGravity(Gravity.CENTER_VERTICAL);
            channelSwitch.setPadding(dp(12), 0, dp(12), 0);
            channelSwitch.setShowText(false);
            String label = AppStrings.isBlank(entry.getTvgId())
                    ? entry.getName()
                    : entry.getName() + " · " + entry.getTvgId();
            channelSwitch.setText(label);
            channelSwitch.setTextColor(getColor(R.color.white));
            channelSwitch.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.settings_control_text_size)
            );
            channelSwitch.setThumbTintList(getColorStateList(R.color.cyan));
            channelSwitch.setChecked(true);
            hiddenChannelsContainer.addView(channelSwitch);
            hiddenChannelSwitches.put(entry.getIdentity(), channelSwitch);
            previous.setNextFocusDownId(channelSwitch.getId());
            channelSwitch.setNextFocusUpId(previous.getId());
            previous = channelSwitch;
        }
        previous.setNextFocusDownId(saveButton.getId());
    }

    private boolean saveHiddenChannels() {
        if (hiddenChannelStore == null) return false;
        boolean changed = false;
        for (Map.Entry<String, Switch> item : hiddenChannelSwitches.entrySet()) {
            if (item.getValue().isChecked()) continue;
            hiddenChannelStore.setHidden(item.getKey(), false);
            changed = true;
        }
        return changed;
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
        if (requestFocus) tabs[safeIndex].requestFocus();
    }

    private View firstFocusForTab(int tabIndex) {
        switch (tabIndex) {
            case 1:
                return playbackFirstFocus == null ? invertChannelKeys : playbackFirstFocus;
            case 2:
                return playlistOneEnabled;
            case 3:
                return resolverGroupSwitches.isEmpty()
                        ? tabs[TAB_RESOLVERS]
                        : resolverGroupSwitches.values().iterator().next();
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
                    AppStrings.isBlank(fallbackVersion) ? getString(R.string.unknown_version) : fallbackVersion
            ));
            // The catalogue is bundled with the APK. A parse failure is
            // reported by the version label; there is no remote resolver
            // update path to fall back to.
        }
    }

    private void renderResolverOptions() {
        if (resolverCatalog == null) return;
        resolverCatalogVersion.setText(getString(
                R.string.resolver_catalog_version,
                resolverCatalog.getVersion()
        ));
        resolverGroupsContainer.removeAllViews();
        resolverGroupSwitches.clear();

        View previous = tabs[TAB_RESOLVERS];
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
        groupSwitch.setThumbTintList(getColorStateList(R.color.cyan));
        return groupSwitch;
    }

    private void moveTabFromRemote(int delta) {
        int nextIndex = (selectedTabIndex + delta + tabs.length) % tabs.length;
        showTab(nextIndex, false);
        tabs[nextIndex].requestFocus();
    }

    private boolean isTextFieldFocused() {
        return getCurrentFocus() instanceof EditText;
    }

    private boolean isTabFocused() {
        View focusedView = getCurrentFocus();
        if (focusedView == null || tabs == null) return false;
        for (TextView tab : tabs) {
            if (focusedView == tab) return true;
        }
        return false;
    }

    private boolean isDescendantOf(View view, View ancestor) {
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private void ensureSettingsControlVisible(View focusedView) {
        if (settingsContent == null || focusedView == null) return;
        focusedView.post(() -> {
            if (isFinishing() || settingsContent.getHeight() <= 0) return;
            Rect visibleRect = new Rect();
            focusedView.getDrawingRect(visibleRect);
            settingsContent.offsetDescendantRectToMyCoords(focusedView, visibleRect);

            int topEdge = settingsContent.getScrollY();
            int bottomEdge = topEdge + settingsContent.getHeight();
            int margin = dp(12);
            if (visibleRect.top < topEdge) {
                settingsContent.smoothScrollTo(
                        0,
                        Math.max(0, visibleRect.top - margin)
                );
            } else if (visibleRect.bottom > bottomEdge) {
                settingsContent.smoothScrollTo(
                        0,
                        Math.max(0, visibleRect.bottom - settingsContent.getHeight() + margin)
                );
            }
        });
    }

    private boolean hasHorizontalTargetInCurrentPage(int keyCode) {
        View focusedView = getCurrentFocus();
        if (focusedView == null || tabPages == null
                || selectedTabIndex < 0 || selectedTabIndex >= tabPages.length) {
            return false;
        }
        int direction = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                ? View.FOCUS_LEFT
                : View.FOCUS_RIGHT;
        View target = focusedView.focusSearch(direction);
        return target != null && isDescendantOf(target, tabPages[selectedTabIndex]);
    }

    private boolean isFooterButtonFocused() {
        View focusedView = getCurrentFocus();
        return focusedView == saveButton || focusedView == cancelButton;
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
        String value2 = urlInput2.getText().toString().trim();
        String highflyManifest = highflyManifestUrlInput.getText().toString().trim();
        boolean enabled1 = playlistOneEnabled.isChecked();
        boolean enabled2 = playlistTwoEnabled.isChecked();
        boolean tvvooEnabled = tvvooSourceEnabled.isChecked();
        boolean highflyEnabled = highflySourceEnabled.isChecked();
        boolean tvvooHasSelection = !tvvooSelectionStore.getSelectedCatalogChannels().isEmpty();
        boolean highflyHasSelection = !highflySelectionStore.getSelectedCatalogChannels().isEmpty();
        if (!enabled1 && !enabled2 && !tvvooEnabled && !highflyEnabled) {
            errorText.setText(R.string.playlist_source_required);
            errorText.setVisibility(View.VISIBLE);
            playlistOneEnabled.requestFocus();
            return;
        }
        if (tvvooEnabled && !tvvooHasSelection) {
            errorText.setText(R.string.tvvoo_selection_required);
            errorText.setVisibility(View.VISIBLE);
            tvvooCatalogButton.requestFocus();
            return;
        }
        if (highflyEnabled && !highflyHasSelection) {
            errorText.setText(R.string.highfly_selection_required);
            errorText.setVisibility(View.VISIBLE);
            highflyCatalogButton.requestFocus();
            return;
        }
        if (enabled1 && !isValidPlaylistUrl(value)) {
            errorText.setText(R.string.url_required);
            errorText.setVisibility(View.VISIBLE);
            urlInput.requestFocus();
            return;
        }
        if (enabled2 && !isValidPlaylistUrl(value2)) {
            errorText.setText(R.string.url_required);
            errorText.setVisibility(View.VISIBLE);
            urlInput2.requestFocus();
            return;
        }
        if (!HighflyStreamResolver.isAllowedManifestUrl(highflyManifest)) {
            errorText.setText(R.string.highfly_manifest_url_required);
            errorText.setVisibility(View.VISIBLE);
            highflyManifestUrlInput.requestFocus();
            return;
        }
        if (!persistGitHubPublicationConfiguration()) return;

        boolean hiddenChannelsChanged = saveHiddenChannels();
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAYLIST_URL, value)
                .putString(KEY_PLAYLIST_URL_2, value2)
                .putString(KEY_HIGHFLY_MANIFEST_URL, highflyManifest)
                .putBoolean(KEY_PLAYLIST_ENABLED, enabled1)
                .putBoolean(KEY_PLAYLIST_ENABLED_2, enabled2)
                .putBoolean(KEY_INVERT_CHANNEL_KEYS, invertChannelKeys.isChecked())
                .putBoolean(KEY_NORMALIZE_VOLUME, normalizeVolume.isChecked())
                .apply();
        tvvooSelectionStore.setEnabled(tvvooEnabled);
        highflySelectionStore.setEnabled(highflyEnabled);
        if (tvvooEnabled) {
            // The source toggle is the user-facing opt-in. Keep the TvVoo
            // resolver engine enabled so a valid selection cannot become a
            // silently empty list after editing resolver settings.
            Switch tvvooResolver = resolverGroupSwitches.get("tvvoo");
            if (tvvooResolver != null) tvvooResolver.setChecked(true);
        }
        if (highflyEnabled) {
            Switch highflyResolver = resolverGroupSwitches.get("highfly");
            if (highflyResolver != null) highflyResolver.setChecked(true);
        }
        for (Map.Entry<String, Switch> entry : resolverGroupSwitches.entrySet()) {
            ResolverDefinition definition = resolverCatalog == null
                    ? null
                    : resolverCatalog.getById(entry.getKey());
            if (definition != null) {
                resolverPreferences.setEnabled(definition, entry.getValue().isChecked());
            }
        }
        GitHubSelectionPublisher.enqueue(this, "settings-save");
        Intent result = new Intent()
                .putExtra(KEY_PLAYLIST_URL, value)
                .putExtra(KEY_PLAYLIST_URL_2, value2)
                .putExtra(KEY_PLAYLIST_ENABLED, enabled1)
                .putExtra(KEY_PLAYLIST_ENABLED_2, enabled2)
                .putExtra(EXTRA_HIDDEN_CHANNELS_CHANGED, hiddenChannelsChanged);
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

    private static boolean isValidPlaylistUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                && uri.getHost() != null;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (!hasExistingUrl && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            boolean horizontalKey = event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            if (horizontalKey) {
                // Left/right changes tabs only while the tab bar itself has focus.
                // Content controls must receive the key and must never escape
                // to the tab bar.
                if (isTabFocused()) {
                    moveTabFromRemote(
                            event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                    ? -1
                                    : 1
                    );
                    return true;
                }
                if (isTextFieldFocused() || isFooterButtonFocused()
                        || hasHorizontalTargetInCurrentPage(event.getKeyCode())) {
                    return super.dispatchKeyEvent(event);
                }
                // There is no horizontal control in this page. Keep focus where it
                // is instead of allowing Android's fallback search to reach a tab.
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
        updateTvVooSelectionStatus();
        updateHighflySelectionStatus();
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
        if (focusVisibilityListener != null) {
            ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalFocusChangeListener(focusVisibilityListener);
            }
        }
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

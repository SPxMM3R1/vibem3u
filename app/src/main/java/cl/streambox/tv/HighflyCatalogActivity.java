package cl.streambox.tv;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Highfly live-catalog selector designed for Android TV remote navigation. */
public final class HighflyCatalogActivity extends Activity {
    private static final int PAGE_SIZE = 40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, HighflyCatalogChannel> selected = new LinkedHashMap<>();

    private HighflyCatalogRepository repository;
    private HighflySelectionStore selectionStore;
    private HighflyCatalog catalog;
    private Spinner categorySpinner;
    private CheckBox selectedOnlyToggle;
    private EditText searchInput;
    private LinearLayout channelContainer;
    private TextView statusText;
    private TextView pageText;
    private Button previousButton;
    private Button nextButton;
    private Button applyButton;
    private Button refreshIdsButton;
    private ChannelIdentityIndex identityIndex = ChannelIdentityIndex.empty();
    private int currentPage;
    private boolean applyingCategory;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new HighflyCatalogRepository(this);
        selectionStore = new HighflySelectionStore(this);
        for (HighflyCatalogChannel channel : selectionStore.getSelectedCatalogChannels()) {
            selected.put(channel.getResourceId(), channel);
        }
        buildLayout();
        loadCatalogWithCache();
        enterImmersiveMode();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(18), dp(28), dp(16));
        root.setBackgroundColor(Color.rgb(8, 17, 34));

        TextView title = label("Canales Highfly", 24, Color.rgb(0, 255, 209));
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView description = label(
                "Marca canales del catálogo. El orden se conserva al seleccionar; el ID EPG se confirma desde la M3U.",
                14,
                Color.LTGRAY
        );
        root.addView(description, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        categorySpinner = new Spinner(this);
        categorySpinner.setFocusable(true);
        selectedOnlyToggle = new CheckBox(this);
        selectedOnlyToggle.setText("Solo seleccionados");
        selectedOnlyToggle.setTextColor(Color.WHITE);
        selectedOnlyToggle.setTextSize(13);
        selectedOnlyToggle.setFocusable(true);
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Buscar canal");
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.GRAY);
        searchInput.setTextSize(15);
        searchInput.setSelectAllOnFocus(false);
        addWeighted(filters, categorySpinner, 1.0f, dp(48));
        addWeighted(filters, selectedOnlyToggle, 1.0f, dp(48));
        addWeighted(filters, searchInput, 1.7f, dp(48));
        root.addView(filters, new LinearLayout.LayoutParams(-1, dp(58)));

        statusText = label("Cargando catálogo…", 14, Color.LTGRAY);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, dp(34)));

        refreshIdsButton = actionButton(getString(R.string.catalog_refresh_ids));
        refreshIdsButton.setOnClickListener(view -> refreshIdentityIndex());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                dp(190), dp(44)
        );
        refreshParams.gravity = Gravity.END;
        root.addView(refreshIdsButton, refreshParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        channelContainer = new LinearLayout(this);
        channelContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(channelContainer, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout paging = new LinearLayout(this);
        paging.setGravity(Gravity.CENTER_VERTICAL);
        previousButton = actionButton("Anterior");
        nextButton = actionButton("Siguiente");
        pageText = label("Página 0/0", 14, Color.LTGRAY);
        pageText.setGravity(Gravity.CENTER);
        paging.addView(previousButton, new LinearLayout.LayoutParams(dp(150), dp(48)));
        paging.addView(pageText, new LinearLayout.LayoutParams(0, dp(48), 1f));
        paging.addView(nextButton, new LinearLayout.LayoutParams(dp(150), dp(48)));
        root.addView(paging, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button copyButton = actionButton("Copiar referencias");
        applyButton = actionButton("Aplicar");
        Button cancelButton = actionButton("Cancelar");
        actions.addView(copyButton, new LinearLayout.LayoutParams(dp(200), dp(50)));
        actions.addView(applyButton, new LinearLayout.LayoutParams(dp(145), dp(50)));
        actions.addView(cancelButton, new LinearLayout.LayoutParams(dp(145), dp(50)));
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(62)));
        setContentView(root);
        loadCachedIdentityIndex();

        categorySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(
                    android.widget.AdapterView<?> parent, View view, int position, long id
            ) {
                if (applyingCategory) return;
                currentPage = 0;
                renderPage();
            }
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentPage = 0;
                renderPage();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        selectedOnlyToggle.setOnCheckedChangeListener((button, checked) -> {
            currentPage = 0;
            renderPage();
        });
        previousButton.setOnClickListener(view -> {
            if (currentPage > 0) { currentPage--; renderPage(); }
        });
        nextButton.setOnClickListener(view -> {
            int pages = pageCount();
            if (currentPage + 1 < pages) { currentPage++; renderPage(); }
        });
        copyButton.setOnClickListener(view -> copyM3u());
        applyButton.setOnClickListener(view -> applySelection());
        cancelButton.setOnClickListener(view -> finish());
    }

    private void loadCatalogWithCache() {
        String manifestUrl = new ResolverPreferences(this).highflyManifestUrl();
        executor.execute(() -> {
            try {
                HighflyCatalog cached = repository.readCachedCatalog();
                handler.post(() -> applyCatalog(cached, true));
            } catch (Exception ignored) { }
            try {
                HighflyCatalog fresh = repository.loadCatalog(manifestUrl);
                handler.post(() -> applyCatalog(fresh, false));
            } catch (Exception error) {
                handler.post(() -> showStatus("No se pudo cargar Highfly: " + safeMessage(error)));
            }
        });
    }

    private void loadCachedIdentityIndex() {
        executor.execute(() -> {
            ChannelIdentityIndex value = ChannelIdentityIndex.loadCached(this);
            handler.post(() -> {
                identityIndex = value;
                renderPage();
            });
        });
    }

    private void refreshIdentityIndex() {
        if (refreshIdsButton != null) refreshIdsButton.setEnabled(false);
        showStatus(getString(R.string.catalog_refresh_ids_working));
        executor.execute(() -> {
            ChannelIdentityIndex.RefreshResult result = ChannelIdentityIndex.refresh(this);
            handler.post(() -> {
                identityIndex = result.getIndex();
                if (refreshIdsButton != null) refreshIdsButton.setEnabled(true);
                showIdentityStatus(result.getFailedSources());
                renderPage();
            });
        });
    }

    private void applyCatalog(HighflyCatalog value, boolean fromCache) {
        if (value == null || value.getChannels().isEmpty()) return;
        catalog = value;
        List<String> categories = new ArrayList<>();
        categories.add("Todos");
        categories.addAll(value.getCategories());
        String previous = categorySpinner.getSelectedItem() == null
                ? "Todos" : String.valueOf(categorySpinner.getSelectedItem());
        applyingCategory = true;
        categorySpinner.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, categories
        ) {{ setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); }});
        categorySpinner.setSelection(Math.max(0, categories.indexOf(previous)));
        applyingCategory = false;
        currentPage = 0;
        renderPage();
        showStatus((fromCache ? "Caché" : "Actualizado")
                + " · " + value.size() + " canales Highfly");
    }

    private void renderPage() {
        if (channelContainer == null) return;
        String focusedId = null;
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) {
            focusedId = String.valueOf(focused.getTag());
        }
        channelContainer.removeAllViews();
        if (catalog == null) {
            pageText.setText("Página 0/0");
            previousButton.setEnabled(false);
            nextButton.setEnabled(false);
            return;
        }
        String category = categorySpinner.getSelectedItem() == null
                ? "" : String.valueOf(categorySpinner.getSelectedItem());
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString();
        List<HighflyCatalogChannel> filtered = catalog.filter(query, category);
        if (selectedOnlyToggle.isChecked()) {
            List<HighflyCatalogChannel> selectedRows = new ArrayList<>();
            for (HighflyCatalogChannel channel : filtered) {
                if (selected.containsKey(channel.getResourceId())) selectedRows.add(channel);
            }
            filtered = selectedRows;
        }
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        currentPage = Math.max(0, Math.min(currentPage, pages - 1));
        int start = Math.min(filtered.size(), currentPage * PAGE_SIZE);
        int end = Math.min(filtered.size(), start + PAGE_SIZE);
        View focusTarget = null;
        for (int index = start; index < end; index++) {
            View row = addChannelRow(filtered.get(index));
            if (focusedId != null && focusedId.equals(row.getTag())) focusTarget = row;
        }
        if (focusTarget != null) {
            View target = focusTarget;
            target.post(target::requestFocus);
        }
        pageText.setText("Página " + (filtered.isEmpty() ? 0 : currentPage + 1)
                + "/" + (filtered.isEmpty() ? 0 : pages));
        previousButton.setEnabled(currentPage > 0);
        nextButton.setEnabled(currentPage + 1 < pages && !filtered.isEmpty());
    }

    private View addChannelRow(HighflyCatalogChannel channel) {
        CheckBox row = new CheckBox(this);
        row.setTag(channel.getResourceId());
        row.setText(channelLabel(channel));
        row.setTextColor(Color.WHITE);
        row.setTextSize(13);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setMinHeight(dp(62));
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.settings_section_card);
        row.setChecked(selected.containsKey(channel.getResourceId()));
        row.setOnCheckedChangeListener((button, checked) -> {
            if (checked) selected.put(channel.getResourceId(), channel);
            else selected.remove(channel.getResourceId());
            showStatus(selected.size() + " canales Highfly seleccionados");
            renderPage();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(62));
        params.bottomMargin = dp(4);
        channelContainer.addView(row, params);
        return row;
    }

    private String channelLabel(HighflyCatalogChannel channel) {
        StringBuilder value = new StringBuilder();
        int position = selectedPosition(channel.getResourceId());
        if (position > 0) {
            value.append(getString(R.string.catalog_selection_position, position))
                    .append(" · ");
        }
        value.append(channel.getName()).append('\n');
        String tvgId = identityIndex.tvgIdFor(channel);
        value.append(AppStrings.isBlank(tvgId)
                ? getString(R.string.catalog_id_pending)
                : getString(R.string.catalog_id_assigned, tvgId));
        return value.toString();
    }

    private int selectedPosition(String resourceId) {
        int position = 1;
        for (HighflyCatalogChannel selectedChannel : selected.values()) {
            if (selectedChannel.getResourceId().equals(resourceId)) return position;
            position++;
        }
        return 0;
    }

    private void showIdentityStatus(int failedSources) {
        int confirmed = 0;
        for (HighflyCatalogChannel channel : selected.values()) {
            if (!AppStrings.isBlank(identityIndex.tvgIdFor(channel))) confirmed++;
        }
        int pending = Math.max(0, selected.size() - confirmed);
        showStatus(failedSources > 0
                ? getString(R.string.catalog_ids_status_with_errors, confirmed, pending, failedSources)
                : getString(R.string.catalog_ids_status, confirmed, pending));
    }

    private void copyM3u() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Highfly M3U", toM3uSelection()
            ));
        }
        showStatus("Referencias copiadas · " + selected.size() + " canales");
    }

    private void applySelection() {
        selectionStore.apply(new ArrayList<>(selected.values()));
        GitHubSelectionPublisher.enqueue(this, "highfly-selection");
        setResult(RESULT_OK);
        finish();
    }

    private String toM3uSelection() {
        StringBuilder result = new StringBuilder("#EXTM3U\n");
        for (HighflyCatalogChannel channel : selected.values()) {
            result.append(channel.toM3uEntry()).append('\n');
        }
        return result.toString();
    }

    private int pageCount() {
        if (catalog == null) return 0;
        String category = categorySpinner.getSelectedItem() == null
                ? "" : String.valueOf(categorySpinner.getSelectedItem());
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString();
        int count = catalog.filter(query, category).size();
        if (selectedOnlyToggle.isChecked()) {
            count = 0;
            for (HighflyCatalogChannel channel : catalog.filter(query, category)) {
                if (selected.containsKey(channel.getResourceId())) count++;
            }
        }
        return (count + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private void showStatus(String value) {
        if (statusText != null) statusText.setText(value == null ? "" : value);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setFocusable(true);
        button.setMinHeight(0);
        button.setMinWidth(0);
        return button;
    }

    private void addWeighted(LinearLayout parent, View child, float weight, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, weight);
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "error desconocido" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "error desconocido" : message;
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }
}

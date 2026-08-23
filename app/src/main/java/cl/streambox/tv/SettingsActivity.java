package cl.streambox.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    public static final String PREFS = "streambox_settings";
    public static final String KEY_PLAYLIST_URL = "playlist_url";
    public static final String KEY_INVERT_CHANNEL_KEYS = "invert_channel_keys";
    public static final String KEY_NORMALIZE_VOLUME = "normalize_volume";

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        enterImmersiveMode();

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
        Button cancelButton = findViewById(R.id.cancel_button);
        Button saveButton = findViewById(R.id.save_button);
        tabs = new TextView[]{
                findViewById(R.id.tab_general),
                findViewById(R.id.tab_playback),
                findViewById(R.id.tab_source),
                findViewById(R.id.tab_interface),
                findViewById(R.id.tab_updates)
        };
        tabPages = new View[]{
                findViewById(R.id.tab_page_general),
                findViewById(R.id.tab_page_playback),
                findViewById(R.id.tab_page_source),
                findViewById(R.id.tab_page_interface),
                findViewById(R.id.tab_page_updates)
        };

        appUpdater = new AppUpdater(this, updateExecutor, mainHandler);
        urlInput.setText(existingUrl);
        urlInput.setSelection(urlInput.length());
        invertChannelKeys.setChecked(prefs.getBoolean(KEY_INVERT_CHANNEL_KEYS, false));
        normalizeVolume.setChecked(prefs.getBoolean(KEY_NORMALIZE_VOLUME, false));
        TextView versionText = findViewById(R.id.current_version);
        versionText.setText(getString(R.string.current_version, BuildConfig.VERSION_NAME));

        cancelButton.setVisibility(hasExistingUrl ? View.VISIBLE : View.GONE);
        cancelButton.setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> save());
        updateButton.setOnClickListener(v -> checkForUpdates());
        for (int index = 0; index < tabs.length; index++) {
            final int tabIndex = index;
            tabs[index].setOnClickListener(v -> showTab(tabIndex, true));
        }
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            save();
            return true;
        });

        if (hasExistingUrl) {
            showTab(0, false);
            normalizeVolume.requestFocus();
        } else {
            showTab(2, false);
            urlInput.requestFocus();
        }
    }

    private void showTab(int selectedIndex, boolean requestFocus) {
        if (tabs == null || tabPages == null) return;
        int safeIndex = Math.max(0, Math.min(selectedIndex, tabs.length - 1));
        for (int index = 0; index < tabs.length; index++) {
            boolean selected = index == safeIndex;
            tabs[index].setSelected(selected);
            tabs[index].setTextColor(getColor(selected ? R.color.black : R.color.white));
            tabPages[index].setVisibility(selected ? View.VISIBLE : View.GONE);
        }
        if (requestFocus) {
            View focusTarget;
            switch (safeIndex) {
                case 1:
                    focusTarget = invertChannelKeys;
                    break;
                case 2:
                    focusTarget = urlInput;
                    break;
                case 3:
                    focusTarget = findViewById(R.id.interface_info);
                    break;
                case 4:
                    focusTarget = updateButton;
                    break;
                default:
                    focusTarget = normalizeVolume;
                    break;
            }
            focusTarget.requestFocus();
        }
    }

    private void checkForUpdates() {
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
        setResult(RESULT_OK, new Intent().putExtra(KEY_PLAYLIST_URL, value));
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (!hasExistingUrl && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            return true;
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

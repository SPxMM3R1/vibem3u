package cl.streambox.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone TV-friendly MediaFlow settings screen. */
public final class MediaFlowSettingsActivity extends Activity {
    private MediaFlowPreferences preferences;
    private Switch enabled;
    private EditText origin;
    private EditText password;
    private TextView status;
    private boolean updatingToggle;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vibem3u-mediaflow-settings");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_mediaflow_settings);
        preferences = new MediaFlowPreferences(this);
        enabled = findViewById(R.id.mediaflow_enabled);
        origin = findViewById(R.id.mediaflow_origin);
        password = findViewById(R.id.mediaflow_password);
        status = findViewById(R.id.mediaflow_status);
        origin.setText(preferences.getOrigin());
        enabled.setChecked(preferences.isEnabled());
        if (preferences.hasApiPassword()) password.setHint(R.string.mediaflow_password_hint);
        updateDisabledStatus();

        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (updatingToggle) return;
            if (!checked) {
                preferences.setEnabled(false);
                updateDisabledStatus();
                return;
            }
            if (!saveConfiguration(true)) {
                updatingToggle = true;
                enabled.setChecked(false);
                updatingToggle = false;
            }
        });
        Button save = findViewById(R.id.mediaflow_save);
        save.setOnClickListener(view -> saveConfiguration(enabled.isChecked()));
        Button test = findViewById(R.id.mediaflow_test);
        test.setOnClickListener(this::testServer);
    }

    private boolean saveConfiguration(boolean shouldEnable) {
        String originText = origin.getText() == null ? "" : origin.getText().toString().trim();
        try {
            // Disabling is always allowed and must not be blocked by an
            // invalid value currently typed into the origin field. Keep the
            // stored origin and encrypted password for a later re-enable.
            if (!shouldEnable) {
                preferences.setEnabled(false);
                updateDisabledStatus();
                return true;
            }
            if (!originText.isEmpty()) preferences.setOrigin(originText);
            if (preferences.getOriginUri() == null) {
                status.setText(R.string.mediaflow_status_invalid);
                return false;
            }
            String typedPassword = password.getText() == null
                    ? "" : password.getText().toString();
            if (!typedPassword.isEmpty()) preferences.setApiPassword(typedPassword);
            if (!preferences.hasApiPassword()) {
                status.setText(R.string.mediaflow_status_invalid);
                return false;
            }
            preferences.setEnabled(shouldEnable);
            if (shouldEnable) {
                status.setText(R.string.mediaflow_status_saved);
                password.setText("");
            } else {
                updateDisabledStatus();
            }
            return true;
        } catch (Exception error) {
            status.setText(getString(
                    R.string.mediaflow_status_error,
                    SafePlaybackText.detail(error.getMessage())
            ));
            return false;
        }
    }

    private void testServer(View ignored) {
        URI configured;
        try {
            configured = MediaFlowOriginPolicy.parseOrigin(
                    origin.getText() == null ? "" : origin.getText().toString()
            );
        } catch (Exception error) {
            status.setText(R.string.mediaflow_status_invalid);
            return;
        }
        status.setText(R.string.mediaflow_status_testing);
        worker.execute(() -> {
            try {
                URI health = configured.resolve("/health");
                new MediaFlowHttpClient().get(health, configured, Collections.emptyMap());
                runOnUiThread(() -> status.setText(R.string.mediaflow_status_reachable));
            } catch (Exception error) {
                String message = SafePlaybackText.detail(error.getMessage());
                runOnUiThread(() -> status.setText(getString(
                        R.string.mediaflow_status_unreachable,
                        message
                )));
            }
        });
    }

    private void updateDisabledStatus() {
        if (!preferences.isEnabled()) status.setText(R.string.mediaflow_status_disabled);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}

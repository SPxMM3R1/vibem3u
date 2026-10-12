package cl.streambox.tv;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Escenas de «Este canal» (0.5.90): confirmar la papelera y vincular GitHub con el teléfono. */
final class ChannelActionScenes {
    private ChannelActionScenes() {}

    /** Confirmación; el foco parte en Cancelar para que un OK de más no borre nada. */
    static Dialog showTrashConfirm(Activity activity, String channelName, Runnable onConfirm) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_scene);
        ((TextView) dialog.findViewById(R.id.scene_kicker)).setText(R.string.github_link_kicker);
        ((TextView) dialog.findViewById(R.id.scene_title)).setText(
                activity.getString(R.string.channel_trash_confirm_title, channelName));
        ((TextView) dialog.findViewById(R.id.scene_message)).setText(R.string.channel_trash_confirm_message);
        Button primary = dialog.findViewById(R.id.scene_primary);
        Button secondary = dialog.findViewById(R.id.scene_secondary);
        primary.setText(R.string.channel_trash_confirm);
        secondary.setText(R.string.cancel);
        primary.setOnClickListener(view -> {
            dialog.dismiss();
            onConfirm.run();
        });
        secondary.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> secondary.requestFocus());
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        return dialog;
    }

    /**
     * Vinculación por código (GitHub Device Flow). Al autorizar, guarda el token cifrado y corre
     * {@code onLinked} en el hilo principal. Cancelar o Atrás detienen la espera.
     */
    static Dialog showGitHubLink(Activity activity, Runnable onLinked) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_github_link);
        ImageView qr = dialog.findViewById(R.id.github_link_qr);
        TextView address = dialog.findViewById(R.id.github_link_address);
        TextView code = dialog.findViewById(R.id.github_link_code);
        TextView status = dialog.findViewById(R.id.github_link_status);
        Button cancel = dialog.findViewById(R.id.github_link_cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> cancel.requestFocus());
        address.setText(GitHubChannelTrash.VERIFICATION_URI.replace("https://", ""));
        try {
            qr.setImageBitmap(PremiumScenes.qrBitmap(GitHubChannelTrash.VERIFICATION_URI, 480));
        } catch (Exception unavailable) {
            qr.setVisibility(View.GONE);
        }
        code.setText("····-····");

        AtomicBoolean cancelled = new AtomicBoolean(false);
        dialog.setOnDismissListener(ignored -> cancelled.set(true));
        Handler main = new Handler(Looper.getMainLooper());
        if (!GitHubChannelTrash.isConfigured()) {
            status.setText(R.string.github_link_unconfigured);
        } else {
            status.setText(R.string.github_link_requesting);
            Thread worker = new Thread(() -> {
                GitHubChannelTrash github = new GitHubChannelTrash();
                try {
                    GitHubChannelTrash.DeviceCode device = github.requestDeviceCode();
                    main.post(() -> {
                        code.setText(device.userCode);
                        status.setText(R.string.github_link_waiting);
                    });
                    long[] interval = {device.intervalMillis};
                    while (!cancelled.get() && System.currentTimeMillis() < device.expiresAtMillis) {
                        Thread.sleep(interval[0]);
                        if (cancelled.get()) return;
                        String token = github.pollToken(device, interval);
                        if (token == null) continue;
                        new GitHubCredentialStore(activity).save(token);
                        main.post(() -> {
                            if (dialog.isShowing()) dialog.dismiss();
                            onLinked.run();
                        });
                        return;
                    }
                    if (!cancelled.get()) throw new IOException("El código de GitHub venció.");
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                } catch (IOException | GeneralSecurityException error) {
                    String message = AppStrings.isBlank(error.getMessage())
                            ? "No se pudo vincular GitHub." : error.getMessage();
                    main.post(() -> status.setText(message));
                }
            }, "vibem3u-github-link");
            worker.setDaemon(true);
            worker.start();
        }
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        return dialog;
    }
}

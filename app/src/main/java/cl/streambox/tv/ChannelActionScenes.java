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
import java.util.Locale;
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
        // Acción peligrosa en rosa (DISENO.md); el foco sigue partiendo en Cancelar.
        primary.setBackgroundResource(R.drawable.scene_button_danger);
        primary.setTextColor(activity.getColorStateList(R.color.scene_button_danger_text));
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
     * Canal con respaldos (0.6.57): pasar el siguiente a principal o borrar todo. El foco parte en
     * Cancelar; el detalle de abajo explica la opción marcada.
     */
    static Dialog showTrashWithBackups(Activity activity, String channelName, int backups, String nextBackup,
            Runnable onPromote, Runnable onTrashAll) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_scene);
        ((TextView) dialog.findViewById(R.id.scene_kicker)).setText(R.string.github_link_kicker);
        ((TextView) dialog.findViewById(R.id.scene_title)).setText(
                activity.getString(R.string.channel_trash_confirm_title, channelName));
        ((TextView) dialog.findViewById(R.id.scene_message)).setText(backups == 1
                ? activity.getString(R.string.channel_trash_backups_message_one, nextBackup)
                : activity.getString(R.string.channel_trash_backups_message, backups, nextBackup));
        TextView detail = dialog.findViewById(R.id.scene_detail);
        Button promote = dialog.findViewById(R.id.scene_primary);
        Button trashAll = dialog.findViewById(R.id.scene_danger);
        Button cancel = dialog.findViewById(R.id.scene_secondary);
        promote.setText(R.string.channel_trash_promote);
        trashAll.setText(R.string.channel_trash_all);
        cancel.setText(R.string.cancel);
        trashAll.setVisibility(View.VISIBLE);
        String promoteDetail = backups == 1
                ? activity.getString(R.string.channel_trash_promote_detail_none, channelName)
                : backups == 2
                ? activity.getString(R.string.channel_trash_promote_detail_one, channelName)
                : activity.getString(R.string.channel_trash_promote_detail, channelName, backups - 1);
        String trashAllDetail = backups == 1
                ? activity.getString(R.string.channel_trash_all_detail_one, channelName)
                : activity.getString(R.string.channel_trash_all_detail, channelName, backups);
        // Invisible y no ausente: la escena crece desde abajo y los botones no deben saltar.
        detail.setVisibility(View.INVISIBLE);
        showDetailOnFocus(promote, detail, promoteDetail);
        showDetailOnFocus(trashAll, detail, trashAllDetail);
        cancel.setOnFocusChangeListener((view, focused) -> {
            if (focused) detail.setVisibility(View.INVISIBLE);
        });
        promote.setOnClickListener(view -> {
            dialog.dismiss();
            onPromote.run();
        });
        trashAll.setOnClickListener(view -> {
            dialog.dismiss();
            onTrashAll.run();
        });
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> cancel.requestFocus());
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        return dialog;
    }

    private static void showDetailOnFocus(Button button, TextView detail, String text) {
        button.setOnFocusChangeListener((view, focused) -> {
            if (!focused) return;
            detail.setText(text);
            detail.setVisibility(View.VISIBLE);
        });
    }

    /** «Cinecanal 38.87.224.19 · 1080p · 3.0 Mbps»; sin medición, solo el nombre y el servidor. */
    static String describeBackup(String name, String host, PublishedDirectBackups.Quality quality) {
        StringBuilder text = new StringBuilder(name == null ? "" : name);
        if (host != null && !host.isEmpty()) text.append(' ').append(host);
        if (quality != null && quality.ok) {
            text.append(" · ").append(quality.height).append('p');
            if (quality.kbps > 0) {
                text.append(" · ").append(String.format(Locale.ROOT, "%.1f Mbps", quality.kbps / 1000f));
            }
        }
        return text.toString();
    }

    /** Evita que abrir la fila de estado «Vinculado» inicie un QR sin confirmación explícita. */
    static Dialog showGitHubRelinkConfirm(Activity activity, Runnable onConfirm) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_scene);
        ((TextView) dialog.findViewById(R.id.scene_kicker)).setText(R.string.github_link_kicker);
        ((TextView) dialog.findViewById(R.id.scene_title)).setText(R.string.github_link_relink_title);
        ((TextView) dialog.findViewById(R.id.scene_message)).setText(R.string.github_link_relink_message);
        Button primary = dialog.findViewById(R.id.scene_primary);
        Button secondary = dialog.findViewById(R.id.scene_secondary);
        primary.setText(R.string.github_link_relink_action);
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
                        GitHubCredentialStore.Credentials credentials = github.pollCredentials(device, interval);
                        if (credentials == null) continue;
                        new GitHubCredentialStore(activity).save(credentials);
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

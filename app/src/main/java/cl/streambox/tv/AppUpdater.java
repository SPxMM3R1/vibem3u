package cl.streambox.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

final class AppUpdater {
    static final int UNKNOWN_SOURCES_REQUEST = 1002;

    private final Activity activity;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final UpdateRepository repository = new UpdateRepository();

    private Dialog updateDialog;
    private UpdateInfo availableUpdate;
    private File pendingApk;
    private String deferredDialogMessage;
    private boolean checkStarted;
    private boolean downloading;
    private boolean installRequested;
    private boolean awaitingUnknownSources;
    private boolean hostResumed;
    private boolean destroyed;

    AppUpdater(Activity activity, ExecutorService executor, Handler mainHandler) {
        this.activity = activity;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    void checkForUpdates() {
        if (checkStarted || destroyed) return;
        checkStarted = true;
        executor.submit(() -> {
            try {
                UpdateInfo update = repository.findAvailableUpdate(currentVersionName());
                if (update != null) {
                    mainHandler.post(() -> {
                        availableUpdate = update;
                        if (canPresentDialog()) showUpdateDialog(update, null);
                    });
                }
            } catch (Exception ignored) {
                // Una caída de GitHub nunca debe interrumpir la reproducción.
            }
        });
    }

    boolean onActivityResult(int requestCode) {
        if (requestCode != UNKNOWN_SOURCES_REQUEST) return false;
        awaitingUnknownSources = false;
        if (pendingApk == null || !pendingApk.isFile()) return true;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls()) {
            launchInstaller(pendingApk);
        } else if (availableUpdate != null) {
            installRequested = false;
            deferredDialogMessage = activity.getString(R.string.update_permission_required);
        }
        return true;
    }

    void onHostResume() {
        hostResumed = true;
        if (awaitingUnknownSources && pendingApk != null && pendingApk.isFile()) {
            awaitingUnknownSources = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || activity.getPackageManager().canRequestPackageInstalls()) {
                launchInstaller(pendingApk);
                return;
            }
            installRequested = false;
            deferredDialogMessage =
                    activity.getString(R.string.update_permission_required);
        }

        if (installRequested && pendingApk != null && pendingApk.isFile()) {
            requestInstallPermissionOrLaunch();
            return;
        }

        if (availableUpdate != null && deferredDialogMessage != null) {
            String message = deferredDialogMessage;
            deferredDialogMessage = null;
            showUpdateDialog(availableUpdate, message);
        } else if (availableUpdate != null
                && pendingApk == null
                && updateDialog == null
                && !downloading) {
            showUpdateDialog(availableUpdate, null);
        }
    }

    void onHostPause() {
        hostResumed = false;
    }

    void destroy() {
        destroyed = true;
        if (updateDialog != null) {
            updateDialog.dismiss();
            updateDialog = null;
        }
    }

    private void showUpdateDialog(UpdateInfo update, String statusMessage) {
        if (!canPresentDialog() || downloading) return;
        availableUpdate = update;
        if (updateDialog != null) updateDialog.dismiss();

        Dialog dialog = new Dialog(activity);
        updateDialog = dialog;
        dialog.setContentView(R.layout.dialog_update);
        dialog.setCanceledOnTouchOutside(false);

        TextView message = dialog.findViewById(R.id.update_message);
        ProgressBar progress = dialog.findViewById(R.id.update_progress);
        Button laterButton = dialog.findViewById(R.id.update_later_button);
        Button installButton = dialog.findViewById(R.id.update_install_button);

        message.setText(statusMessage == null
                ? activity.getString(R.string.update_available, update.getVersionName())
                : statusMessage);
        progress.setVisibility(View.GONE);
        laterButton.setOnClickListener(view -> dialog.dismiss());
        installButton.setOnClickListener(view -> {
            if (pendingApk != null && pendingApk.isFile()) {
                installRequested = true;
                dialog.dismiss();
                requestInstallPermissionOrLaunch();
            } else {
                downloadAndInstall(update, message, progress, laterButton, installButton);
            }
        });
        dialog.setOnShowListener(ignored -> installButton.requestFocus());
        dialog.setOnDismissListener(ignored -> {
            if (updateDialog == dialog) updateDialog = null;
        });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.68f;
            window.setAttributes(attributes);
        }
        dialog.show();
    }

    private void downloadAndInstall(
            UpdateInfo update,
            TextView message,
            ProgressBar progress,
            Button laterButton,
            Button installButton
    ) {
        if (downloading) return;
        downloading = true;
        installRequested = true;
        progress.setIndeterminate(update.getSizeBytes() <= 0);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        message.setText(activity.getString(R.string.update_downloading, 0));
        laterButton.setEnabled(false);
        installButton.setEnabled(false);

        executor.submit(() -> {
            try {
                File apk = repository.download(update, activity.getCacheDir(), percent ->
                        mainHandler.post(() -> {
                            if (destroyed || updateDialog == null) return;
                            progress.setIndeterminate(false);
                            progress.setProgress(percent);
                            message.setText(activity.getString(
                                    R.string.update_downloading,
                                    percent
                            ));
                        }));
                verifyDownloadedApk(apk);
                pendingApk = apk;
                mainHandler.post(() -> {
                    downloading = false;
                    if (!canUseActivity()) return;
                    if (updateDialog != null) updateDialog.dismiss();
                    if (hostResumed) requestInstallPermissionOrLaunch();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    downloading = false;
                    installRequested = false;
                    if (!canUseActivity()) return;
                    progress.setVisibility(View.GONE);
                    message.setText(activity.getString(
                            R.string.update_download_error,
                            shortMessage(error)
                    ));
                    laterButton.setEnabled(true);
                    installButton.setEnabled(true);
                    installButton.setText(R.string.retry);
                    installButton.requestFocus();
                });
            }
        });
    }

    private void requestInstallPermissionOrLaunch() {
        if (pendingApk == null || !pendingApk.isFile()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls()) {
            launchInstaller(pendingApk);
            return;
        }

        Intent settingsIntent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())
        );
        if (settingsIntent.resolveActivity(activity.getPackageManager()) == null) {
            installRequested = false;
            if (availableUpdate != null) {
                showUpdateDialog(
                        availableUpdate,
                        activity.getString(R.string.update_permission_unavailable)
                );
            }
            return;
        }
        awaitingUnknownSources = true;
        activity.startActivityForResult(settingsIntent, UNKNOWN_SOURCES_REQUEST);
    }

    private void launchInstaller(File apk) {
        try {
            Uri contentUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apk
            );
            Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            installIntent.setData(contentUri);
            installIntent.setClipData(ClipData.newRawUri("VibeM3U", contentUri));
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (installIntent.resolveActivity(activity.getPackageManager()) == null) {
                throw new IllegalStateException("No hay un instalador de APK disponible.");
            }
            installRequested = false;
            activity.startActivity(installIntent);
        } catch (Exception error) {
            installRequested = false;
            if (availableUpdate != null && canUseActivity()) {
                String message = activity.getString(
                        R.string.update_download_error,
                        shortMessage(error)
                );
                if (canPresentDialog()) {
                    showUpdateDialog(availableUpdate, message);
                } else {
                    deferredDialogMessage = message;
                }
            }
        }
    }

    private void verifyDownloadedApk(File apk) throws Exception {
        PackageManager packageManager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;

        PackageInfo candidate = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = packageManager.getPackageInfo(activity.getPackageName(), flags);
        if (candidate == null || !activity.getPackageName().equals(candidate.packageName)) {
            throw new SecurityException("El APK no pertenece a VibeM3U.");
        }

        long candidateVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? candidate.getLongVersionCode()
                : candidate.versionCode;
        long installedVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? installed.getLongVersionCode()
                : installed.versionCode;
        if (candidateVersion <= installedVersion) {
            throw new SecurityException("El APK no es una versión más reciente.");
        }

        Set<String> installedSigners = signerDigests(installed);
        Set<String> candidateSigners = signerDigests(candidate);
        if (installedSigners.isEmpty() || !installedSigners.equals(candidateSigners)) {
            throw new SecurityException("La firma del APK no coincide con la instalación.");
        }
    }

    private static Set<String> signerDigests(PackageInfo packageInfo) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (packageInfo.signingInfo == null) return new HashSet<>();
            signatures = packageInfo.signingInfo.getApkContentsSigners();
        } else {
            signatures = packageInfo.signatures;
        }

        Set<String> result = new HashSet<>();
        if (signatures == null) return result;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Signature signature : signatures) {
            result.add(toHex(digest.digest(signature.toByteArray())));
        }
        return result;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private boolean canUseActivity() {
        return !destroyed && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                || !activity.isDestroyed());
    }

    private String currentVersionName() {
        try {
            String versionName = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0)
                    .versionName;
            return versionName == null ? "0.0.0" : versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "0.0.0";
        }
    }

    private boolean canPresentDialog() {
        return hostResumed && canUseActivity();
    }

    private static String shortMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "Error desconocido." : message;
    }
}

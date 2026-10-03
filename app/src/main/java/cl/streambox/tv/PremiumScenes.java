package cl.streambox.tv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/** Escenas de Highfly Premium: vincular con QR, token vencido y gestionar la cuenta. */
final class PremiumScenes {
    private static final long PAIRING_WINDOW_MS = 5 * 60_000L;
    private static final long STATUS_HOLD_MS = 6_000L;

    private PremiumScenes() {}

    /** Diálogo de vinculación con QR; {@code onLinked} corre en el hilo principal. */
    static Dialog showPairing(Activity activity, Runnable onLinked) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_premium_pair);
        ImageView qr = dialog.findViewById(R.id.premium_pair_qr);
        TextView address = dialog.findViewById(R.id.premium_pair_address);
        TextView status = dialog.findViewById(R.id.premium_pair_status);
        TextView[] digits = {
                dialog.findViewById(R.id.premium_pair_digit_0),
                dialog.findViewById(R.id.premium_pair_digit_1),
                dialog.findViewById(R.id.premium_pair_digit_2),
                dialog.findViewById(R.id.premium_pair_digit_3)
        };
        Button cancel = dialog.findViewById(R.id.premium_pair_cancel_button);
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> cancel.requestFocus());

        Handler main = new Handler(Looper.getMainLooper());
        HighflyPremiumCredentialStore store = HighflyPremiumCredentialStore.getInstance(activity);
        long deadline = SystemClock.elapsedRealtime() + PAIRING_WINDOW_MS;
        long[] holdUntil = {0L};

        String ip = PremiumPairingServer.localAddress(activity);
        PremiumPairingServer server = ip == null ? null : new PremiumPairingServer(
                new PremiumPairingServer.Listener() {
                    @Override
                    public PremiumPairingServer.Outcome onToken(HighflyPremiumTokenRules.ParsedInput input) {
                        main.post(() -> {
                            holdUntil[0] = Long.MAX_VALUE;
                            status.setText(R.string.premium_pair_verifying);
                        });
                        HighflyPremiumRegion region = input.getRegion() != null
                                ? input.getRegion() : store.region();
                        try {
                            HighflyPremiumAccount account =
                                    new HighflyPremiumClient(new TokenHttpClient()).verify(input.getToken(), region);
                            store.saveVerified(input.getToken(), account, input.getRegion());
                            HighflyPremiumSession.reset();
                            return new PremiumPairingServer.Outcome(true,
                                    activity.getString(R.string.premium_pair_phone_done));
                        } catch (HighflyPremiumClient.RejectedException rejected) {
                            holdStatus(main, status, holdUntil, R.string.premium_pair_rejected);
                            return new PremiumPairingServer.Outcome(false,
                                    activity.getString(R.string.premium_pair_rejected));
                        } catch (IOException unavailable) {
                            holdStatus(main, status, holdUntil, R.string.premium_pair_unreachable);
                            return new PremiumPairingServer.Outcome(false,
                                    activity.getString(R.string.premium_pair_unreachable));
                        }
                    }

                    @Override
                    public void onClosed(boolean ok, boolean tooManyAttempts) {
                        main.post(() -> {
                            if (ok) {
                                if (dialog.isShowing()) dialog.dismiss();
                                onLinked.run();
                            } else if (tooManyAttempts && dialog.isShowing()) {
                                holdUntil[0] = Long.MAX_VALUE;
                                status.setText(R.string.premium_pair_locked);
                            }
                        });
                    }
                });

        if (server == null) {
            qr.setVisibility(View.GONE);
            address.setVisibility(View.GONE);
            for (TextView digit : digits) digit.setText("–");
            status.setText(R.string.premium_pair_no_network);
        } else {
            try {
                int port = server.start();
                String url = "http://" + ip + ":" + port + "/";
                qr.setImageBitmap(qrBitmap(url, 480));
                address.setText(activity.getString(R.string.premium_pair_address, ip + ":" + port));
                String code = server.code();
                for (int index = 0; index < 4; index++) {
                    digits[index].setText(String.valueOf(code.charAt(index)));
                }
            } catch (IOException | WriterException error) {
                server.stop();
                qr.setVisibility(View.GONE);
                address.setVisibility(View.GONE);
                status.setText(R.string.premium_pair_no_network);
            }
        }

        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0L) {
                    dialog.dismiss();
                    return;
                }
                if (server != null && SystemClock.elapsedRealtime() >= holdUntil[0]) {
                    long seconds = (left + 999L) / 1000L;
                    status.setText(activity.getString(R.string.premium_pair_waiting,
                            String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)));
                }
                main.postDelayed(this, 1000L);
            }
        };
        dialog.setOnDismissListener(ignored -> {
            main.removeCallbacks(tick);
            if (server != null) server.stop();
        });
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        tick.run();
        return dialog;
    }

    /** Aviso al fallar un canal porque Highfly rechazó el token. */
    static Dialog showRejected(Activity activity, String channelName, Runnable relink, Runnable watchFree) {
        HighflyPremiumCredentialStore.State state =
                HighflyPremiumCredentialStore.getInstance(activity).state();
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_scene);
        boolean expired = state.expiresAtMillis > 0L && state.expiresAtMillis <= System.currentTimeMillis();
        ((View) dialog.findViewById(R.id.scene_kicker_line)).setBackgroundColor(
                activity.getColor(R.color.amber));
        String kicker = activity.getString(R.string.premium_kicker);
        if (!AppStrings.isBlank(channelName)) kicker += " · " + channelName.trim();
        ((TextView) dialog.findViewById(R.id.scene_kicker)).setText(kicker);
        ((TextView) dialog.findViewById(R.id.scene_title)).setText(expired
                ? R.string.premium_expired_title : R.string.premium_rejected_title);
        ((TextView) dialog.findViewById(R.id.scene_message)).setText(expired
                ? activity.getString(R.string.premium_expired_message, date(state.expiresAtMillis))
                : activity.getString(R.string.premium_rejected_message));
        Button primary = dialog.findViewById(R.id.scene_primary);
        Button secondary = dialog.findViewById(R.id.scene_secondary);
        primary.setText(R.string.premium_relink);
        secondary.setText(R.string.premium_watch_free);
        primary.setOnClickListener(view -> {
            dialog.dismiss();
            relink.run();
        });
        secondary.setOnClickListener(view -> {
            dialog.dismiss();
            watchFree.run();
        });
        dialog.setOnShowListener(ignored -> primary.requestFocus());
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        return dialog;
    }

    /** Cuenta vinculada: vincular otro token o desvincular. */
    static Dialog showManage(Activity activity, Runnable relink, Runnable onUnlinked) {
        Dialog dialog = SceneDialog.create(activity, R.layout.dialog_scene);
        ((TextView) dialog.findViewById(R.id.scene_kicker)).setText(R.string.premium_kicker);
        ((TextView) dialog.findViewById(R.id.scene_title)).setText(R.string.premium_manage_title);
        ((TextView) dialog.findViewById(R.id.scene_message)).setText(R.string.premium_manage_message);
        Button primary = dialog.findViewById(R.id.scene_primary);
        Button secondary = dialog.findViewById(R.id.scene_secondary);
        primary.setText(R.string.premium_relink_other);
        secondary.setText(R.string.premium_unlink);
        primary.setOnClickListener(view -> {
            dialog.dismiss();
            relink.run();
        });
        secondary.setOnClickListener(view -> {
            HighflyPremiumCredentialStore.getInstance(activity).clear();
            HighflyPremiumSession.reset();
            dialog.dismiss();
            onUnlinked.run();
        });
        dialog.setOnShowListener(ignored -> primary.requestFocus());
        dialog.show();
        SceneDialog.hideSystemBars(dialog);
        return dialog;
    }

    static String date(long millis) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag("es-CL"))
                .format(new Date(millis));
    }

    private static void holdStatus(Handler main, TextView status, long[] holdUntil, int text) {
        main.post(() -> {
            holdUntil[0] = SystemClock.elapsedRealtime() + STATUS_HOLD_MS;
            status.setText(text);
        });
    }

    private static Bitmap qrBitmap(String text, int size) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                Collections.singletonMap(EncodeHintType.MARGIN, 0));
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);
    }
}

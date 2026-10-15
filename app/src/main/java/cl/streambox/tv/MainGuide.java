package cl.streambox.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.ui.PlayerView;

import okhttp3.OkHttpClient;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guía de programación.
 * Capa de MainActivity (separada en la 0.6.03; el código se movió tal cual).
 */
@UnstableApi
abstract class MainGuide extends MainOverlays {
    boolean isGuideVisible() {
        return guideView != null && guideView.getVisibility() == View.VISIBLE;
    }

    /** Guía completa: filas de canales y bloques de programas; OK abre el canal enfocado. */
    void openGuide() {
        if (channels.isEmpty() || guideView == null || settingsOpen || exiting) return;
        closePlaybackSourceSelector();
        // Con la pantalla de error a la vista (la tecla GUÍA llega igual): se guarda y vuelve
        // al cerrar la Guía, para que no quede encima de la grilla (0.6.01).
        if (isPlaybackErrorVisible()) {
            playbackErrorOverlay.setVisibility(View.GONE);
            playbackErrorDeferred = true;
        }
        // Moderno (0.5.99): la carga del canal no se trasluce detrás de la Guía; desde la 0.6.00
        // tampoco el OSD (se veía tenue bajo la grilla).
        if (!classicUi && loadingPanel != null) loadingPanel.setAlpha(0f);
        if (!classicUi) hideOverlay.run();
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        hideProgrammeDetail.run();
        mainHandler.removeCallbacks(hideOverlay);
        overlayAwaitingPlayback = false;
        hideOverlay.run();
        long now = System.currentTimeMillis();
        rebuildGuideFilters();
        guideFiltersFocused = false;
        guideNavigator = new EpgGuideNavigator(guideRows.size(), guidePlayingRow(), now);
        refreshReminderIds();
        guideOkDown = false;
        guideOkLongHandled = false;
        guideSurface.bind(guideSource, guideNavigator, now);
        guideView.setVisibility(View.VISIBLE);
    }

    /** «Todos» más las categorías (grupos) de la lista, en el orden en que aparecen. */
    void rebuildGuideFilters() {
        String selected = guideFilterIndex > 0 && guideFilterIndex < guideFilters.size()
                ? guideFilters.get(guideFilterIndex) : null;
        guideFilters.clear();
        guideFilters.add(getString(R.string.guide_filter_all));
        Set<String> seen = new HashSet<>();
        for (Channel channel : channels) {
            String group = channel.getGroup();
            if (!AppStrings.isBlank(group) && seen.add(group.trim())) guideFilters.add(group.trim());
        }
        guideFilterIndex = selected == null ? 0 : Math.max(0, guideFilters.indexOf(selected));
        rebuildGuideRows();
    }

    void rebuildGuideRows() {
        guideRows.clear();
        String filter = guideFilterIndex > 0 && guideFilterIndex < guideFilters.size()
                ? guideFilters.get(guideFilterIndex) : null;
        for (int index = 0; index < channels.size(); index++) {
            String group = channels.get(index).getGroup();
            if (filter == null || (group != null && filter.equals(group.trim()))) guideRows.add(index);
        }
    }

    /** Fila del canal en reproducción dentro del filtro actual, o la primera. */
    int guidePlayingRow() {
        return Math.max(0, guideRows.indexOf(channelIndex));
    }

    /** Índice en {@link #channels} de una fila de la guía, o -1. */
    int guideChannelIndex(int row) {
        return row < 0 || row >= guideRows.size() ? -1 : guideRows.get(row);
    }

    Channel guideChannel(int row) {
        int index = guideChannelIndex(row);
        return index < 0 || index >= channels.size() ? null : channels.get(index);
    }

    void changeGuideFilter(int delta) {
        int next = Math.max(0, Math.min(guideFilters.size() - 1, guideFilterIndex + delta));
        if (next == guideFilterIndex) return;
        guideFilterIndex = next;
        rebuildGuideRows();
        long now = System.currentTimeMillis();
        guideNavigator = new EpgGuideNavigator(guideRows.size(), guidePlayingRow(), now);
        guideSurface.bind(guideSource, guideNavigator, now);
    }

    /** Logo de la guía: si no está en memoria se carga en segundo plano y se redibuja. */
    android.graphics.Bitmap guideLogo(Channel channel) {
        URI logoUri = channel.getLogoUri();
        if (logoUri == null || !("http".equalsIgnoreCase(logoUri.getScheme())
                || "https".equalsIgnoreCase(logoUri.getScheme()))) return null;
        String key = logoUri.toString();
        android.graphics.Bitmap bitmap = guideLogos.get(key);
        if (bitmap != null || !guideLogoRequests.add(key)) return bitmap;
        int width = dpToPx(90 * LogoFit.LOGO_SCALE);
        int height = dpToPx(36 * LogoFit.LOGO_SCALE);
        logoCacheExecutor.submit(() -> {
            android.graphics.Bitmap loaded = channelLogoCache.loadCached(logoUri, width, height);
            if (loaded == null) {
                try {
                    loaded = channelLogoCache.load(logoUri, width, height);
                } catch (Exception ignored) {
                    // Sin logo: la guía muestra el nombre del canal.
                }
            }
            android.graphics.Bitmap result = LogoFit.trim(loaded, logoUri);
            mainHandler.post(() -> {
                if (result == null) {
                    guideLogoRequests.remove(key);
                    return;
                }
                guideLogos.put(key, result);
                if (isGuideVisible()) guideView.invalidate();
            });
        });
        return null;
    }

    void closeGuide() {
        if (guideView != null) guideView.setVisibility(View.GONE);
        if (loadingPanel != null) loadingPanel.setAlpha(blackScreenDimmed ? BLACK_SCREEN_ALPHA : 1f);
        // Después de lo que hizo cerrar la Guía: si eligió otro canal, playChannel ya descartó
        // el error pendiente y no se muestra nada.
        if (playbackErrorDeferred) mainHandler.post(showDeferredPlaybackError);
    }

    final Runnable showDeferredPlaybackError = () -> {
        if (!playbackErrorDeferred || isGuideVisible() || settingsOpen || exiting) return;
        mainHandler.removeCallbacks(hideOverlay);
        hideOverlay.run();
        presentPlaybackErrorScreen();
    };

    final EpgGuideView.Source guideSource = new EpgGuideView.Source() {
        @Override public int rowCount() { return guideRows.size(); }

        @Override public String number(int row) {
            Channel channel = guideChannel(row);
            if (channel == null) return "";
            int number = publishedPlaybackCatalog.numberFor(channel, guideChannelIndex(row) + 1);
            return String.format(Locale.ROOT, "%03d", number);
        }

        @Override public String name(int row) {
            Channel channel = guideChannel(row);
            return channel == null ? "" : channel.getName();
        }

        @Override public String category(int row) {
            Channel channel = guideChannel(row);
            return channel == null || AppStrings.isBlank(channel.getGroup()) ? "" : channel.getGroup().trim();
        }

        @Override public android.graphics.Bitmap logo(int row) {
            Channel channel = guideChannel(row);
            return channel == null ? null : guideLogo(channel);
        }

        @Override public List<EpgProgramme> programmes(int row, long fromMillis, long toMillis) {
            Channel channel = guideChannel(row);
            if (channel == null) return java.util.Collections.emptyList();
            return epgData.findInWindow(channel.getTvgId(), fromMillis, toMillis);
        }

        @Override public int playingRow() { return guideRows.indexOf(channelIndex); }

        @Override public boolean hasReminder(int row, EpgProgramme programme) {
            Channel channel = guideChannel(row);
            if (channel == null || reminderIds.isEmpty()) return false;
            return reminderIds.contains(PlaybackPreferences.channelIdentity(channel)
                    + "@" + programme.getStartMillis());
        }

        @Override public List<String> filters() { return guideFilters; }

        @Override public int selectedFilter() { return guideFilterIndex; }

        @Override public boolean filtersFocused() { return guideFiltersFocused; }
    };

    /** Programas de la fila enfocada en un rango amplio para saltar entre bloques. */
    List<EpgProgramme> guideRowProgrammes() {
        Channel channel = guideChannel(guideNavigator.getRow());
        if (channel == null) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        return epgData.findInWindow(channel.getTvgId(),
                now - EpgGuideNavigator.WINDOW_MILLIS,
                now + EpgGuideNavigator.MAX_AHEAD_MILLIS + EpgGuideNavigator.WINDOW_MILLIS);
    }

    boolean handleGuideKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_GUIDE) {
            if (down && event.getRepeatCount() == 0) {
                closeGuide();
                if (keyCode != KeyEvent.KEYCODE_GUIDE) markBackConsumedByPanel();
            }
            return true;
        }
        if (guideFiltersFocused) return handleGuideFilterKey(keyCode, down);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!down) return true;
                if (guideNavigator.moveRow(-1)) {
                    guideSurface.refresh(System.currentTimeMillis());
                } else if (event.getRepeatCount() == 0 && guideFilters.size() > 1 && !classicUi) {
                    // Arriba desde el primer canal: foco a los filtros de categoría.
                    guideFiltersFocused = true;
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down && guideNavigator.moveRow(-1)) guideSurface.refresh(System.currentTimeMillis());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down && guideNavigator.moveRow(1)) guideSurface.refresh(System.currentTimeMillis());
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && guideNavigator.moveTime(-1, guideRowProgrammes())) {
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && guideNavigator.moveTime(1, guideRowProgrammes())) {
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // OK corto (al soltar) abre el canal; OK mantenido programa o quita
                // el recordatorio del programa enfocado.
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        guideOkDown = true;
                        guideOkLongHandled = false;
                    } else if (guideOkDown && !guideOkLongHandled) {
                        guideOkLongHandled = true;
                        toggleGuideReminder();
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    boolean shortPress = guideOkDown && !guideOkLongHandled;
                    guideOkDown = false;
                    guideOkLongHandled = false;
                    if (shortPress) {
                        int row = guideChannelIndex(guideNavigator.getRow());
                        if (row < 0) return true;
                        closeGuide();
                        if (row != channelIndex) {
                            playChannel(row);
                        } else {
                            showOverlay(false);
                        }
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                return true;
            default:
                return false;
        }
    }

    /** Teclas con el foco en los filtros: ◀ ▶ cambian la categoría; ▼ u OK vuelven a los canales. */
    boolean handleGuideFilterKey(int keyCode, boolean down) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) changeGuideFilter(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) changeGuideFilter(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down) {
                    guideFiltersFocused = false;
                    guideOkDown = false;
                    guideOkLongHandled = false;
                    guideSurface.refresh(System.currentTimeMillis());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                return true;
            default:
                return false;
        }
    }

    /** Si falló el enlace directo Highfly del runner, el próximo intento usa el resolutor. */
    void markPublishedHighflyLinkFailed() {
        if (currentPlaybackSource != null
                && "highfly".equalsIgnoreCase(currentPlaybackSource.getResolverId())) {
            PublishedHighflyLinks.markFailed(currentPlaybackSource.getStableSourceId());
        }
    }

    void refreshReminderIds() {
        reminderIds.clear();
        for (ProgramReminder reminder : ReminderAlerts.list(this)) reminderIds.add(reminder.id());
    }

    /** Programa o quita el recordatorio del programa enfocado en la Guía. */
    void toggleGuideReminder() {
        Channel channel = guideChannel(guideNavigator.getRow());
        if (channel == null) return;
        EpgProgramme programme = guideNavigator.focusedProgramme(guideRowProgrammes());
        if (programme == null) {
            showNotice(NOTICE_WARN, R.string.notice_guide, getString(R.string.reminder_no_programme), false);
            return;
        }
        ProgramReminder reminder = new ProgramReminder(
                PlaybackPreferences.channelIdentity(channel),
                channel.getName(),
                programme.getTitle(),
                programme.getStartMillis(),
                programme.getStopMillis()
        );
        boolean alreadySet = reminderIds.contains(reminder.id());
        if (!alreadySet && programme.getStartMillis() <= System.currentTimeMillis()) {
            showNotice(NOTICE_WARN, R.string.notice_guide, getString(R.string.reminder_already_started), false);
            return;
        }
        boolean added;
        try {
            added = ReminderAlerts.toggle(this, reminder);
        } catch (RuntimeException error) {
            Log.w(PLAYBACK_HEALTH_TAG, "no se pudo programar el recordatorio", error);
            return;
        }
        refreshReminderIds();
        guideSurface.refresh(System.currentTimeMillis());
        if (classicUi) {
            Toast.makeText(this, added
                    ? getString(R.string.reminder_added, programme.getTitle(),
                            ReminderAlerts.when(programme.getStartMillis()))
                    : getString(R.string.reminder_removed), Toast.LENGTH_SHORT).show();
        } else {
            showNotice(NOTICE_REMINDER, R.string.notice_reminder, added
                    ? getString(R.string.notice_reminder_added, programme.getTitle(),
                            ReminderAlerts.when(programme.getStartMillis()))
                    : getString(R.string.notice_reminder_removed), false);
        }
    }

    void showProgrammeDetail() {
        if (channels.isEmpty() || channelIndex < 0 || channelIndex >= channels.size()) return;
        if (!classicUi) {
            showOsdDescription();
            return;
        }
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        // El detalle se apoya sobre el OSD: ambos quedan visibles y se cierran juntos.
        showOverlay(true);
        // El detalle toma el lugar del bloque del OSD; los datos técnicos siguen visibles.
        if (osdHero != null) osdHero.setVisibility(View.INVISIBLE);
        programmeDetailOverlay.setVisibility(View.VISIBLE);
        updateProgrammeDetail();
        mainHandler.postDelayed(hideProgrammeDetailWithOverlay, PROGRAMME_DETAIL_TIMEOUT_MS);
    }

    /** Moderno: OK abre la descripción en el mismo OSD; sin descripción no pasa nada. */
    void showOsdDescription() {
        if (osdDescription == null) return;
        EpgProgramme programme = epgData.findCurrent(
                channels.get(channelIndex).getTvgId(), System.currentTimeMillis());
        if (programme == null || AppStrings.isBlank(programme.getDescription())) return;
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        showOverlay(true);
        osdDescriptionOpen = true;
        updateProgrammeInfo();
        mainHandler.postDelayed(hideProgrammeDetailWithOverlay, PROGRAMME_DETAIL_TIMEOUT_MS);
    }
}

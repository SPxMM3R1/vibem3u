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
 * Capas sobre el video: carga, OSD, logo, avisos y pantalla de error.
 * Capa de MainActivity (separada en la 0.6.03; el código se movió tal cual).
 */
@UnstableApi
abstract class MainOverlays extends MainPlayback {
    void showPlaylistError(String detail) {
        loadFailed = true;
        if (!classicUi && playbackErrorOverlay != null) {
            clearResourceWarning();
            stopLoadingTextAnimation();
            if (loadingPanel != null) loadingPanel.setVisibility(View.GONE);
            showPlaylistErrorScreen(detail);
            return;
        }
        clearResourceWarning();
        stopLoadingTextAnimation();
        loadingPanel.setVisibility(View.VISIBLE);
        if (!classicUi && loadingProgress != null) loadingProgress.setVisibility(View.GONE);
        loadingText.animate().cancel();
        loadingText.setAlpha(1f);
        loadingText.setTranslationY(0f);
        String message = getString(R.string.playlist_error);
        if (detail != null && !AppStrings.isBlank(detail)) {
            message += " · " + SafePlaybackText.detail(detail.replace('\n', ' '));
        }
        loadingText.setText(message);
    }

    void clearResourceWarning() {
        mainHandler.removeCallbacks(hideResourceWarning);
        resourceWarningVisible = false;
        resourceWarningUntilElapsedRealtime = 0L;
    }

    void resetResourceWarningState() {
        clearResourceWarning();
        resourceWarningCooldownUntilElapsedRealtime = 0L;
        bufferWarningConditionActive = false;
        memoryWarningSamples = 0;
        memoryWarningCondition = PlaybackResourceWarningPolicy.Type.NONE;
    }

    boolean showResourceWarning(
            PlaybackResourceWarningPolicy.Type type,
            PlaybackBufferManager.Snapshot snapshot,
            int memoryPressureLevel
    ) {
        if (type == PlaybackResourceWarningPolicy.Type.NONE
                || playbackChannel == null
                || !playbackHasStarted
                || settingsOpen
                || loadFailed
                || !hasWindowFocus()
                || playbackAutoRecoveryInFlight
                || playbackRecoveryFailed) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < resourceWarningCooldownUntilElapsedRealtime) return false;

        int messageId;
        switch (type) {
            case BUFFER:
                messageId = R.string.resource_warning_buffer;
                break;
            case MEMORY_CRITICAL:
                messageId = R.string.resource_warning_memory_critical;
                break;
            case MEMORY:
                messageId = R.string.resource_warning_memory;
                break;
            default:
                return false;
        }

        clearResourceWarning();
        showLoadingState(getString(messageId));
        resourceWarningVisible = true;
        if (!classicUi && loadingKicker != null) loadingKicker.setText(R.string.loading_kicker_unstable);
        resourceWarningUntilElapsedRealtime = now + RESOURCE_WARNING_VISIBLE_MS;
        resourceWarningCooldownUntilElapsedRealtime =
                now + RESOURCE_WARNING_COOLDOWN_MS;
        mainHandler.postDelayed(hideResourceWarning, RESOURCE_WARNING_VISIBLE_MS);

        Log.w(
                RESOURCE_HEALTH_TAG,
                "warning type=" + type
                        + " bufferedMs=" + snapshot.bufferedDurationMs
                        + " heap=" + snapshot.heapPercent() + "%"
                        + " allocator=" + snapshot.allocatorPercent() + "%"
                        + " pressure=" + memoryPressureLevel
        );
        return true;
    }

    void updateResourceWarnings(int playbackState) {
        if (player == null || playbackChannel == null || settingsOpen || loadFailed
                || !playbackHasStarted || playbackResolutionTask != null
                || playbackAutoRecoveryInFlight || playbackRecoveryFailed) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        boolean userPaused = !player.getPlayWhenReady() && !player.isPlaying();
        if (userPaused) {
            bufferWarningConditionActive = false;
            memoryWarningSamples = 0;
            memoryWarningCondition = PlaybackResourceWarningPolicy.Type.NONE;
            return;
        }
        boolean loading = playbackState == Player.STATE_BUFFERING
                || playbackState == Player.STATE_IDLE
                || (playbackState == Player.STATE_READY && player.isLoading());
        PlaybackBufferManager.Snapshot snapshot = playbackBufferManager == null
                ? PlaybackBufferManager.Snapshot.EMPTY : playbackBufferManager.snapshot();

        boolean bufferWarning = PlaybackResourceWarningPolicy.isBufferWarning(
                playbackHasStarted,
                userPaused,
                loading,
                snapshot.bufferedDurationMs,
                playbackLoadingSinceElapsedRealtime,
                now
        );
        if (!bufferWarning) {
            bufferWarningConditionActive = false;
        } else if (!bufferWarningConditionActive
                && showResourceWarning(
                PlaybackResourceWarningPolicy.Type.BUFFER,
                snapshot,
                0
        )) {
            bufferWarningConditionActive = true;
        }

        PlaybackResourceWarningPolicy.Type memoryType =
                PlaybackResourceWarningPolicy.memoryType(
                        playbackHasStarted,
                        0,
                        snapshot.heapUsedBytes,
                        snapshot.heapMaxBytes,
                        snapshot.allocatedBytes,
                        snapshot.targetBytes
                );
        if (memoryType == PlaybackResourceWarningPolicy.Type.NONE) {
            memoryWarningSamples = 0;
            memoryWarningCondition = PlaybackResourceWarningPolicy.Type.NONE;
        } else {
            memoryWarningSamples = Math.min(2, memoryWarningSamples + 1);
        }
        if (memoryType != PlaybackResourceWarningPolicy.Type.NONE
                && memoryWarningSamples >= 2
                && (memoryWarningCondition == PlaybackResourceWarningPolicy.Type.NONE
                || (memoryType == PlaybackResourceWarningPolicy.Type.MEMORY_CRITICAL
                && memoryWarningCondition != PlaybackResourceWarningPolicy.Type.MEMORY_CRITICAL))) {
            if (showResourceWarning(memoryType, snapshot, 0)) {
                memoryWarningCondition = memoryType;
            }
        }
    }

    void handleMemoryPressure(int level) {
        if (playbackBufferManager != null) playbackBufferManager.onMemoryPressure(level);
        if (player == null || playbackChannel == null || !playbackHasStarted
                || settingsOpen || loadFailed || !hasWindowFocus()) return;
        if (!player.getPlayWhenReady() && !player.isPlaying()) return;

        PlaybackBufferManager.Snapshot snapshot = playbackBufferManager == null
                ? PlaybackBufferManager.Snapshot.EMPTY : playbackBufferManager.snapshot();
        PlaybackResourceWarningPolicy.Type type = PlaybackResourceWarningPolicy.memoryType(
                playbackHasStarted,
                level,
                snapshot.heapUsedBytes,
                snapshot.heapMaxBytes,
                snapshot.allocatedBytes,
                snapshot.targetBytes
        );
        if (type == PlaybackResourceWarningPolicy.Type.NONE) return;
        if (showResourceWarning(type, snapshot, level)) {
            memoryWarningSamples = 2;
            memoryWarningCondition = type;
        }
    }

    void showLoadingState(String message) {
        if (loadingPanel == null || isFinishing()) return;
        clearResourceWarning();
        loadingPanel.setVisibility(View.VISIBLE);
        // Pantalla negra (sin imagen todavía): todo lo que se ve encima, al 70 % (0.5.90).
        if (!classicUi) setBlackScreenDim(!hasRenderedVideoFrame());
        String safeMessage = SafePlaybackText.detail(message == null ? "" : message.trim());
        if (!classicUi) {
            showModernLoadingStep(stripTrailingEllipsis(safeMessage));
            return;
        }
        boolean animate = safeMessage.endsWith("…") || safeMessage.endsWith("...");
        String base = stripTrailingEllipsis(safeMessage);
        boolean sameAnimatedMessage = animate
                && loadingMessageAnimating
                && base.equals(loadingMessageBase);
        if (sameAnimatedMessage) {
            // Several resolver stages intentionally share one visible label
            // (for example, both catalogue requests). Keep the current dot
            // phase instead of restarting it for every progress callback.
            if (!loadingAnimationScheduled) {
                loadingAnimationScheduled = true;
                mainHandler.postDelayed(animateLoadingText, 420L);
            }
            return;
        }
        if (base.equals(loadingMessageBase) && animate == loadingMessageAnimating) return;

        mainHandler.removeCallbacks(animateLoadingText);
        loadingAnimationScheduled = false;
        loadingMessageBase = base;
        loadingMessageAnimating = animate;
        loadingDotCount = 0;
        if (animate) {
            renderAnimatedLoadingText();
            loadingAnimationScheduled = true;
            mainHandler.postDelayed(animateLoadingText, 420L);
        } else {
            loadingText.setText(safeMessage);
        }
    }

    void showResolutionProgress(ResolutionProgress progress) {
        if (progress == null || progress.getStage() == null) return;
        int current = progress.getCurrent();
        int total = progress.getTotal();
        String message;
        switch (progress.getStage()) {
            case SESSION:
                message = getString(R.string.loading_resolver_session);
                break;
            case CATALOG_REQUEST:
                message = getString(R.string.loading_resolver_catalog);
                break;
            case CATALOG_PAGE:
                message = getString(R.string.loading_resolver_catalog);
                break;
            case CATALOG_PARSED:
                message = getString(R.string.loading_resolver_catalog_parsed);
                break;
            case CATALOG_MATCHING:
                message = getString(R.string.loading_resolver_matching);
                break;
            case ALIAS_ATTEMPT:
                message = current > 0 && total > 1
                        ? getString(R.string.loading_resolver_alias, current, total)
                        : getString(R.string.loading_resolver_alias_unknown);
                break;
            case SOURCE_REQUEST:
                message = current > 0 && total > 1
                        ? getString(R.string.loading_resolver_source_request_count,
                        current,
                        total)
                        : getString(R.string.loading_resolver_source_request);
                break;
            case SOURCE_CANDIDATE:
                message = current > 0 && total > 1
                        ? getString(R.string.loading_resolver_candidate, current, total)
                        : getString(R.string.loading_resolver_candidate_unknown);
                break;
            case PAGE_REQUEST:
                message = getString(R.string.loading_resolver_page);
                break;
            case PAGE_PARSED:
                message = getString(R.string.loading_resolver_page_parsed);
                break;
            case TOKEN_REQUEST:
                message = getString(R.string.loading_resolver_token);
                break;
            case TOKEN_PARSED:
                message = getString(R.string.loading_resolver_token_parsed);
                break;
            case SOURCE_BUILDING:
                message = getString(R.string.loading_resolver_building);
                break;
            case HLS_PLAYLIST:
                message = getString(R.string.loading_hls_playlist);
                break;
            case HLS_VARIANT:
                message = getString(R.string.loading_hls_variant);
                break;
            case HLS_SEGMENT:
                message = getString(R.string.loading_validating_segment);
                break;
            case SOURCE_FOUND:
                message = getString(R.string.loading_resolver_source_ready);
                break;
            case CACHE_REUSED:
                message = getString(R.string.loading_resolver_cache_reused);
                break;
            default:
                return;
        }
        // Detailed endpoint information remains sanitized inside the progress
        // event for diagnostics, but the normal playback UI only shows the
        // short stage label. This keeps one stable, readable line and never
        // exposes a complete playback URL while a source is loading.
        showLoadingState(message);
    }

    void stopLoadingTextAnimation() {
        mainHandler.removeCallbacks(animateLoadingText);
        loadingAnimationScheduled = false;
        loadingMessageAnimating = false;
        loadingMessageBase = "";
        loadingDotCount = 0;
    }

    /**
     * Keep the three-dot slot in the text at all times. Only the dots change
     * visibility, so the fixed stage label does not move as the animation
     * advances while the TextView remains centered.
     */
    void renderAnimatedLoadingText() {
        if (loadingText == null) return;
        String dotSlot = "...";
        SpannableString rendered = new SpannableString(loadingMessageBase + dotSlot);
        int visibleDots = Math.min(Math.max(loadingDotCount, 0), dotSlot.length());
        if (visibleDots < dotSlot.length()) {
            rendered.setSpan(
                    new ForegroundColorSpan(Color.TRANSPARENT),
                    loadingMessageBase.length() + visibleDots,
                    loadingMessageBase.length() + dotSlot.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        loadingText.setText(rendered);
    }

    static String stripTrailingEllipsis(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("…")) result = result.substring(0, result.length() - 1);
        while (result.endsWith("...")) result = result.substring(0, result.length() - 3);
        return result.trim();
    }

    void hideLoadingState() {
        if (loadingKicker != null) loadingKicker.setVisibility(View.GONE);
        clearResourceWarning();
        stopLoadingTextAnimation();
        if (loadingPanel != null) loadingPanel.setVisibility(View.GONE);
        setBlackScreenDim(false);
    }

    /** Brillo del OSD y la carga sobre la pantalla negra (aprobado 2026-10-11): 70 %. */
    static final float BLACK_SCREEN_ALPHA = 0.7f;
    boolean blackScreenDimmed;

    void setBlackScreenDim(boolean dim) {
        if (dim == blackScreenDimmed) return;
        blackScreenDimmed = dim;
        float alpha = dim ? BLACK_SCREEN_ALPHA : 1f;
        for (View view : new View[]{channelOverlay, loadingPanel}) {
            if (view == null) continue;
            view.animate().cancel();
            // Con la Guía moderna abierta la carga no se ve (vuelve al cerrarla).
            float target = view == loadingPanel && guideCoversPlayback() ? 0f : alpha;
            if (dim) view.setAlpha(target);
            else view.animate().alpha(target).setDuration(250L).start();
        }
    }

    /** Guía moderna abierta: ni la carga ni el OSD se traslucen debajo (0.6.00). */
    boolean guideCoversPlayback() {
        // Opciones también (0.6.02): debajo del menú solo se ve el video.
        return !classicUi && (isGuideVisible() || settingsOpen);
    }

    /**
     * Moderno: la onda anima la espera, así que el paso va sin puntos y entra con el
     * movimiento enfatizado de Material (sube y aparece). Mientras carga, el OSD se atenúa.
     */
    void showModernLoadingStep(String step) {
        if (loadingProgress != null) loadingProgress.setVisibility(View.VISIBLE);
        if (loadingKicker != null) {
            loadingKicker.setText(resourceWarningVisible
                    ? R.string.loading_kicker_unstable : R.string.loading_kicker);
            loadingKicker.setVisibility(View.VISIBLE);
        }
        if (step.equals(loadingMessageBase)) return;
        loadingMessageBase = step;
        loadingText.animate().cancel();
        loadingText.setText(step);
        loadingText.setAlpha(0f);
        loadingText.setTranslationY(dpToPx(5));
        loadingText.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400L)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
    }


    void hidePlaylistLoadingIfPlaybackPending() {
        if (playbackChannel != null) {
            boolean playbackPending = playbackResolutionTask != null
                    || player == null
                    || player.getPlaybackState() != Player.STATE_READY;
            if (playbackPending) return;
        }
        hideLoadingState();
    }
    /** Número del canal en el OSD; el moderno ya no muestra la categoría. El logo reemplaza al nombre. */
    void bindOsdChannel(int displayNumber, Channel channel) {
        channelNumber.setText(String.format(Locale.ROOT, "%03d", displayNumber));
        channelName.setText(channel.getName());
        if (osdEyebrow != null) {
            // Línea «Flotante minimalista»: el punto verde marca lo que se está viendo.
            String eyebrow = String.format(Locale.ROOT, "%03d", displayNumber) + " · " + channel.getName();
            if (!AppStrings.isBlank(channel.getGroup())) eyebrow += " · " + channel.getGroup().trim();
            android.text.SpannableString text = new android.text.SpannableString("●  " + eyebrow);
            text.setSpan(new android.text.style.ForegroundColorSpan(0xFF5BE9A0), 0, 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            osdEyebrow.setText(text);
        }
    }

    /** Etiqueta en mayúsculas espaciadas dentro de un texto mixto (no hay span de interletrado). */
    static String spacedCaps(String value) {
        String upper = value.toUpperCase(Locale.forLanguageTag("es-CL"));
        StringBuilder out = new StringBuilder(upper.length() * 2);
        for (int index = 0; index < upper.length(); index++) {
            if (index > 0) out.append('\u200A');
            out.append(upper.charAt(index));
        }
        return out.toString();
    }

    void updateProgrammeInfo() {
        if (channels.isEmpty() || channelIndex < 0 || channelIndex >= channels.size()) return;
        Channel channel = channels.get(channelIndex);
        long now = System.currentTimeMillis();
        EpgProgramme programme = epgData.findCurrent(channel.getTvgId(), now);

        if (programme == null) {
            contentTitle.setText(AppStrings.isBlank(channel.getGroup())
                    ? getString(R.string.live_content)
                    : channel.getGroup());
            programmeTime.setVisibility(View.GONE);
            if (osdDescription != null) osdDescription.setVisibility(View.GONE);
            if (osdNext != null) osdNext.setVisibility(View.GONE);
            liveProgress.setIndeterminate(true);
            updateProgrammeDetail();
            return;
        }

        contentTitle.setText(programme.getTitle());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String timeRange = timeFormat.format(new Date(programme.getStartMillis()))
                + (classicUi ? " — " : " – ")
                + timeFormat.format(new Date(programme.getStopMillis()));
        programmeTime.setText(classicUi ? timeRange : timeRange + "  ·  " + getString(
                R.string.guide_remaining, EpgGuideView.formatDuration(programme.getStopMillis() - now)));
        programmeTime.setVisibility(View.VISIBLE);

        String description = programme.getDescription();
        if (osdDescription != null) {
            // Moderno (0.5.60): la descripción solo va en el detalle, que se abre con OK.
            osdDescription.setText(description);
            boolean blank = AppStrings.isBlank(description);
            if (blank) osdDescriptionOpen = false;
            osdDescription.setVisibility(!blank && (classicUi || osdDescriptionOpen)
                    ? View.VISIBLE : View.GONE);
        }

        long duration = programme.getStopMillis() - programme.getStartMillis();
        int progress = duration <= 0 ? 0 : (int) Math.max(0, Math.min(1000,
                ((now - programme.getStartMillis()) * 1000L) / duration));
        liveProgress.setIndeterminate(false);
        liveProgress.setMax(1000);
        liveProgress.setProgress(progress);

        if (osdNext != null) {
            if (osdDescriptionOpen) osdNext.setVisibility(View.GONE);
            else bindOsdNext(channel, programme, timeFormat);
        }
        updateProgrammeDetail();
    }

    /** «Después · 22:15 Programa», con la campana cyan si tiene recordatorio. */
    void bindOsdNext(Channel channel, EpgProgramme current, SimpleDateFormat timeFormat) {
        EpgProgramme next = null;
        for (EpgProgramme candidate : epgData.findUpcoming(channel.getTvgId(),
                current.getStopMillis(), 2)) {
            if (candidate.getStartMillis() >= current.getStopMillis()) {
                next = candidate;
                break;
            }
        }
        if (next == null) {
            osdNext.setVisibility(View.GONE);
            return;
        }
        String prefix = getString(R.string.guide_next,
                timeFormat.format(new Date(next.getStartMillis())));
        // Moderno: «DESPUÉS · 03:30» como etiqueta (mayúsculas espaciadas, tenue y más chica).
        prefix = (classicUi ? prefix : spacedCaps(prefix)) + "   ";
        android.text.SpannableString text = new android.text.SpannableString(prefix + next.getTitle());
        text.setSpan(new android.text.style.ForegroundColorSpan(classicUi ? getColor(R.color.white) : 0xD9FFFFFF),
                prefix.length(), text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!classicUi) {
            text.setSpan(new android.text.style.RelativeSizeSpan(0.82f), 0, prefix.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.ForegroundColorSpan(0x73FFFFFF), 0, prefix.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        osdNext.setText(text);
        refreshReminderIds();
        boolean reminded = reminderIds.contains(
                PlaybackPreferences.channelIdentity(channel) + "@" + next.getStartMillis());
        android.graphics.drawable.Drawable bell = null;
        if (reminded) {
            bell = getDrawable(R.drawable.ic_reminder_bell);
            if (bell != null) bell.setBounds(0, 0, dpToPx(10), dpToPx(10));
        }
        osdNext.setCompoundDrawablesRelative(bell, null, null, null);
        osdNext.setVisibility(View.VISIBLE);
    }

    void updateProgrammeDetail() {
        if (programmeDetailOverlay == null
                || programmeDetailOverlay.getVisibility() != View.VISIBLE
                || channels.isEmpty()
                || channelIndex < 0
                || channelIndex >= channels.size()) return;

        Channel channel = channels.get(channelIndex);
        long now = System.currentTimeMillis();
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        List<EpgProgramme> upcoming = epgData.findUpcoming(channel.getTvgId(), now, 3);
        EpgProgramme first = upcoming.isEmpty() ? null : upcoming.get(0);
        EpgProgramme current = first != null && first.getStartMillis() <= now ? first : null;

        if (first == null) {
            detailLive.setVisibility(classicUi ? View.VISIBLE : View.GONE); // Moderno: sin «EN VIVO».
            detailLabel.setText(AppStrings.isBlank(channel.getGroup())
                    ? getString(R.string.live_content) : channel.getGroup());
            detailTitle.setText(R.string.epg_no_information);
            detailMeta.setText(channel.getName());
            detailProgress.setVisibility(View.GONE);
            detailDescription.setVisibility(View.GONE);
            detailSide.setVisibility(View.GONE);
            return;
        }

        detailLive.setVisibility(classicUi && current != null ? View.VISIBLE : View.GONE);
        detailLabel.setText(current != null
                ? getString(R.string.light_epg_now)
                : getString(R.string.light_epg_next_at,
                        timeFormat.format(new Date(first.getStartMillis()))));
        detailTitle.setText(first.getTitle());
        String range = formatProgrammeRange(first, timeFormat);
        String duration = formatEpgDuration(first.getStopMillis() - first.getStartMillis());
        detailMeta.setText(current != null
                ? getString(R.string.detail_meta_live, range, duration,
                        formatEpgDuration(current.getStopMillis() - now))
                : getString(R.string.detail_meta, range, duration));
        if (current != null) {
            long total = current.getStopMillis() - current.getStartMillis();
            int progress = total <= 0 ? 0 : (int) Math.max(0, Math.min(1000,
                    ((now - current.getStartMillis()) * 1000L) / total));
            detailProgress.setMax(1000);
            detailProgress.setProgress(progress);
            detailProgress.setVisibility(View.VISIBLE);
        } else {
            detailProgress.setVisibility(View.GONE);
        }
        String description = first.getDescription();
        detailDescription.setText(description);
        detailDescription.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);

        EpgProgramme next = upcoming.size() > 1 ? upcoming.get(1) : null;
        EpgProgramme after = upcoming.size() > 2 ? upcoming.get(2) : null;
        // Moderno (0.5.60): el detalle no muestra los próximos programas.
        detailSide.setVisibility(!classicUi || next == null ? View.GONE : View.VISIBLE);
        if (next != null) {
            detailNextLabel.setText(getString(R.string.light_epg_next_at,
                    timeFormat.format(new Date(next.getStartMillis()))));
            detailNextTitle.setText(next.getTitle());
        }
        detailAfterLabel.setVisibility(after == null ? View.GONE : View.VISIBLE);
        detailAfterTitle.setVisibility(after == null ? View.GONE : View.VISIBLE);
        if (after != null) {
            detailAfterLabel.setText(getString(R.string.light_epg_after_at,
                    timeFormat.format(new Date(after.getStartMillis()))));
            detailAfterTitle.setText(after.getTitle());
        }
    }

    String formatEpgDuration(long millis) {
        long totalMinutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        int hours = (int) (totalMinutes / 60L);
        int minutes = (int) (totalMinutes % 60L);
        if (hours == 0) return getString(R.string.light_epg_minutes, minutes);
        if (minutes == 0) return getString(R.string.light_epg_hours, hours);
        return getString(R.string.light_epg_hours_minutes, hours, minutes);
    }

    static String formatProgrammeRange(
            EpgProgramme programme,
            SimpleDateFormat timeFormat
    ) {
        return timeFormat.format(new Date(programme.getStartMillis()))
                + " — "
                + timeFormat.format(new Date(programme.getStopMillis()));
    }

    void loadChannelLogo(Channel channel, boolean revalidate) {
        URI logoUri = channel.getLogoUri();
        // Sin logo: moderno muestra el nombre; clásico, las iniciales en el recuadro.
        String fallback = classicUi ? initials(channel.getName()) : channel.getName();
        String expectedIdentity = PlaybackPreferences.channelIdentity(channel);
        long requestGeneration = ++logoRequestGeneration;
        boolean hasLogo = logoUri != null && ("http".equalsIgnoreCase(logoUri.getScheme())
                || "https".equalsIgnoreCase(logoUri.getScheme()));
        channelLogoFallback.setText(fallback);
        if (!expectedIdentity.equals(displayedLogoIdentity)) {
            finishLogoSizeAdjust(false);
            displayedLogoBitmap = null;
            displayedLogoKey = null;
            channelLogo.setImageDrawable(null);
            displayedLogoIdentity = "";
            if (hasLogo) {
                // Con logo, el recuadro queda vacío hasta que llega (caché: unos cuadros).
                // Mostrar el nombre mientras tanto lo hacía parpadear al cambiar de canal.
                channelLogo.setVisibility(View.INVISIBLE);
                channelLogoFallback.setVisibility(View.GONE);
            } else {
                channelLogo.setVisibility(View.GONE);
                channelLogoFallback.setVisibility(View.VISIBLE);
            }
        }
        if (!hasLogo) return;

        int expectedIndex = channelIndex;
        // Se carga con margen de sobra: el tamaño final lo fija el tamaño óptico.
        int targetWidthPx = classicUi ? dpToPx(78) : dpToPx(LogoFit.OSD_LOGO_MAX_WIDTH_DP * 1.5f);
        int targetHeightPx = classicUi ? dpToPx(54) : dpToPx(LogoFit.OSD_LOGO_MAX_HEIGHT_DP * 2f);
        boolean shouldRevalidate = revalidate
                || logoRevalidatedThisSession.add(logoUri.toString());
        Future<?> previousTask = logoRequestTask;
        if (previousTask != null) previousTask.cancel(true);
        logoRequestTask = logoCacheExecutor.submit(() -> {
            android.graphics.Bitmap cached = channelLogoCache.loadCached(
                    logoUri,
                    targetWidthPx,
                    targetHeightPx
            );
            if (cached != null) {
                android.graphics.Bitmap trimmedCached = classicUi ? cached : LogoFit.trim(cached, logoUri);
                mainHandler.post(() -> showChannelLogo(
                        trimmedCached,
                        expectedIndex,
                        expectedIdentity,
                        requestGeneration
                ));
            }

            if (cached != null && !shouldRevalidate) return;
            try {
                ChannelLogoCache.RefreshResult refreshed = channelLogoCache.refreshIfChanged(
                        logoUri,
                        targetWidthPx,
                        targetHeightPx
                );
                if (cached != null && !refreshed.isChanged()) return;
                android.graphics.Bitmap trimmedRefreshed = classicUi
                        ? refreshed.getBitmap() : LogoFit.trim(refreshed.getBitmap(), logoUri);
                mainHandler.post(() -> showChannelLogo(
                        trimmedRefreshed,
                        expectedIndex,
                        expectedIdentity,
                        requestGeneration
                ));
            } catch (Exception ignored) {
                // El logo en caché ya mostrado permanece si falla la actualización. Sin caché
                // y sin red, el nombre ocupa el lugar del logo.
                if (cached == null) {
                    mainHandler.post(() -> showChannelLogoFallback(
                            expectedIndex, expectedIdentity, requestGeneration));
                }
            }
        });
    }

    void showChannelLogoFallback(int expectedIndex, String expectedIdentity,
            long requestGeneration) {
        if (requestGeneration != logoRequestGeneration
                || !isCurrentLogo(expectedIndex, expectedIdentity)
                || isFinishing()
                || expectedIdentity.equals(displayedLogoIdentity)) return;
        channelLogo.setVisibility(View.GONE);
        channelLogoFallback.setVisibility(View.VISIBLE);
    }

    void showChannelLogo(
            android.graphics.Bitmap bitmap,
            int expectedIndex,
            String expectedIdentity,
            long requestGeneration
    ) {
        if (requestGeneration != logoRequestGeneration
                || !isCurrentLogo(expectedIndex, expectedIdentity)
                || isFinishing()) return;
        if (classicUi) {
            // Clásico: logo contenido en su recuadro fijo de 78×54 dp.
            channelLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            channelLogo.setImageBitmap(bitmap);
            channelLogo.setVisibility(View.VISIBLE);
            channelLogoFallback.setVisibility(View.GONE);
            displayedLogoIdentity = expectedIdentity;
            return;
        }
        sizeModernLogo(bitmap);
        channelLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        channelLogo.setImageBitmap(bitmap);
        channelLogo.setVisibility(View.VISIBLE);
        channelLogoFallback.setVisibility(View.GONE);
        displayedLogoIdentity = expectedIdentity;
        displayedLogoBitmap = bitmap;
        Channel channel = expectedIndex >= 0 && expectedIndex < channels.size()
                ? channels.get(expectedIndex) : null;
        displayedLogoKey = channel == null ? null : LogoFit.logoKey(channel.getLogoUri());
    }

    /**
     * Tamaño óptico: la misma superficie visual para todos los logos (los UHD, igual de altos
     * que su versión normal), con la corrección por tinta y el ajuste a mano de ese logo.
     */
    void sizeModernLogo(android.graphics.Bitmap bitmap) {
        float[] size = LogoFit.opticalSize(bitmap,
                dpToPx(LogoFit.OSD_LOGO_AREA_WIDTH_DP) * (float) dpToPx(LogoFit.OSD_LOGO_AREA_HEIGHT_DP),
                dpToPx(LogoFit.OSD_LOGO_MAX_WIDTH_DP), dpToPx(LogoFit.OSD_LOGO_MAX_HEIGHT_DP));
        ViewGroup.LayoutParams logoParams = channelLogo.getLayoutParams();
        logoParams.width = Math.max(1, Math.round(size[0]));
        logoParams.height = Math.max(1, Math.round(size[1]));
        channelLogo.setLayoutParams(logoParams);
    }

    // ---------------------------------------------------------------------------------
    // Tamaño del logo en vivo (0.5.60): Opciones › En reproducción › Tamaño del logo
    // ---------------------------------------------------------------------------------

    static final float LOGO_SIZE_STEP = 0.05f;

    void startLogoSizeAdjust() {
        if (classicUi || logoSizeAdjust == null || channelLogoFrame == null
                || displayedLogoBitmap == null || displayedLogoKey == null || logoSizeAdjusting) return;
        logoSizeAdjusting = true;
        logoSizeKey = displayedLogoKey;
        logoSizeOriginal = LogoFit.userScale(logoSizeKey);
        logoSizeScale = logoSizeOriginal;
        showOverlay(true);
        int padding = dpToPx(8);
        channelLogoFrame.setBackgroundResource(R.drawable.logo_size_frame);
        channelLogoFrame.setPadding(padding, padding, padding, padding);
        logoSizeAdjust.setVisibility(View.VISIBLE);
        applyLogoSize();
    }

    void applyLogoSize() {
        LogoFit.setUserScale(logoSizeKey, logoSizeScale);
        if (displayedLogoBitmap != null) sizeModernLogo(displayedLogoBitmap);
        String value = getString(R.string.logo_size_value, Math.round(logoSizeScale * 100f));
        android.text.SpannableString styled = new android.text.SpannableString(value);
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '◀' || c == '▶') {
                styled.setSpan(new android.text.style.RelativeSizeSpan(0.5f), index, index + 1, 0);
                styled.setSpan(new android.text.style.ForegroundColorSpan(0x8CFFFFFF), index, index + 1, 0);
            }
        }
        logoSizeValue.setText(styled);
        positionLogoSizeAdjust();
    }

    /** El control va a la derecha del logo, centrado en su alto. */
    void positionLogoSizeAdjust() {
        if (logoSizeAdjust == null || channelLogoFrame == null
                || !(logoSizeAdjust.getParent() instanceof View)) return;
        int[] frame = new int[2];
        int[] parent = new int[2];
        channelLogoFrame.getLocationInWindow(frame);
        ((View) logoSizeAdjust.getParent()).getLocationInWindow(parent);
        float x = frame[0] - parent[0] + channelLogoFrame.getWidth() + dpToPx(20);
        float y = frame[1] - parent[1]
                + (channelLogoFrame.getHeight() - logoSizeAdjust.getHeight()) / 2f;
        if (logoSizeAdjust.getX() != x) logoSizeAdjust.setX(x);
        if (logoSizeAdjust.getY() != y) logoSizeAdjust.setY(y);
    }

    /** ◀ ▶ cambian de 5 en 5 %, ▼ vuelve al automático, OK guarda. Otra tecla cancela. */
    boolean handleLogoSizeKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean ours = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (!ours) {
            // Atrás, cambio de canal u otra tecla: se deshace el ajuste y la tecla sigue su curso.
            // Si era Atrás, el callback de atrás (al soltar) no debe abrir «¿Salir de la app?»
            // (0.5.99): se marca como consumida; otra tecla no pasa por ese callback.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                finishLogoSizeAdjust(false);
                markBackConsumedByPanel();
            }
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                stepLogoSize(-LOGO_SIZE_STEP);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                stepLogoSize(LOGO_SIZE_STEP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                logoSizeScale = 1f;
                applyLogoSize();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) finishLogoSizeAdjust(true);
                return true;
            default:
                return true;
        }
    }

    void stepLogoSize(float delta) {
        float next = Math.round((logoSizeScale + delta) * 20f) / 20f;
        logoSizeScale = Math.max(LogoFit.USER_SCALE_MIN, Math.min(LogoFit.USER_SCALE_MAX, next));
        applyLogoSize();
    }

    void finishLogoSizeAdjust(boolean save) {
        if (!logoSizeAdjusting) return;
        logoSizeAdjusting = false;
        if (save) LogoScales.save(this, logoSizeKey, logoSizeScale);
        else LogoFit.setUserScale(logoSizeKey, logoSizeOriginal);
        logoSizeAdjust.setVisibility(View.GONE);
        channelLogoFrame.setBackground(null);
        channelLogoFrame.setPadding(0, 0, 0, 0);
        if (displayedLogoBitmap != null) sizeModernLogo(displayedLogoBitmap);
        if (guideView != null) guideView.invalidate();
        mainHandler.removeCallbacks(hideOverlay);
        mainHandler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS);
    }

    boolean isCurrentLogo(int expectedIndex, String expectedIdentity) {
        if (expectedIndex != channelIndex || expectedIndex < 0 || expectedIndex >= channels.size()) {
            return false;
        }
        return expectedIdentity.equals(
                PlaybackPreferences.channelIdentity(channels.get(expectedIndex))
        );
    }

    int dpToPx(int dp) {
        return Math.max(1, Math.round(dp * getResources().getDisplayMetrics().density));
    }

    int dpToPx(float dp) {
        return Math.max(1, Math.round(dp * getResources().getDisplayMetrics().density));
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    void setStatus(String text, int colorResource) {
        streamStatus.setText(text);
        statusDot.setTextColor(getColor(colorResource));
    }

    void showOverlay(boolean keepVisible) {
        // Moderno (0.5.95): con el selector abierto, el OSD del canal no compite con él; con la
        // Guía abierta tampoco (0.6.00: los pasos de carga lo volvían a mostrar debajo).
        if (!classicUi && (isSourceSelectorVisible() || isGuideVisible() || settingsOpen)) return;
        hideProgrammeDetail.run();
        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        channelOverlay.setVisibility(View.VISIBLE);
        // Moderno: la hora y la fecha van dentro del OSD; el reloj aparte es del clásico.
        clock.setVisibility(classicUi ? View.VISIBLE : View.GONE);
        updateDiagnosticsVisibility();
        maybeActivatePlaybackDiagnostics();
        updateDiagnostics();
        mainHandler.removeCallbacks(hideOverlay);
        if (!keepVisible) {
            mainHandler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS);
        }
    }

    void showOverlayForChannelStart() {
        overlayAwaitingPlayback = true;
        showOverlay(true);
    }

    boolean isProgrammeDetailVisible() {
        return osdDescriptionOpen || (programmeDetailOverlay != null
                && programmeDetailOverlay.getVisibility() == View.VISIBLE);
    }
    /** Aviso breve; el clásico sigue usando el Toast de Android. */
    void showNotice(int kind, int kickerId, String text, boolean longDuration) {
        if (classicUi || noticeOverlay == null || noticeText == null) {
            Toast.makeText(this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        String kicker = getString(kickerId);
        android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
        line.append(spacedCaps(kicker));
        line.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 0, line.length(), 0);
        line.setSpan(new android.text.style.ForegroundColorSpan(0x8CFFFFFF), 0, line.length(), 0);
        line.append("    ").append(text);
        noticeText.setText(line);
        android.graphics.drawable.GradientDrawable mark = new android.graphics.drawable.GradientDrawable();
        mark.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        int size = dpToPx(kind == NOTICE_REMINDER ? 6 : 5);
        mark.setSize(size, size);
        if (kind == NOTICE_REMINDER) {
            mark.setStroke(Math.max(1, dpToPx(1)), 0xFF00B8D9);
        } else {
            mark.setColor(kind == NOTICE_WARN ? 0xFFFFC266 : kind == NOTICE_PROGRESS ? 0xFF00B8D9 : 0xFF5BE9A0);
        }
        mark.setBounds(0, 0, size, size);
        noticeText.setCompoundDrawablesRelative(mark, null, null, null);
        mainHandler.removeCallbacks(hideNotice);
        // En la Guía el aviso sube al espacio libre entre el detalle y la grilla; abajo tapaba
        // la fila enfocada.
        View root = findViewById(R.id.root);
        noticeOverlay.setTranslationY(isGuideVisible() && root != null
                ? -(root.getHeight() - dpToPx(272)) : 0f);
        noticeOverlay.animate().cancel();
        noticeOverlay.setVisibility(View.VISIBLE);
        noticeOverlay.animate().alpha(1f).setDuration(180L).start();
        mainHandler.postDelayed(hideNotice, longDuration ? 4_000L : 2_600L);
    }

    boolean isPlaybackErrorVisible() {
        return playbackErrorOverlay != null && playbackErrorOverlay.getVisibility() == View.VISIBLE;
    }

    void hidePlaybackErrorScreen() {
        playbackErrorDeferred = false;
        if (playbackErrorOverlay == null || playbackErrorOverlay.getVisibility() != View.VISIBLE) return;
        playbackErrorOverlay.setVisibility(View.GONE);
    }

    /** Canal que no abrió o se cortó: logo, «● SIN SEÑAL · 001 · TVN», causa y acciones. */
    void showPlaybackErrorScreen() {
        Channel channel = channels.isEmpty() || channelIndex < 0 || channelIndex >= channels.size()
                ? null : channels.get(channelIndex);
        playbackErrorIsPlaylist = false;
        mainHandler.removeCallbacks(hideOverlay);
        hideOverlay.run();
        android.graphics.drawable.Drawable logo = channelLogo == null ? null : channelLogo.getDrawable();
        playbackErrorLogo.setImageDrawable(logo);
        playbackErrorLogo.setVisibility(logo == null ? View.GONE : View.VISIBLE);
        String where = "";
        if (channel != null) {
            where = String.format(Locale.ROOT, "%03d",
                    publishedPlaybackCatalog.numberFor(channel, channelIndex + 1)) + " · " + channel.getName();
        }
        playbackErrorEyebrow.setText(warningEyebrow(getString(R.string.playback_error_no_signal), where));
        List<String> tried = channel == null ? Collections.emptyList() : triedSources(channel);
        if (tried.size() > 1) {
            playbackErrorTitle.setText(R.string.playback_error_title_all);
            playbackErrorMessage.setText(R.string.playback_error_all_message);
            android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
            for (String label : tried) {
                if (line.length() > 0) line.append("      ");
                line.append(label).append("  ");
                int dot = line.length();
                line.append("●");
                line.setSpan(new android.text.style.ForegroundColorSpan(0xD9FF9A9A), dot, dot + 1, 0);
                line.setSpan(new android.text.style.RelativeSizeSpan(0.6f), dot, dot + 1, 0);
            }
            playbackErrorTried.setText(line);
            playbackErrorTried.setVisibility(View.VISIBLE);
        } else {
            playbackErrorTitle.setText(playbackHasStarted
                    ? R.string.playback_error_title_dropped : R.string.playback_error_title_open);
            playbackErrorMessage.setText(getString(playbackErrorCause(lastPlaybackDiagnosticCode))
                    + " " + getString(R.string.playback_error_suffix));
            playbackErrorTried.setVisibility(View.GONE);
        }
        String code = PlaybackDiagnosticCode.display(lastPlaybackDiagnosticCode);
        playbackErrorCode.setText(code.isEmpty() ? "" : getString(R.string.playback_error_code, code));
        playbackErrorSecondary.setText(R.string.playback_error_other_source);
        playbackErrorHint.setVisibility(View.VISIBLE);
        presentPlaybackErrorScreen();
    }

    void showPlaylistErrorScreen(String detail) {
        playbackErrorIsPlaylist = true;
        mainHandler.removeCallbacks(hideOverlay);
        hideOverlay.run();
        playbackErrorLogo.setVisibility(View.GONE);
        playbackErrorEyebrow.setText(warningEyebrow(getString(R.string.playback_error_no_connection),
                getString(R.string.playlist_error_kicker)));
        playbackErrorTitle.setText(R.string.playlist_error_title);
        playbackErrorMessage.setText(R.string.playlist_error_message);
        playbackErrorTried.setVisibility(View.GONE);
        playbackErrorCode.setText(detail == null || AppStrings.isBlank(detail) ? ""
                : SafePlaybackText.detail(detail.replace('\n', ' ')));
        playbackErrorSecondary.setText(R.string.playback_error_settings);
        playbackErrorHint.setVisibility(View.GONE);
        presentPlaybackErrorScreen();
    }

    void presentPlaybackErrorScreen() {
        if (isGuideVisible() || (settingsOpen && !classicUi)) {
            playbackErrorDeferred = true;
            return;
        }
        playbackErrorDeferred = false;
        Date now = new Date();
        playbackErrorClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        playbackErrorDate.setText(new SimpleDateFormat("EEEE d 'de' MMMM",
                Locale.forLanguageTag("es-CL")).format(now));
        playbackErrorOverlay.setVisibility(View.VISIBLE);
        playbackErrorRetry.requestFocus();
    }

    /** «● SIN SEÑAL · 001 · TVN»: punto y estado en ámbar (aviso, DISENO.md). */
    CharSequence warningEyebrow(String state, String where) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder("●  ");
        text.append(state);
        text.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFC266), 0, text.length(), 0);
        if (!AppStrings.isBlank(where)) text.append(" · ").append(where);
        return text;
    }

    /**
     * Fuentes que la recuperación automática ya probó: con «Cambio automático» y respaldos,
     * al llegar aquí pasó por todas. Sin respaldos (o con el cambio apagado), solo una.
     */
    List<String> triedSources(Channel channel) {
        List<String> labels = new ArrayList<>();
        if (playbackPreferences == null || !playbackPreferences.isAutoBackupEnabled()) return labels;
        int backups = TvVooBackup.directBackupsOf(channel).size();
        if (backups == 0 && !TvVooBackup.hasTvVoo(channel)) return labels;
        labels.add(getString(R.string.source_selector_direct_label));
        if (TvVooBackup.hasTvVoo(channel)) {
            labels.add("TvVoo");
        } else if (backups == 1) {
            labels.add(getString(R.string.source_selector_backup_label));
        } else {
            for (int index = 0; index < backups; index++) {
                labels.add(getString(R.string.source_selector_backup_numbered_label, index + 1));
            }
        }
        return labels;
    }

    /** Frase según la familia del código (PPSSCCC, ver PlaybackDiagnosticCode). */
    static int playbackErrorCause(long code) {
        if (code <= 0) return R.string.playback_error_generic;
        if (code >= 1_000_000_000L) return R.string.playback_error_format;
        int subsystem = (int) (code / 100_000L);
        int cause = (int) (code % 1_000L);
        switch (subsystem) {
            case 30:
                if (cause == 401 || cause == 403) return R.string.playback_error_rejected;
                if (cause == 404 || cause == 410) return R.string.playback_error_not_found;
                if (cause >= 500 && cause <= 599) return R.string.playback_error_server;
                return R.string.playback_error_network;
            case 20:
                return R.string.playback_error_resolver;
            case 40:
                return R.string.playback_error_format;
            case 50:
                return R.string.playback_error_dropped;
            case 60:
                return R.string.playback_error_memory;
            default:
                return R.string.playback_error_generic;
        }
    }

    /** ◀ ▶ entre acciones, OK actúa; ▲ ▼ y el resto siguen su curso (cambiar de canal…). */
    boolean handlePlaybackErrorKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) playbackErrorRetry.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) playbackErrorSecondary.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && event.getRepeatCount() == 0) {
                    boolean secondary = playbackErrorSecondary.isFocused();
                    hidePlaybackErrorScreen();
                    if (playbackErrorIsPlaylist) {
                        if (secondary) openSettings();
                        else refreshPlaylists(getPlaylistSources());
                    } else if (secondary) {
                        openPlaybackSourceSelector();
                    } else {
                        startPlaybackFromInput();
                    }
                }
                return true;
            default:
                if (isChannelNavigationKey(keyCode) && down) hidePlaybackErrorScreen();
                return false;
        }
    }
}

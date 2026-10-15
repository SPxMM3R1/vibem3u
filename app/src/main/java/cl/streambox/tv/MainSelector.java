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
 * Selector «Fuentes y calidades» y acciones del canal.
 * Capa de MainActivity (separada en la 0.6.03; el código se movió tal cual).
 */
@UnstableApi
abstract class MainSelector extends MainGuide {
    boolean isSourceSelectorVisible() {
        return sourceSelectorOverlay != null
                && sourceSelectorOverlay.getVisibility() == View.VISIBLE;
    }

    /** Opens the shared source/quality chooser without disturbing the current player. */
    void openPlaybackSourceSelector() {
        if (exiting || resourcesReleased || settingsOpen || player == null
                || !hasWindowFocus() || playbackChannel == null
                || playbackResolutionTask != null
                || streamResolverRegistry == null) return;
        Channel versionsChannel = TvVooBackup.hasTvVoo(playbackChannel)
                ? TvVooBackup.resolutionChannel(playbackChannel) : playbackChannel;
        if (versionsChannel == null) versionsChannel = playbackChannel;
        StreamResolver resolver = streamResolverRegistry.find(versionsChannel);
        if (isSourceSelectorVisible() || sourceCandidateTask != null) return;

        mainHandler.removeCallbacks(hideProgrammeDetailWithOverlay);
        hideProgrammeDetail.run();
        // Moderno: el selector flota a la derecha sin degradado inferior; la información del
        // canal abajo a la izquierda competiría con él, así que se oculta (0.5.95).
        if (!classicUi) {
            mainHandler.removeCallbacks(hideOverlay);
            hideOverlay.run();
        }
        sourceSelectorTitle.setText(getString(R.string.source_selector_title));
        // Moderno: «TVN · 001», igual que el número que muestra el OSD.
        sourceSelectorChannel.setText(classicUi ? playbackChannel.getName()
                : playbackChannel.getName() + " · " + String.format(Locale.ROOT, "%03d",
                        publishedPlaybackCatalog.numberFor(playbackChannel, channelIndex + 1)));
        sourceSelectorOverlay.setVisibility(View.VISIBLE);
        // Moderno: «Cargando…» o «Buffer insuficiente» se centran en la zona libre, a la
        // izquierda del selector, en vez de quedar debajo de su texto (0.5.95).
        if (!classicUi && loadingPanel != null) {
            View panel = findViewById(R.id.source_selector_panel);
            int marginEnd = panel != null && panel.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) panel.getLayoutParams()).getMarginEnd() : 0;
            loadingPanel.setTranslationX(-(sourceSelectorPanelWidth() + marginEnd) / 2f);
        }
        sourceSelectorFixedHint = !classicUi && resolver instanceof TvVooStreamResolver;
        if (resolver != null && supportsSourceSelector(resolver)) {
            startSourceSelectorQuery(playbackChannel, versionsChannel, resolver);
            // Moderno (0.5.92): mientras consulta ya están las filas que tendrá al final
            // (versiones conocidas o una fila «Consultando…») y las acciones de «Este canal».
            // Antes reservaba 5 filas vacías y el panel se encogía al cargar.
            List<TvVooStreamResolver.PlannedVersion> planned = sourceSelectorFixedHint
                    ? ((TvVooStreamResolver) resolver).plannedVersions(versionsChannel) : null;
            if (planned != null && !planned.isEmpty()) {
                renderSourcePlaceholders(planned);
            } else if (!classicUi) {
                renderLoadingPlaceholder(providerLabel(resolver));
            }
        } else {
            renderDirectQualitySelector(playbackChannel);
        }
    }

    /** TvVoo moderno: el subtítulo no cambia y las filas no se mueven mientras carga. */
    boolean sourceSelectorFixedHint;
    static final int SOURCE_ROW_HEIGHT_DP = 42;
    /** Calidades agrupadas bajo su fuente (0.5.90): una sola línea, algo más bajas. */
    static final int SOURCE_QUALITY_ROW_HEIGHT_DP = 32;
    /** Acciones de «Este canal»: van después de las opciones en sourceCandidateViews. */
    final List<Runnable> sourceActionRunnables = new ArrayList<>();
    static final int SOURCE_ROW_GAP_DP = 4;
    static final int SOURCE_ROWS_VISIBLE = 5;

    // ------------------------------------------------------------------------------------
    // Selector moderno minimalista (0.5.95, mockup aprobado «Aplícalo»): texto flotante a la
    // derecha sobre un difuminado horizontal, sin paneles ni tarjetas. Filas lógicas (señal o
    // versión, calidad, acciones) que pasan a otra línea si no caben. ◀ ▶ recorre la fila,
    // ▲ ▼ salta a la opción más cercana de la línea de arriba o de abajo, OK aplica.
    // sourceCandidateViews conserva el orden de playbackOptions y después las acciones.

    static final int MINIMAL_GROUP_OPTIONS = 0;
    static final int MINIMAL_GROUP_ACTIONS = 1;
    /** Por índice de sourceCandidateViews: grupo lógico y línea visual. */
    final List<Integer> minimalItemGroup = new ArrayList<>();
    final List<Integer> minimalItemLine = new ArrayList<>();
    int minimalLineCount;

    boolean minimalSelector() {
        return !classicUi;
    }

    void beginMinimalSelector() {
        sourceSelectorOptions.removeAllViews();
        sourceCandidateViews.clear();
        sourceActionRunnables.clear();
        minimalItemGroup.clear();
        minimalItemLine.clear();
        minimalLineCount = 0;
        sourceSelectorCloseFocused = false;
    }

    TextView minimalSectionLabel(String text, boolean first) {
        TextView label = new TextView(this);
        label.setText(text.toUpperCase(java.util.Locale.ROOT));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        label.setLetterSpacing(0.2f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setTextColor(0x6BFFFFFF);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(first ? 2 : 22);
        params.bottomMargin = dp(9);
        label.setLayoutParams(params);
        sourceSelectorOptions.addView(label);
        return label;
    }

    /** Una opción de texto: título, subtítulo opcional, punto verde si está en uso. */
    View minimalOption(String title, String subtitle, boolean inUse, boolean off, boolean danger) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setPadding(0, 0, 0, dp(6));
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        titleView.setIncludeFontPadding(false);
        titleView.setSingleLine(true);
        int restColor = danger ? 0xFFFF9A9A : 0xB8FFFFFF;
        titleView.setTextColor(restColor);
        titleView.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
        titleLine.addView(titleView);
        if (inUse) {
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(0xFF5BE9A0);
            dot.setBackground(circle);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotParams.setMarginStart(dp(6));
            titleLine.addView(dot, dotParams);
        }
        // WRAP_CONTENT: con el valor por defecto (MATCH_PARENT) la opción tomaba el ancho del
        // subtítulo y cortaba el título.
        box.addView(titleLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView subtitleView = null;
        if (!AppStrings.isBlank(subtitle)) {
            subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
            subtitleView.setIncludeFontPadding(false);
            subtitleView.setSingleLine(true);
            subtitleView.setTextColor(0x73FFFFFF);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = dp(3);
            box.addView(subtitleView, subParams);
        }
        View underline = new View(this);
        underline.setBackgroundColor(0xFFFFFFFF);
        underline.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f));
        lineParams.topMargin = dp(5);
        box.addView(underline, lineParams);
        if (off) box.setAlpha(0.4f);
        final TextView subtitleRef = subtitleView;
        box.setTag(R.id.source_selector_options, new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                titleView.setTextColor(focused ? (danger ? 0xFFFFB4B4 : 0xFFFFFFFF) : restColor);
                titleView.setTypeface(null, focused ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                if (subtitleRef != null) subtitleRef.setTextColor(focused ? 0xC0FFFFFF : 0x73FFFFFF);
                underline.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            }
        });
        return box;
    }

    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Agrega una fila lógica: reparte las opciones en líneas según el ancho del panel y
     * registra cada una en sourceCandidateViews con su grupo y su línea.
     */
    void minimalRow(List<View> items, int group, List<Runnable> clicks) {
        int available = sourceSelectorPanelWidth();
        int gap = dp(24);
        LinearLayout line = null;
        int used = 0;
        for (int index = 0; index < items.size(); index++) {
            View item = items.get(index);
            item.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int width = item.getMeasuredWidth();
            if (line == null || (used > 0 && used + gap + width > available)) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (used > 0) lineParams.topMargin = dp(8);
                sourceSelectorOptions.addView(line, lineParams);
                minimalLineCount++;
                used = 0;
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (used > 0) params.setMarginStart(gap);
            line.addView(item, params);
            used += (used > 0 ? gap : 0) + width;
            final int viewIndex = sourceCandidateViews.size();
            final View.OnFocusChangeListener visual = (View.OnFocusChangeListener) item.getTag(R.id.source_selector_options);
            item.setOnFocusChangeListener((view, focused) -> {
                if (visual != null) visual.onFocusChange(view, focused);
                if (focused) {
                    sourceCandidateFocusIndex = viewIndex;
                    sourceSelectorCloseFocused = false;
                }
            });
            Runnable click = clicks.get(index);
            item.setOnClickListener(view -> click.run());
            sourceCandidateViews.add(item);
            minimalItemGroup.add(group);
            minimalItemLine.add(minimalLineCount - 1);
        }
    }

    int sourceSelectorPanelWidth() {
        View panel = findViewById(R.id.source_selector_panel);
        int width = panel == null ? 0 : panel.getWidth();
        if (width <= 0 && panel != null) width = panel.getLayoutParams().width;
        if (width <= 0) width = dp(330);
        return width - (panel == null ? 0 : panel.getPaddingLeft() + panel.getPaddingRight());
    }

    /** «Cambio automático · Ocultar en esta TV · Papelera» en una línea de texto. */
    void minimalActions(boolean optionsFirst) {
        Channel channel = playbackChannel;
        if (channel == null) return;
        List<View> items = new ArrayList<>();
        List<Runnable> clicks = new ArrayList<>();
        if (TvVooBackup.has(channel)) {
            boolean on = playbackPreferences == null || playbackPreferences.isAutoBackupEnabled();
            items.add(minimalToggle(getString(R.string.source_selector_auto_backup), on));
            Runnable toggle = () -> {
                if (playbackPreferences != null) playbackPreferences.setAutoBackupEnabled(!playbackPreferences.isAutoBackupEnabled());
                refreshMinimalToggle();
            };
            clicks.add(toggle);
            sourceActionRunnables.add(toggle);
        }
        items.add(minimalOption(getString(R.string.channel_hide_title), "", false, false, false));
        Runnable hide = () -> hideChannelOnThisTv(channel);
        clicks.add(hide);
        sourceActionRunnables.add(hide);
        items.add(minimalOption(getString(R.string.source_selector_trash_short), "", false, false, true));
        Runnable trash = () -> confirmTrashChannel(channel);
        clicks.add(trash);
        sourceActionRunnables.add(trash);
        View spacer = new View(this);
        sourceSelectorOptions.addView(spacer, new LinearLayout.LayoutParams(1, dp(optionsFirst ? 22 : 4)));
        minimalRow(items, MINIMAL_GROUP_ACTIONS, clicks);
    }

    View minimalAutoToggleView;

    View minimalToggle(String label, boolean on) {
        View option = minimalOption(label, "", false, false, false);
        LinearLayout titleLine = (LinearLayout) ((LinearLayout) option).getChildAt(0);
        FrameLayout track = new FrameLayout(this);
        View knob = new View(this);
        android.graphics.drawable.GradientDrawable knobShape = new android.graphics.drawable.GradientDrawable();
        knobShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        knobShape.setColor(0xFFFFFFFF);
        knob.setBackground(knobShape);
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(8), dp(8));
        knobParams.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
        knobParams.setMargins(dp(1.5f), 0, dp(1.5f), 0);
        track.addView(knob, knobParams);
        track.setBackground(minimalTrack(on));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(dp(19), dp(11));
        trackParams.setMarginEnd(dp(7));
        titleLine.addView(track, 0, trackParams);
        option.setTag(R.id.source_selector_scroll, track);
        minimalAutoToggleView = option;
        return option;
    }

    android.graphics.drawable.GradientDrawable minimalTrack(boolean on) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(6));
        shape.setColor(on ? getColor(R.color.cyan) : 0x59FFFFFF);
        return shape;
    }

    void refreshMinimalToggle() {
        if (minimalAutoToggleView == null || playbackPreferences == null) return;
        boolean on = playbackPreferences.isAutoBackupEnabled();
        FrameLayout track = (FrameLayout) minimalAutoToggleView.getTag(R.id.source_selector_scroll);
        if (track == null) return;
        track.setBackground(minimalTrack(on));
        View knob = track.getChildAt(0);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) knob.getLayoutParams();
        params.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
        knob.setLayoutParams(params);
    }

    /** Línea técnica tenue al pie: resolución, cuadros, códec y tasa medida. */
    void minimalMeta() {
        if (player == null) return;
        Format video = player.getVideoFormat();
        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
        List<String> parts = new ArrayList<>();
        if (video != null && video.width > 0 && video.height > 0) parts.add(video.width + "×" + video.height);
        if (measurements.displayFrameRate > 0) parts.add(trimDecimal(measurements.displayFrameRate) + " fps");
        if (video != null && video.sampleMimeType != null) parts.add(codecName(video.sampleMimeType));
        long bitrate = measurements.muxedStream ? measurements.streamBitrate : measurements.videoBitrate;
        if (bitrate > 0) parts.add(formatBitrate(bitrate));
        if (parts.isEmpty()) return;
        TextView meta = new TextView(this);
        meta.setText(TextUtils.join(" · ", parts));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        meta.setTextColor(0x80FFFFFF);
        meta.setIncludeFontPadding(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        sourceSelectorOptions.addView(meta, params);
    }

    /** «Directo · 1080p · 5.1 Mbps»: lo que está sonando, para la línea bajo el título. */
    String minimalNowPlaying(String sourceLabel) {
        List<String> parts = new ArrayList<>();
        if (!AppStrings.isBlank(sourceLabel)) parts.add(sourceLabel);
        Format video = player == null ? null : player.getVideoFormat();
        if (video != null && video.height > 0) parts.add(video.height + "p");
        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
        long bitrate = measurements.muxedStream ? measurements.streamBitrate : measurements.videoBitrate;
        if (bitrate > 0) parts.add(formatBitrate(bitrate));
        return TextUtils.join(" · ", parts);
    }

    void setMinimalStatus(String text, boolean live) {
        sourceSelectorStatus.setText(text);
        if (live) {
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(0xFF5BE9A0);
            dot.setSize(dp(6), dp(6));
            sourceSelectorStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            sourceSelectorStatus.setCompoundDrawablePadding(dp(7));
        } else {
            sourceSelectorStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        }
    }

    /**
     * Versiones TvVoo con el mismo nombre de canal («SKY SPORTS MAIN EVENT», «… HD»): se
     * muestra solo lo que las distingue («Estándar», «HD»). Otros nombres quedan iguales.
     */
    List<String> shortVersionNames(List<String> names) {
        List<String> result = new ArrayList<>(names);
        if (names.size() < 2) return result;
        String[] first = names.get(0).trim().split("\\s+");
        int common = first.length;
        for (String name : names) {
            String[] words = name.trim().split("\\s+");
            int same = 0;
            while (same < Math.min(common, words.length) && words[same].equalsIgnoreCase(first[same])) same++;
            common = same;
        }
        if (common < 2) return result;
        for (int index = 0; index < names.size(); index++) {
            String[] words = names.get(index).trim().split("\\s+");
            String rest = String.join(" ", java.util.Arrays.copyOfRange(words, Math.min(common, words.length), words.length));
            result.set(index, rest.isEmpty() ? getString(R.string.source_selector_version_standard) : rest);
        }
        return result;
    }

    static String qualityTitle(PlaybackOption option) {
        if (option.automatic) return "Auto";
        String label = option.label == null ? "" : option.label;
        int split = label.indexOf(" · ");
        return split > 0 ? label.substring(0, split) : label;
    }

    static String qualitySubtitle(PlaybackOption option) {
        if (option.automatic) return "";
        String label = option.label == null ? "" : option.label;
        int split = label.indexOf(" · ");
        return split > 0 ? label.substring(split + 3) : "";
    }

    /** Render final: señales/versiones, calidades, acciones y datos técnicos. */
    void renderMinimalOptions(String fallbackStatus) {
        beginMinimalSelector();
        List<View> sources = new ArrayList<>();
        List<Runnable> sourceClicks = new ArrayList<>();
        List<View> qualities = new ArrayList<>();
        List<Runnable> qualityClicks = new ArrayList<>();
        String inUseSource = "";
        List<String> sourceNames = new ArrayList<>();
        for (PlaybackOption option : playbackOptions) {
            if (option.sourceCandidate != null) sourceNames.add(option.label);
        }
        List<String> shortNames = sourceSelectorFixedHint ? shortVersionNames(sourceNames) : sourceNames;
        int sourcePosition = 0;
        for (int index = 0; index < playbackOptions.size(); index++) {
            final int optionIndex = index;
            PlaybackOption option = playbackOptions.get(index);
            if (option.sourceCandidate != null) {
                String shortName = shortNames.get(sourcePosition++);
                ResolvedPlaybackCandidate candidate = option.sourceCandidate;
                String subtitle = !candidate.isAvailable() ? getString(R.string.source_selector_no_signal_short)
                        : !AppStrings.isBlank(candidate.getQuality()) ? candidate.getQuality()
                        : candidate.isPreferred() ? getString(R.string.source_selector_principal_short) : "";
                sources.add(minimalOption(shortName, subtitle, option.selected, !candidate.isAvailable(), false));
                sourceClicks.add(() -> selectPlaybackOption(optionIndex));
                if (option.selected) inUseSource = shortName;
            } else {
                qualities.add(minimalOption(qualityTitle(option), qualitySubtitle(option), option.selected, false, false));
                qualityClicks.add(() -> selectPlaybackOption(optionIndex));
            }
        }
        boolean versions = sourceSelectorFixedHint;
        sourceSelectorTitle.setText(sources.isEmpty()
                ? getString(R.string.source_selector_quality_title) : getString(R.string.source_selector_title));
        if (!sources.isEmpty()) {
            minimalSectionLabel(getString(versions ? R.string.source_selector_section_versions
                    : R.string.source_selector_section_signal), true);
            minimalRow(sources, MINIMAL_GROUP_OPTIONS, sourceClicks);
        }
        if (!qualities.isEmpty()) {
            minimalSectionLabel(getString(R.string.source_selector_section_quality), sources.isEmpty());
            minimalRow(qualities, MINIMAL_GROUP_OPTIONS, qualityClicks);
        }
        boolean anyOption = !sources.isEmpty() || !qualities.isEmpty();
        if (anyOption) {
            setMinimalStatus(minimalNowPlaying(inUseSource), true);
        } else {
            setMinimalStatus(fallbackStatus, false);
        }
        minimalActions(anyOption);
        minimalMeta();
        fitSourceSelectorList(sourceCandidateViews.size());
        int focusIndex = 0;
        for (int index = 0; index < playbackOptions.size(); index++) {
            if (playbackOptions.get(index).selected) {
                focusIndex = index;
                break;
            }
        }
        if (!sourceCandidateViews.isEmpty()) {
            sourceCandidateFocusIndex = Math.min(focusIndex, sourceCandidateViews.size() - 1);
            sourceCandidateViews.get(sourceCandidateFocusIndex).requestFocus();
        }
    }

    /** Mientras consulta: las filas que tendrá (versiones conocidas o «Señal X») y las acciones. */
    void renderMinimalPlaceholders(List<String> names, List<Boolean> preferred, String status) {
        beginMinimalSelector();
        sourceSelectorTitle.setText(getString(R.string.source_selector_title));
        setMinimalStatus(status, false);
        List<View> items = new ArrayList<>();
        List<Runnable> clicks = new ArrayList<>();
        names = sourceSelectorFixedHint ? shortVersionNames(names) : names;
        for (int index = 0; index < names.size(); index++) {
            items.add(minimalOption(names.get(index), getString(R.string.source_selector_testing_short),
                    false, false, false));
            clicks.add(() -> { });
        }
        minimalSectionLabel(getString(sourceSelectorFixedHint ? R.string.source_selector_section_versions
                : R.string.source_selector_section_signal), true);
        minimalRow(items, MINIMAL_GROUP_OPTIONS, clicks);
        minimalActions(true);
        fitSourceSelectorList(sourceCandidateViews.size());
        sourceCandidateFocusIndex = 0;
        if (!sourceCandidateViews.isEmpty()) sourceCandidateViews.get(0).requestFocus();
    }

    /** ◀ ▶ dentro de la fila lógica; devuelve false si no hay a dónde ir. */
    boolean moveMinimalHorizontal(int delta) {
        int current = sourceCandidateFocusIndex;
        if (current < 0 || current >= sourceCandidateViews.size()) return false;
        int target = current + delta;
        if (target < 0 || target >= sourceCandidateViews.size()) return false;
        if (!minimalItemGroup.get(target).equals(minimalItemGroup.get(current))) return false;
        // Señal y calidad comparten grupo pero no fila visual distinta de sección: no cruzar secciones.
        if (minimalSection(target) != minimalSection(current)) return false;
        sourceCandidateViews.get(target).requestFocus();
        return true;
    }

    /** Sección = bloque bajo un mismo rótulo; dos opciones son de la misma si las une un parent común. */
    int minimalSection(int index) {
        View view = sourceCandidateViews.get(index);
        View line = (View) view.getParent();
        int section = 0;
        for (int child = 0; child < sourceSelectorOptions.getChildCount(); child++) {
            View node = sourceSelectorOptions.getChildAt(child);
            if (node instanceof TextView || !(node instanceof LinearLayout)) section++;
            if (node == line) return section;
        }
        return section;
    }

    /** ▲ ▼ a la opción más cercana en horizontal de la línea vecina. */
    void moveMinimalVertical(int delta) {
        int current = sourceCandidateFocusIndex;
        if (current < 0 || current >= sourceCandidateViews.size()) {
            if (!sourceCandidateViews.isEmpty()) sourceCandidateViews.get(0).requestFocus();
            return;
        }
        int targetLine = minimalItemLine.get(current) + delta;
        if (targetLine < 0 || targetLine >= minimalLineCount) return;
        View from = sourceCandidateViews.get(current);
        int[] location = new int[2];
        from.getLocationOnScreen(location);
        float center = location[0] + from.getWidth() / 2f;
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int index = 0; index < sourceCandidateViews.size(); index++) {
            if (minimalItemLine.get(index) != targetLine) continue;
            View candidate = sourceCandidateViews.get(index);
            candidate.getLocationOnScreen(location);
            float distance = Math.abs(location[0] + candidate.getWidth() / 2f - center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        if (best >= 0) sourceCandidateViews.get(best).requestFocus();
    }

    /** Una fila por versión conocida, con «Probando…», en el mismo lugar que tendrá al final. */
    void renderSourcePlaceholders(List<TvVooStreamResolver.PlannedVersion> versions) {
        if (versions == null || versions.isEmpty() || !isSourceSelectorVisible()) return;
        if (minimalSelector()) {
            List<String> names = new ArrayList<>();
            List<Boolean> preferred = new ArrayList<>();
            for (TvVooStreamResolver.PlannedVersion version : versions) {
                names.add(version.name);
                preferred.add(version.preferred);
            }
            renderMinimalPlaceholders(names, preferred, getString(R.string.source_selector_testing_versions));
            return;
        }
        sourceSelectorOptions.removeAllViews();
        sourceCandidateViews.clear();
        sourceActionRunnables.clear();
        sourceSelectorStatus.setText(R.string.source_selector_versions_hint);
        for (int index = 0; index < versions.size(); index++) {
            TvVooStreamResolver.PlannedVersion version = versions.get(index);
            View row = sceneRow(version.name, getString(R.string.source_selector_consulting_row), "—",
                    getString(R.string.source_selector_testing), false, true, version.preferred);
            sourceSelectorOptions.addView(row, sourceRowParams(index));
            sourceCandidateViews.add(row);
        }
        addChannelActionRows();
        fitSourceSelectorList(versions.size());
        sourceCandidateFocusIndex = 0;
        sourceCandidateViews.get(0).requestFocus();
    }

    /** Highfly y otros: una fila «Consultando…» del proveedor y las acciones del canal. */
    void renderLoadingPlaceholder(String provider) {
        if (!isSourceSelectorVisible() || sourceCandidateTask == null) return;
        if (minimalSelector()) {
            renderMinimalPlaceholders(
                    java.util.Collections.singletonList(getString(R.string.source_selector_provider_signal, provider)),
                    java.util.Collections.singletonList(false),
                    getString(R.string.source_selector_catalog_of, provider));
            return;
        }
        sourceSelectorOptions.removeAllViews();
        sourceCandidateViews.clear();
        sourceActionRunnables.clear();
        View row = sceneRow(getString(R.string.source_selector_provider_signal, provider),
                getString(R.string.source_selector_consulting_row), "",
                getString(R.string.source_selector_testing), false, true, false);
        sourceSelectorOptions.addView(row, sourceRowParams(0));
        sourceCandidateViews.add(row);
        addChannelActionRows();
        fitSourceSelectorList(sourceCandidateViews.size());
        sourceCandidateFocusIndex = 0;
        row.requestFocus();
    }

    static String providerLabel(StreamResolver resolver) {
        String id = resolver == null ? "" : resolver.getId();
        if ("tvvoo".equalsIgnoreCase(id)) return "TvVoo";
        if ("highfly".equalsIgnoreCase(id)) return "Highfly";
        return AppStrings.isBlank(id) ? "" : id;
    }

    LinearLayout.LayoutParams sourceRowParams(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(SOURCE_ROW_HEIGHT_DP));
        params.setMargins(0, index == 0 ? 0 : dp(SOURCE_ROW_GAP_DP), 0, 0);
        return params;
    }

    /**
     * La lista mide lo que ocupa de verdad (rótulos «Calidades de…» y «Este canal»
     * incluidos), hasta el alto de 5 filas; más, se desplaza. Antes contaba 42 dp por fila
     * y con una sola opción cortaba la última acción (0.5.92).
     */
    void fitSourceSelectorList(int ignoredRows) {
        View scroll = findViewById(R.id.source_selector_scroll);
        if (scroll == null || classicUi) return;
        if (minimalSelector()) {
            ViewGroup.LayoutParams wrap = scroll.getLayoutParams();
            wrap.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            scroll.setLayoutParams(wrap);
            scroll.setVerticalFadingEdgeEnabled(false);
            View hint = findViewById(R.id.source_selector_more_hint);
            if (hint != null) hint.setVisibility(View.GONE);
            return;
        }
        int max = dp(SOURCE_ROWS_VISIBLE * SOURCE_ROW_HEIGHT_DP + (SOURCE_ROWS_VISIBLE - 1) * SOURCE_ROW_GAP_DP);
        int width = scroll.getWidth();
        if (width <= 0) {
            View panel = findViewById(R.id.source_selector_panel);
            int panelWidth = panel != null && panel.getWidth() > 0 ? panel.getWidth()
                    : panel != null ? panel.getLayoutParams().width : dp(560);
            width = panelWidth - (panel != null ? panel.getPaddingLeft() + panel.getPaddingRight() : 0);
        }
        sourceSelectorOptions.measure(
                View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ViewGroup.LayoutParams params = scroll.getLayoutParams();
        params.height = Math.max(dp(SOURCE_ROW_HEIGHT_DP), Math.min(max, sourceSelectorOptions.getMeasuredHeight()));
        scroll.setLayoutParams(params);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> refreshSourceSelectorMoreHint());
        // Tras el próximo layout (0.5.94): la fila enfocada al abrir quedaba bajo el difuminado
        // y la pista «Mueve el control…» se calculaba antes de medir y no aparecía.
        scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                revealFocusedSourceRow(scroll);
                refreshSourceSelectorMoreHint();
                return true;
            }
        });
    }

    /** Deja la fila enfocada entera a la vista, fuera de los bordes difuminados. */
    void revealFocusedSourceRow(View scroll) {
        View focused = scroll.findFocus();
        if (!(scroll instanceof ViewGroup) || focused == null || focused == scroll) return;
        android.graphics.Rect rect = new android.graphics.Rect();
        focused.getDrawingRect(rect);
        ((ViewGroup) scroll).offsetDescendantRectToMyCoords(focused, rect);
        int fade = scroll.getVerticalFadingEdgeLength();
        int top = scroll.getScrollY();
        int bottom = top + scroll.getHeight();
        if (rect.bottom > bottom - fade) {
            scroll.scrollTo(0, rect.bottom - scroll.getHeight() + fade);
        } else if (top > 0 && rect.top < top + fade) {
            scroll.scrollTo(0, Math.max(0, rect.top - fade));
        }
    }

    /**
     * Degradado y pista «hay más opciones» mientras la lista pueda bajar. Al llegar al final se
     * ocultan: el degradado taparía la última fila. En clásico no se muestran (0.5.82).
     */
    void refreshSourceSelectorMoreHint() {
        View scroll = findViewById(R.id.source_selector_scroll);
        View fade = findViewById(R.id.source_selector_more_fade);
        TextView hint = findViewById(R.id.source_selector_more_hint);
        if (scroll == null || hint == null) return;
        // El difuminado ya no es una vista con color encima (sobre video claro se veía como una
        // banda sólida, 0.5.94): es el borde nativo del ScrollView, que vuelve transparentes las
        // filas y solo aparece del lado hacia donde queda contenido.
        if (fade != null) fade.setVisibility(View.GONE);
        scroll.setVerticalFadingEdgeEnabled(!classicUi);
        scroll.setFadingEdgeLength(dp(42));
        hint.setVisibility(!classicUi && scroll.canScrollVertically(1) ? View.VISIBLE : View.GONE);
    }

    static boolean supportsSourceSelector(StreamResolver resolver) {
        if (resolver == null || AppStrings.isBlank(resolver.getId())) return false;
        String id = resolver.getId();
        return "tvvoo".equalsIgnoreCase(id) || "highfly".equalsIgnoreCase(id);
    }

    void startSourceSelectorQuery(Channel channel, StreamResolver resolver) {
        startSourceSelectorQuery(channel, channel, resolver);
    }

    /**
     * Consulta las versiones de {@code resolutionChannel} para el canal en reproducción
     * {@code channel} (distintos solo cuando un canal directo tiene respaldo TvVoo).
     */
    void startSourceSelectorQuery(
            Channel channel,
            Channel resolutionChannel,
            StreamResolver resolver
    ) {
        if (channel == null || resolver == null || !isSourceSelectorVisible()) return;
        if (sourceCandidateContext != null) sourceCandidateContext.cancel();
        if (sourceCandidateTask != null) sourceCandidateTask.cancel(true);
        sourceCandidateTask = null;
        sourceCandidateContext = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        sourceCandidateViews.clear();
        sourceActionRunnables.clear();
        sourceCandidateFocusIndex = -1;
        sourceSelectorCloseFocused = false;
        sourceSelectorOptions.removeAllViews();
        sourceSelectorStatus.setText(getString(sourceSelectorFixedHint
                ? R.string.source_selector_versions_hint : R.string.source_selector_consulting));

        long requestId = ++sourceCandidateRequestId;
        long expectedGeneration = playbackGeneration;
        ResolutionContext context = new ResolutionContext(20_000L);
        sourceCandidateContext = context;
        ResolutionProgressListener listener = progress -> mainHandler.post(() -> {
            if (requestId != sourceCandidateRequestId
                    || !isSourceSelectorVisible()
                    || !isCurrentPlayback(channel, expectedGeneration)) return;
            if (!sourceSelectorFixedHint) {
                sourceSelectorStatus.setText(sourceSelectorProgressText(progress, providerLabel(resolver)));
            }
        });
        sourceCandidateTask = playbackExecutor.submit(() -> {
            try (ResolutionContext.Scope ignored = context.activate()) {
                context.check();
                List<ResolvedPlaybackCandidate> resolved = withDirectPrincipal(
                        channel,
                        resolutionChannel,
                        resolver.resolvePlaybackCandidates(resolutionChannel, listener)
                );
                context.check();
                mainHandler.post(() -> finishSourceSelectorQuery(
                        requestId,
                        channel,
                        expectedGeneration,
                        resolved
                ));
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) return;
                mainHandler.post(() -> failSourceSelectorQuery(
                        requestId,
                        channel,
                        expectedGeneration
                ));
            }
        });
    }

    void finishSourceSelectorQuery(
            long requestId,
            Channel channel,
            long expectedGeneration,
            List<ResolvedPlaybackCandidate> resolved
    ) {
        if (requestId != sourceCandidateRequestId
                || !isSourceSelectorVisible()
                || !isCurrentPlayback(channel, expectedGeneration)) return;
        sourceCandidateTask = null;
        sourceCandidateContext = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        if (resolved != null) sourceCandidates.addAll(resolved);
        if (sourceCandidates.isEmpty()) {
            // Media3 may already expose adaptive variants even when the
            // provider's alternate-source endpoint is empty. Keep the same
            // window useful instead of showing a dead end.
            renderDirectQualitySelector(channel);
            return;
        }
        renderSourceSelectorOptions();
    }

    void failSourceSelectorQuery(
            long requestId,
            Channel channel,
            long expectedGeneration
    ) {
        if (requestId != sourceCandidateRequestId
                || !isSourceSelectorVisible()
                || !isCurrentPlayback(channel, expectedGeneration)) return;
        sourceCandidateTask = null;
        sourceCandidateContext = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        sourceCandidateViews.clear();
        sourceActionRunnables.clear();
        sourceCandidateFocusIndex = -1;
        sourceSelectorCloseFocused = false;
        sourceSelectorOptions.removeAllViews();
        renderDirectQualitySelector(channel);
    }

    String sourceSelectorProgressText(ResolutionProgress progress, String provider) {
        if (progress == null || progress.getStage() == null) {
            return getString(R.string.source_selector_consulting);
        }
        int current = progress.getCurrent();
        int total = progress.getTotal();
        return switch (progress.getStage()) {
            case ALIAS_ATTEMPT -> current > 0 && total > 1
                    ? "Consultando aliases " + current + "/" + total + "…"
                    : "Consultando aliases…";
            // Highfly también pasa por aquí: antes decía «TvVoo» para todos (0.5.92).
            case CATALOG_REQUEST, CATALOG_PAGE -> AppStrings.isBlank(provider)
                    ? getString(R.string.source_selector_consulting)
                    : getString(R.string.source_selector_catalog_of, provider);
            case CATALOG_PARSED -> "Procesando fuentes publicadas…";
            case SOURCE_REQUEST -> current > 0 && total > 1
                    ? "Solicitando fuentes " + current + "/" + total + "…"
                    : "Solicitando fuentes…";
            case SOURCE_CANDIDATE -> current > 0 && total > 1
                    ? "Validando fuentes " + current + "/" + total + "…"
                    : getString(R.string.source_selector_validating);
            case HLS_PLAYLIST -> "Validando playlist HLS…";
            case HLS_VARIANT -> "Validando variante HLS…";
            case HLS_SEGMENT -> "Validando segmento HLS…";
            case SOURCE_FOUND -> getString(R.string.source_selector_ready);
            default -> getString(R.string.source_selector_consulting);
        };
    }

    void renderSourceSelectorOptions() {
        playbackOptions.clear();
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            playbackOptions.add(PlaybackOption.source(
                    candidate,
                    isCurrentSource(candidate.getSource())
            ));
        }
        if (minimalSelector() && playbackChannel != null) appendQualityOptions(playbackChannel);
        int available = 0;
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            if (candidate.isAvailable()) available++;
        }
        renderPlaybackOptions(sourceSelectorFixedHint
                ? getString(R.string.source_selector_versions_hint)
                : available == sourceCandidates.size()
                ? available == 1 ? getString(R.string.source_selector_ready_one)
                : getString(R.string.source_selector_ready_count, available)
                : getString(R.string.source_selector_versions_count, available,
                        sourceCandidates.size()));
    }

    /** Fila «escena» del selector: nombre (+ «PRINCIPAL»), detalle, calidad y estado. */
    View sceneOptionRow(PlaybackOption option) {
        ResolvedPlaybackCandidate candidate = option.sourceCandidate;
        boolean available = candidate == null || candidate.isAvailable();
        String stateText = option.selected ? getString(R.string.source_selector_in_use)
                : available ? getString(R.string.source_selector_available)
                : getString(R.string.source_selector_no_signal);
        return sceneRow(option.label, option.detail,
                candidate == null ? "" : candidate.getQuality(), stateText,
                option.selected, available, candidate != null && candidate.isPreferred());
    }

    View sceneRow(String label, String detailText, String qualityText, String stateText,
                          boolean selected, boolean available, boolean preferred) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.scene_row);
        row.setPadding(dp(13), dp(7), dp(13), dp(7));
        row.setMinimumHeight(dp(42));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        if (!available) row.setAlpha(0.42f);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setDuplicateParentStateEnabled(true);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        titleLine.setDuplicateParentStateEnabled(true);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        title.setTextColor(getColorStateList(R.color.scene_row_title));
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setDuplicateParentStateEnabled(true);
        titleLine.addView(title);
        if (preferred) {
            TextView badge = new TextView(this);
            badge.setText(R.string.source_selector_your_version);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
            badge.setLetterSpacing(0.08f);
            badge.setTextColor(getColorStateList(R.color.scene_row_detail));
            badge.setBackgroundResource(R.drawable.scene_badge);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            badge.setIncludeFontPadding(false);
            badge.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMarginStart(dp(6));
            titleLine.addView(badge, badgeParams);
        }
        text.addView(titleLine);
        if (!AppStrings.isBlank(detailText)) {
            TextView detail = new TextView(this);
            detail.setText(detailText);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            detail.setTextColor(getColorStateList(R.color.scene_row_detail));
            detail.setIncludeFontPadding(false);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(3);
            text.addView(detail, detailParams);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!AppStrings.isBlank(qualityText)) {
            TextView quality = new TextView(this);
            quality.setText(qualityText);
            quality.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            quality.setTextColor(getColorStateList(R.color.scene_row_title));
            quality.setIncludeFontPadding(false);
            quality.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            qualityParams.setMarginStart(dp(10));
            row.addView(quality, qualityParams);
        }

        if (AppStrings.isBlank(stateText)) return row;
        TextView state = new TextView(this);
        state.setText(stateText);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        // Con foco la fila es blanca: el chip pasa a tonos oscuros para leerse (0.5.92).
        state.setTextColor(getColorStateList(selected ? R.color.scene_chip_text_ok : R.color.scene_chip_text));
        state.setBackgroundResource(selected ? R.drawable.scene_chip_ok : R.drawable.scene_chip);
        state.setDuplicateParentStateEnabled(true);
        state.setPadding(dp(7), dp(3), dp(7), dp(3));
        state.setIncludeFontPadding(false);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stateParams.setMarginStart(dp(10));
        row.addView(state, stateParams);
        return row;
    }

    void renderDirectQualitySelector(Channel channel) {
        sourceCandidates.clear();
        playbackOptions.clear();
        // Canal con señal preferida o respaldos directos (0.5.66, varios desde la 0.5.72):
        // todas sus señales arriba y, debajo, sus calidades.
        int backupCount = TvVooBackup.directBackupsOf(channel).size();
        if (backupCount > 0) {
            ResolvedPlaybackSource principal = ResolvedPlaybackSource.direct(channel, PLAYER_USER_AGENT);
            sourceCandidates.add(ResolvedPlaybackCandidate.version(
                    getString(R.string.source_selector_direct_label),
                    getString(R.string.source_selector_direct_detail),
                    principal, "direct-principal", "", true, true, 0));
            for (int index = 0; index < backupCount; index++) {
                Channel backupChannel = TvVooBackup.directResolutionChannel(channel, index);
                if (backupChannel == null) continue;
                String label = backupCount == 1 ? getString(R.string.source_selector_backup_label)
                        : getString(R.string.source_selector_backup_numbered_label, index + 1);
                sourceCandidates.add(ResolvedPlaybackCandidate.version(
                        label,
                        getString(R.string.source_selector_backup_detail),
                        ResolvedPlaybackSource.direct(backupChannel, PLAYER_USER_AGENT),
                        "direct-backup-" + index, "", true, false, 0));
            }
            // «En uso» por URL; si ninguna coincide (TVN y otros pasan por su resolutor oficial y
            // la URL reproducida no es la del canal), por el estado de respaldo (0.5.94).
            boolean anyMatch = false;
            for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
                anyMatch |= isCurrentSource(candidate.getSource());
            }
            // Si lo que suena es una versión TvVoo (tiene variante), ninguna señal directa está en uso.
            boolean onTvVooVersion = currentPlaybackSource != null
                    && !AppStrings.isBlank(currentPlaybackSource.getVariantId());
            int inUse = onTvVooVersion ? -1 : tvvooBackupActive ? directBackupIndex + 1 : 0;
            for (int index = 0; index < sourceCandidates.size(); index++) {
                ResolvedPlaybackCandidate candidate = sourceCandidates.get(index);
                boolean selected = anyMatch ? isCurrentSource(candidate.getSource()) : index == inUse;
                playbackOptions.add(PlaybackOption.source(candidate, selected));
            }
        }
        List<VideoTrackOption> qualities = appendQualityOptions(channel);
        if (playbackOptions.isEmpty() && !classicUi) {
            renderPlaybackOptions(getString(R.string.source_selector_no_options));
            return;
        }
        if (playbackOptions.isEmpty()) {
            sourceCandidateViews.clear();
            sourceActionRunnables.clear();
            sourceSelectorOptions.removeAllViews();
            sourceSelectorStatus.setText(getString(R.string.source_selector_no_options));
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
            return;
        }
        // Con señales arriba se cuentan las opciones; solo calidades, las calidades (0.5.94).
        // Antes decía «1 calidades detectadas» aunque hubiera Directo y respaldos.
        renderPlaybackOptions(sourceCandidates.isEmpty()
                ? qualities.size() == 1 ? getString(R.string.source_selector_quality_one)
                : getString(R.string.source_selector_quality_count, qualities.size())
                : playbackOptions.size() == 1 ? getString(R.string.source_selector_ready_one)
                : getString(R.string.source_selector_ready_count, playbackOptions.size()));
    }

    /** Agrega «Auto» y las calidades del stream en reproducción; devuelve las calidades. */
    List<VideoTrackOption> appendQualityOptions(Channel channel) {
        List<VideoTrackOption> qualities = player == null
                ? Collections.emptyList()
                : collectVideoTrackOptions(player.getCurrentTracks());
        if (qualities.size() > 1) {
            boolean automatic = playbackPreferences != null
                    && playbackPreferences.isAutomaticQuality(channel);
            playbackOptions.add(PlaybackOption.automatic(automatic));
        }
        VideoTrackOption selected = selectedQualityOption(channel, qualities);
        for (VideoTrackOption quality : qualities) {
            playbackOptions.add(PlaybackOption.quality(
                    quality,
                    quality == selected
            ));
        }
        return qualities;
    }

    VideoTrackOption selectedQualityOption(
            Channel channel,
            List<VideoTrackOption> qualities
    ) {
        if (channel == null || qualities.isEmpty() || playbackPreferences == null) return null;
        if (playbackPreferences.isAutomaticQuality(channel)) return null;
        PlaybackPreferences.QualityPreference preference =
                playbackPreferences.getQuality(channel);
        return preference == null
                ? qualities.get(0)
                : findClosestQuality(qualities, preference);
    }

    boolean isCurrentSource(ResolvedPlaybackSource source) {
        if (source == null || currentPlaybackSource == null) return false;
        String currentVariant = currentPlaybackSource.getVariantId();
        String candidateVariant = source.getVariantId();
        if (!AppStrings.isBlank(currentVariant) && !AppStrings.isBlank(candidateVariant)) {
            return currentVariant.equals(candidateVariant);
        }
        URI currentUri = currentPlaybackSource.getPlaybackUri();
        URI candidateUri = source.getPlaybackUri();
        return currentUri != null && currentUri.equals(candidateUri);
    }

    void renderPlaybackOptions(String status) {
        if (minimalSelector()) {
            renderMinimalOptions(status);
            return;
        }
        sourceSelectorOptions.removeAllViews();
        sourceCandidateViews.clear();
        sourceActionRunnables.clear();
        sourceSelectorCloseFocused = false;
        // Calidades bajo su fuente (0.5.90): con fuentes arriba, las calidades van con sangría,
        // una línea a la izquierda y el rótulo «Calidades de <fuente en uso>».
        boolean groupQualities = false;
        String inUseSource = "";
        boolean anySource = false;
        boolean anyQuality = false;
        for (PlaybackOption option : playbackOptions) {
            if (option.sourceCandidate != null) {
                anySource = true;
                if (option.selected) inUseSource = option.label;
            } else {
                anyQuality = true;
            }
        }
        // Siempre que haya calidades (0.5.91): también en canales de una sola señal, sin fuentes arriba.
        groupQualities = !classicUi && anyQuality;
        LinearLayout qualityGroup = null;
        for (int index = 0; index < playbackOptions.size(); index++) {
            final int candidateIndex = index;
            PlaybackOption playbackOption = playbackOptions.get(index);
            if (!classicUi) {
                boolean grouped = groupQualities && playbackOption.sourceCandidate == null;
                if (grouped && qualityGroup == null) {
                    qualityGroup = sourceQualityGroup(inUseSource);
                    LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    groupParams.setMargins(0, dp(SOURCE_ROW_GAP_DP), 0, 0);
                    sourceSelectorOptions.addView((View) qualityGroup.getParent(), groupParams);
                }
                View row = sceneOptionRow(playbackOption);
                row.setOnFocusChangeListener((view, focused) -> {
                    if (focused) {
                        sourceCandidateFocusIndex = candidateIndex;
                        sourceSelectorCloseFocused = false;
                    }
                });
                row.setOnClickListener(view -> selectPlaybackOption(candidateIndex));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowParams.setMargins(0, index == 0 ? 0 : dp(SOURCE_ROW_GAP_DP), 0, 0);
                rowParams.height = dp(grouped ? SOURCE_QUALITY_ROW_HEIGHT_DP : SOURCE_ROW_HEIGHT_DP);
                if (grouped) qualityGroup.addView(row, rowParams);
                else sourceSelectorOptions.addView(row, rowParams);
                sourceCandidateViews.add(row);
                continue;
            }
            TextView option = new TextView(this);
            String detail = playbackOption.detail;
            String label = (playbackOption.selected ? "✓ " : "") + playbackOption.label;
            option.setText(AppStrings.isBlank(detail) ? label : label + "\n" + detail);
            option.setTextColor(getColorStateList(classicUi
                    ? R.color.classic_focus_option_text : R.color.focus_option_text));
            option.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.settings_control_text_size)
            );
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setIncludeFontPadding(false);
            option.setLineSpacing(0f, 0.95f);
            // Moderno: fila con velo cyan; clásico: botón plano de 0.5.40.
            option.setBackgroundResource(classicUi
                    ? R.drawable.classic_focus_button : R.drawable.settings_section_card);
            option.setPadding(dp(14), dp(6), dp(14), dp(6));
            option.setFocusable(true);
            option.setFocusableInTouchMode(true);
            option.setMinHeight(dp(44));
            option.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    sourceCandidateFocusIndex = candidateIndex;
                    sourceSelectorCloseFocused = false;
                }
            });
            option.setOnClickListener(view -> selectPlaybackOption(candidateIndex));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, index == 0 ? 0 : dp(5), 0, 0);
            sourceSelectorOptions.addView(option, params);
            sourceCandidateViews.add(option);
        }
        if (!classicUi) addChannelActionRows();
        sourceSelectorStatus.setText(status);
        if (!classicUi) fitSourceSelectorList(sourceCandidateViews.size());
        if (!sourceCandidateViews.isEmpty()) {
            // El foco parte en la fila en uso, si la hay.
            int focusIndex = 0;
            for (int index = 0; index < playbackOptions.size(); index++) {
                if (playbackOptions.get(index).selected) {
                    focusIndex = index;
                    break;
                }
            }
            sourceCandidateFocusIndex = Math.min(focusIndex, sourceCandidateViews.size() - 1);
            sourceCandidateViews.get(sourceCandidateFocusIndex).requestFocus();
            View last = sourceCandidateViews.get(sourceCandidateViews.size() - 1);
            last.setNextFocusDownId(sourceSelectorCloseButton.getId());
            sourceSelectorCloseButton.setNextFocusUpId(last.getId());
        } else {
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
        }
    }
    void moveSourceSelectorFocus(int delta) {
        if (!isSourceSelectorVisible()) return;
        boolean hasClose = sourceSelectorCloseButton.getVisibility() == View.VISIBLE;
        if (sourceCandidateViews.isEmpty()) {
            if (!hasClose) return;
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
            return;
        }
        int size = sourceCandidateViews.size() + (hasClose ? 1 : 0);
        int index = sourceSelectorCloseFocused
                ? sourceCandidateViews.size()
                : Math.max(0, sourceCandidateFocusIndex);
        index = (index + delta % size + size) % size;
        if (index == sourceCandidateViews.size()) {
            sourceSelectorCloseFocused = true;
            sourceSelectorCloseButton.requestFocus();
        } else {
            sourceSelectorCloseFocused = false;
            sourceCandidateFocusIndex = index;
            sourceCandidateViews.get(index).requestFocus();
        }
    }

    void selectFocusedSource() {
        if (sourceSelectorCloseFocused) {
            closePlaybackSourceSelector();
        } else if (sourceCandidateFocusIndex >= 0
                && sourceCandidateFocusIndex < playbackOptions.size()) {
            selectPlaybackOption(sourceCandidateFocusIndex);
        } else {
            int action = sourceCandidateFocusIndex - (sourceCandidateViews.size() - sourceActionRunnables.size());
            if (action >= 0 && action < sourceActionRunnables.size()) sourceActionRunnables.get(action).run();
        }
    }

    /** Contenedor de calidades con sangría: línea a la izquierda, rótulo y filas (0.5.90). */
    LinearLayout sourceQualityGroup(String sourceLabel) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setPadding(dp(9), 0, 0, 0);
        View guide = new View(this);
        guide.setBackgroundColor(0x73FFFFFF);
        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(
                dp(1), LinearLayout.LayoutParams.MATCH_PARENT);
        guideParams.setMarginEnd(dp(10));
        guideParams.bottomMargin = dp(6);
        outer.addView(guide, guideParams);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView header = sourceSectionHeader(AppStrings.isBlank(sourceLabel)
                ? getString(R.string.source_selector_qualities_title)
                : getString(R.string.source_selector_qualities_of, sourceLabel));
        column.addView(header);
        outer.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return column;
    }

    TextView sourceSectionHeader(String text) {
        TextView header = new TextView(this);
        header.setText(text.toUpperCase(Locale.forLanguageTag("es-CL")));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        header.setLetterSpacing(0.1f);
        header.setTextColor(getColor(R.color.osd_muted));
        header.setIncludeFontPadding(false);
        header.setPadding(0, dp(4), 0, dp(4));
        return header;
    }

    /**
     * «Este canal» al final del selector (0.5.90): ocultar en esta TV o enviar a la papelera de
     * Lista M3U. Sus filas siguen a las opciones en {@link #sourceCandidateViews}.
     */
    void addChannelActionRows() {
        Channel channel = playbackChannel != null ? playbackChannel
                : channels.isEmpty() ? null : channels.get(Math.max(0, Math.min(channelIndex, channels.size() - 1)));
        if (channel == null) return;
        TextView header = sourceSectionHeader(getString(R.string.channel_actions_title));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(12);
        sourceSelectorOptions.addView(header, headerParams);
        addChannelActionRow(getString(R.string.channel_hide_title), getString(R.string.channel_hide_detail),
                false, () -> hideChannelOnThisTv(channel));
        addChannelActionRow(getString(R.string.channel_trash_title), getString(R.string.channel_trash_detail),
                true, () -> confirmTrashChannel(channel));
    }

    void addChannelActionRow(String title, String detail, boolean danger, Runnable action) {
        View row = sceneRow(title, detail, "›", "", false, true, false);
        if (danger) {
            TextView titleView = (TextView) ((ViewGroup) ((ViewGroup) ((ViewGroup) row).getChildAt(0))
                    .getChildAt(0)).getChildAt(0);
            titleView.setTextColor(getColorStateList(R.color.channel_trash_title));
        }
        final int rowIndex = sourceCandidateViews.size();
        row.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                sourceCandidateFocusIndex = rowIndex;
                sourceSelectorCloseFocused = false;
            }
        });
        row.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(SOURCE_ROW_HEIGHT_DP));
        params.setMargins(0, dp(SOURCE_ROW_GAP_DP), 0, 0);
        sourceSelectorOptions.addView(row, params);
        sourceCandidateViews.add(row);
        sourceActionRunnables.add(action);
    }

    void hideChannelOnThisTv(Channel channel) {
        String key = PublishedPlaybackCatalog.editorRowKey(channel);
        if (AppStrings.isBlank(key)) key = "m3u:" + PlaybackPreferences.channelIdentity(channel);
        channelVisibility.hide(key, channel.getName(), System.currentTimeMillis());
        closePlaybackSourceSelector();
        if (classicUi) {
            Toast.makeText(this, getString(R.string.channel_hidden_toast, channel.getName()), Toast.LENGTH_SHORT).show();
        } else {
            showNotice(NOTICE_OK, R.string.notice_this_tv,
                    getString(R.string.notice_channel_hidden, channel.getName()), false);
        }
        reapplyChannelList();
    }

    void confirmTrashChannel(Channel channel) {
        String key = PublishedPlaybackCatalog.editorRowKey(channel);
        if (AppStrings.isBlank(key)) {
            showNotice(NOTICE_WARN, R.string.notice_trash, getString(R.string.channel_trash_unavailable), true);
            return;
        }
        closePlaybackSourceSelector();
        ChannelActionScenes.showTrashConfirm(this, channel.getName(), () -> {
            if (new GitHubCredentialStore(this).linked()) sendChannelToTrash(channel, key, true);
            else ChannelActionScenes.showGitHubLink(this, () -> sendChannelToTrash(channel, key, true));
        });
    }

    /** Deja el pedido en GitHub; si el acceso ya no sirve, pide vincular otra vez (una vez). */
    void sendChannelToTrash(Channel channel, String key, boolean mayRelink) {
        String name = channel.getName();
        showNotice(NOTICE_PROGRESS, R.string.notice_list, getString(R.string.channel_trash_sending, name), false);
        GitHubCredentialStore store = new GitHubCredentialStore(this);
        String token = store.token();
        if (token == null) {
            if (mayRelink) ChannelActionScenes.showGitHubLink(this, () -> sendChannelToTrash(channel, key, false));
            return;
        }
        networkExecutor.execute(() -> {
            try {
                new GitHubChannelTrash().requestTrash(token, key, name, BuildConfig.VERSION_NAME);
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    channelVisibility.markTrashed(key, name, System.currentTimeMillis());
                    showNotice(NOTICE_OK, R.string.notice_list, getString(R.string.channel_trash_done, name), true);
                    reapplyChannelList();
                });
            } catch (GitHubChannelTrash.UnauthorizedException unauthorized) {
                store.clear();
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    if (mayRelink) {
                        ChannelActionScenes.showGitHubLink(this, () -> sendChannelToTrash(channel, key, false));
                    } else {
                        showNotice(NOTICE_WARN, R.string.notice_trash, getString(R.string.channel_trash_failed,
                                unauthorized.getMessage()), true);
                    }
                });
            } catch (java.io.IOException error) {
                mainHandler.post(() -> showNotice(NOTICE_WARN, R.string.notice_trash,
                        getString(R.string.channel_trash_failed,
                                AppStrings.isBlank(error.getMessage()) ? "sin conexión" : error.getMessage()),
                        true));
            }
        });
    }

    void selectPlaybackOption(int index) {
        if (index < 0 || index >= playbackOptions.size() || sourceCandidateTask != null) return;
        PlaybackOption option = playbackOptions.get(index);
        if (option.isQuality()) {
            Channel channel = playbackChannel;
            if (channel == null || player == null) return;
            if (option.automatic) {
                useAutomaticQuality(channel);
            } else if (option.quality != null) {
                applyFixedQuality(channel, option.quality, true);
            }
            closePlaybackSourceSelector();
            requestDiagnosticsUpdate();
            return;
        }
        if (option.sourceCandidate != null && !option.sourceCandidate.isAvailable()) return;
        int sourceIndex = sourceCandidates.indexOf(option.sourceCandidate);
        if (sourceIndex >= 0) selectSourceCandidate(sourceIndex);
    }

    void selectSourceCandidate(int index) {
        if (sourceCandidateTask != null
                || index < 0
                || index >= sourceCandidates.size()
                || playbackChannel == null
                || player == null) return;
        ResolvedPlaybackCandidate candidate = sourceCandidates.get(index);
        // Directo y respaldos directos salen del catálogo, sin sesión del resolutor: no caducan.
        // Antes, con el selector abierto un rato, elegir un respaldo Nauta consultaba al
        // resolutor del canal y la fila quedaba solo con «Fuente actual» (0.5.95).
        boolean catalogSource = candidate.getSource() != null && !candidate.getSource().hasResolver();
        if (!catalogSource && (candidate.isStale()
                || candidate.getSource() == null
                || candidate.getSource().isExpired(System.currentTimeMillis()))) {
            Channel versions = TvVooBackup.hasTvVoo(playbackChannel)
                    ? TvVooBackup.resolutionChannel(playbackChannel) : playbackChannel;
            StreamResolver resolver = versions == null ? null : streamResolverRegistry.find(versions);
            if (resolver != null && supportsSourceSelector(resolver)) {
                startSourceSelectorQuery(playbackChannel, versions, resolver);
            } else {
                renderDirectQualitySelector(playbackChannel);
            }
            return;
        }

        Channel channel = playbackChannel;
        ResolvedPlaybackSource source = candidate.getSource();
        closePlaybackSourceSelector();
        if (!isCurrentPlayback(channel, playbackGeneration)) return;
        if (TvVooBackup.hasTvVoo(channel)) {
            tvvooBackupActive = source.hasResolver();
            rememberSourceChoice(channel, tvvooBackupActive
                    ? PlaybackPreferences.SOURCE_TVVOO_BACKUP : PlaybackPreferences.SOURCE_PRINCIPAL);
        } else if (TvVooBackup.directBackupOf(channel) != null) {
            // Elegir una señal de respaldo la deja fija también para las reconexiones.
            int backupIndex = TvVooBackup.directBackupsOf(channel).indexOf(source.getPlaybackUri());
            tvvooBackupActive = backupIndex >= 0;
            directBackupIndex = Math.max(0, backupIndex);
            rememberSourceChoice(channel, backupIndex >= 0
                    ? PlaybackPreferences.directBackupChoice(source.getPlaybackUri())
                    : PlaybackPreferences.SOURCE_PRINCIPAL);
            URI selectedReference = source.getPlaybackUri();
            if (backupIndex >= 0
                    && DynamicSourceReference.isAppOnly(selectedReference)
                    && "nauta".equals(DynamicSourceReference.provider(selectedReference))) {
                // Nauta backups are tokenless locators, not playable HTTP URLs. Resolve the
                // selected row with its own identity through the normal resolver registry.
                playbackHasStarted = false;
                playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
                playbackAutoRecoveryInFlight = false;
                playbackRecoveryFailed = false;
                resetPlaybackBitrateMeter();
                setStatus("CARGANDO", R.color.amber);
                showLoadingState(getString(R.string.source_selector_switching));
                resolveAndPlay(channel, playbackGeneration, true);
                return;
            }
            if (backupIndex < 0 && streamResolverRegistry != null
                    && streamResolverRegistry.find(channel) != null) {
                // «Directo» de un canal con resolutor (TVN): se pide de nuevo la señal oficial;
                // su dirección publicada es solo un respaldo y puede no responder.
                closePlaybackSourceSelector();
                playbackHasStarted = false;
                playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
                playbackAutoRecoveryInFlight = false;
                playbackRecoveryFailed = false;
                resetPlaybackBitrateMeter();
                setStatus("CARGANDO", R.color.amber);
                showLoadingState(getString(R.string.source_selector_switching));
                resolveAndPlay(channel, playbackGeneration, true);
                return;
            }
        }
        // Versión TvVoo elegida (canal TvVoo o respaldo TvVoo): se prueba primero las próximas veces.
        if (source.hasResolver() && "tvvoo".equalsIgnoreCase(source.getResolverId())
                && !AppStrings.isBlank(source.getVariantId())) {
            TvVooSourceHistory.pinAlias(source.getStableSourceId(), source.getVariantId());
        } else if (source.hasResolver() && playbackPreferences != null) {
            // Highfly y otros: se recuerda la fuente por su nombre en el selector.
            playbackPreferences.rememberResolverSourceChoice(
                    channel, source.getResolverId(), candidate.getLabel());
        }

        playbackHasStarted = false;
        playbackLoadingSinceElapsedRealtime = SystemClock.elapsedRealtime();
        playbackAutoRecoveryInFlight = false;
        playbackRecoveryFailed = false;
        resetPlaybackBitrateMeter();
        player.stop();
        player.clearMediaItems();
        discardCurrentPlaybackSource();
        long requestId = ++playbackResolutionRequestId;
        setStatus("CARGANDO", R.color.amber);
        showLoadingState(getString(R.string.source_selector_switching));
        startResolvedPlayback(channel, source, playbackGeneration, requestId);
    }

    void closePlaybackSourceSelector() {
        sourceCandidateRequestId++;
        if (loadingPanel != null) loadingPanel.setTranslationX(0f);
        if (sourceCandidateContext != null) sourceCandidateContext.cancel();
        sourceCandidateContext = null;
        if (sourceCandidateTask != null) sourceCandidateTask.cancel(true);
        sourceCandidateTask = null;
        sourceCandidates.clear();
        playbackOptions.clear();
        sourceCandidateViews.clear();
        sourceActionRunnables.clear();
        sourceCandidateFocusIndex = -1;
        sourceSelectorCloseFocused = false;
        if (sourceSelectorOptions != null) sourceSelectorOptions.removeAllViews();
        if (sourceSelectorOverlay != null) sourceSelectorOverlay.setVisibility(View.GONE);
    }
}

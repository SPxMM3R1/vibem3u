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
        // Moderno: «CALIDAD · 036 · TVN», con número y nombre como el OSD (0.6.22).
        minimalChannelLabel = String.format(Locale.ROOT, "%03d",
                publishedPlaybackCatalog.numberFor(playbackChannel, channelIndex + 1))
                + " · " + playbackChannel.getName();
        sourceSelectorChannel.setText(classicUi ? playbackChannel.getName() : minimalChannelLabel);
        sourceSelectorOverlay.setVisibility(View.VISIBLE);
        // Moderno: «Cargando…» o «Buffer insuficiente» suben a la zona libre, sobre la franja
        // del selector, en vez de quedar debajo de su texto (0.6.22: antes iba a la izquierda).
        if (!classicUi && loadingPanel != null) {
            View panel = findViewById(R.id.source_selector_panel);
            int height = panel != null && panel.getHeight() > 0 ? panel.getHeight() : dp(170);
            loadingPanel.setTranslationY(-height / 2f);
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
    // Selector moderno minimalista (0.5.95; escenario abajo desde la 0.6.22): texto flotante
    // sobre un difuminado, sin paneles ni tarjetas. Líneas lógicas: la escala, la señal o
    // versión en línea y las acciones. ◀ ▶ recorre la línea, ▲ ▼ salta a la opción más
    // cercana de la línea de arriba o de abajo, OK aplica.
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

    // Escenario (0.6.22, mockup «F2» aprobado): el selector moderno es una franja abajo con el
    // lenguaje del OSD. Etiqueta con punto verde, la calidad actual grande, una escala de tramos
    // (el que está en uso en cian), la señal en una línea y, a la derecha, las acciones y la
    // línea técnica. Tamaños: los del mockup de 1920×1080 a la mitad.
    static final float TYPE_TITLE_SP = 30f;      // calidad actual («720p»)
    static final float TYPE_META_SP = 14f;       // «2.2 Mbps · 60 fps» junto a la calidad
    static final float TYPE_OPTION_SP = 12f;     // tramos de la escala y acciones
    static final float TYPE_INLINE_SP = 11f;     // señal o versiones en línea
    static final float TYPE_DETAIL_SP = 8.5f;    // Mbps bajo cada tramo
    static final float TYPE_LABEL_SP = 8.5f;     // «SEÑAL»
    static final float TYPE_TECH_SP = 10f;       // línea técnica
    /** Ancho de un tramo de la escala. */
    static final int STAGE_SEGMENT_DP = 112;
    /** La escala no pasa de esta fracción del ancho: a su derecha van las acciones. */
    static final float STAGE_LADDER_MAX_FRACTION = 0.6f;

    TextView minimalSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text.toUpperCase(java.util.Locale.ROOT));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_LABEL_SP);
        label.setLetterSpacing(0.2f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setTextColor(0x73FFFFFF);
        label.setIncludeFontPadding(false);
        return label;
    }

    /** Una opción de texto en línea: título (con su detalle tenue), punto verde si está en uso. */
    View minimalOption(CharSequence title, boolean inUse, boolean off, boolean danger, float titleSp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        titleView.setIncludeFontPadding(false);
        titleView.setSingleLine(true);
        int restColor = danger ? 0xFFFF9A9A : inUse ? 0xFFFFFFFF : 0x9EFFFFFF;
        titleView.setTextColor(restColor);
        titleView.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
        if (inUse) titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleLine.addView(titleView);
        if (inUse) titleLine.addView(minimalDot(), minimalDotParams());
        // WRAP_CONTENT: con MATCH_PARENT la línea de foco tomaba el ancho del contenedor.
        box.addView(titleLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View underline = minimalUnderline();
        box.addView(underline, minimalUnderlineParams());
        if (off) box.setAlpha(0.4f);
        box.setTag(R.id.source_selector_options, (View.OnFocusChangeListener) (view, focused) -> {
            titleView.setTextColor(focused ? (danger ? 0xFFFFB4B4 : 0xFFFFFFFF) : restColor);
            titleView.setTypeface(null, focused || inUse
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            underline.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        });
        return box;
    }

    /** Un tramo de la escala: raya arriba (cian si está en uso), nombre y su detalle debajo. */
    View minimalSegment(String title, String subtitle, boolean inUse, boolean off, int width) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        View bar = new View(this);
        bar.setBackgroundColor(inUse ? getColor(R.color.cyan) : 0x2EFFFFFF);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(inUse ? 2f : 1.5f));
        barParams.setMarginEnd(dp(5));
        box.addView(bar, barParams);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_OPTION_SP);
        titleView.setIncludeFontPadding(false);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setMaxWidth(Math.max(1, width - dp(9)));
        int restColor = inUse ? 0xFFFFFFFF : 0x99FFFFFF;
        titleView.setTextColor(restColor);
        titleView.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
        if (inUse) titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        text.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView subtitleView = null;
        if (!AppStrings.isBlank(subtitle)) {
            subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_DETAIL_SP);
            subtitleView.setFontFeatureSettings("tnum");
            subtitleView.setIncludeFontPadding(false);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setMaxWidth(Math.max(1, width - dp(9)));
            subtitleView.setTextColor(0x66FFFFFF);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = dp(2.5f);
            text.addView(subtitleView, subParams);
        }
        View underline = minimalUnderline();
        text.addView(underline, minimalUnderlineParams());
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(8);
        box.addView(text, textParams);
        if (off) box.setAlpha(0.4f);
        final TextView subtitleRef = subtitleView;
        box.setTag(R.id.source_selector_options, (View.OnFocusChangeListener) (view, focused) -> {
            titleView.setTextColor(focused ? 0xFFFFFFFF : restColor);
            titleView.setTypeface(null, focused || inUse
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            if (subtitleRef != null) subtitleRef.setTextColor(focused ? 0xA6FFFFFF : 0x66FFFFFF);
            underline.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        });
        return box;
    }

    /** «Respaldo 1  1080p»: el detalle va en la misma línea, más chico y tenue. */
    CharSequence inlineTitle(String name, String detail) {
        if (AppStrings.isBlank(detail)) return name;
        SpannableString text = new SpannableString(name + "  " + detail);
        text.setSpan(new ForegroundColorSpan(0x66FFFFFF), name.length(), text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.RelativeSizeSpan(0.8f), name.length(), text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    View minimalDot() {
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0xFF5BE9A0);
        dot.setBackground(circle);
        return dot;
    }

    LinearLayout.LayoutParams minimalDotParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(5f), dp(5f));
        params.setMarginStart(dp(5));
        return params;
    }

    View minimalUnderline() {
        View underline = new View(this);
        underline.setBackgroundColor(0xFFFFFFFF);
        underline.setVisibility(View.INVISIBLE);
        return underline;
    }

    LinearLayout.LayoutParams minimalUnderlineParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f));
        params.topMargin = dp(4);
        return params;
    }

    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Foco, clic y línea lógica (para ▲ ▼ y ◀ ▶) de una opción del selector. */
    void registerMinimalItem(View item, int group, Runnable click, int line) {
        final int viewIndex = sourceCandidateViews.size();
        final View.OnFocusChangeListener visual =
                (View.OnFocusChangeListener) item.getTag(R.id.source_selector_options);
        item.setOnFocusChangeListener((view, focused) -> {
            if (visual != null) visual.onFocusChange(view, focused);
            if (focused) {
                sourceCandidateFocusIndex = viewIndex;
                sourceSelectorCloseFocused = false;
            }
        });
        item.setOnClickListener(view -> click.run());
        sourceCandidateViews.add(item);
        minimalItemGroup.add(group);
        minimalItemLine.add(line);
        minimalLineCount = Math.max(minimalLineCount, line + 1);
    }

    int sourceSelectorPanelWidth() {
        View panel = findViewById(R.id.source_selector_panel);
        int width = panel == null ? 0 : panel.getWidth();
        if (width <= 0 && panel != null) width = panel.getLayoutParams().width;
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels - dp(64);
        return width - (panel == null ? 0 : panel.getPaddingLeft() + panel.getPaddingRight());
    }

    /** Ancho de cada tramo: 112 dp, o menos si son muchos para el 60 % del ancho. */
    int stageSegmentWidth(int count) {
        int max = Math.round(sourceSelectorPanelWidth() * STAGE_LADDER_MAX_FRACTION);
        return Math.max(dp(48), Math.min(dp(STAGE_SEGMENT_DP), max / Math.max(1, count)));
    }

    /** Contenedores del escenario, armados por {@link #minimalStage}. */
    LinearLayout stageLadder;
    LinearLayout stageInline;
    LinearLayout stageActions;
    LinearLayout stageRight;
    LinearLayout stageBottom;

    /**
     * Escenario: arriba la escala y, a la derecha, las acciones con la línea técnica debajo;
     * abajo, la señal en línea con todo el ancho (hasta 9 versiones TvVoo). Las opciones se
     * agregan después con {@link #stageAdd}.
     */
    void minimalStage(String inlineLabel) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        stageLadder = new LinearLayout(this);
        stageLadder.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(stageLadder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        stageRight = new LinearLayout(this);
        stageRight.setOrientation(LinearLayout.VERTICAL);
        stageRight.setGravity(Gravity.END);
        stageActions = new LinearLayout(this);
        stageActions.setOrientation(LinearLayout.HORIZONTAL);
        stageActions.setGravity(Gravity.BOTTOM);
        stageRight.addView(stageActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        top.addView(stageRight, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sourceSelectorOptions.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Arriba, no centrado: la línea de foco cuelga bajo cada opción y no debe cortarse.
        stageBottom = new LinearLayout(this);
        stageBottom.setOrientation(LinearLayout.HORIZONTAL);
        stageBottom.setGravity(Gravity.TOP);
        stageInline = new LinearLayout(this);
        stageInline.setOrientation(LinearLayout.HORIZONTAL);
        stageInline.setGravity(Gravity.TOP);
        if (!AppStrings.isBlank(inlineLabel)) {
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.setMarginEnd(dp(17));
            labelParams.topMargin = dp(2.5f);
            stageInline.addView(minimalSectionLabel(inlineLabel), labelParams);
        }
        stageBottom.addView(stageInline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = dp(15);
        sourceSelectorOptions.addView(stageBottom, bottomParams);
    }

    /** Una opción en la escala ({@code ladder}) o en la línea de abajo. */
    void stageAdd(View item, boolean ladder, int width) {
        if (ladder) {
            stageLadder.addView(item, new LinearLayout.LayoutParams(
                    width, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            // El rótulo «SEÑAL» es el primer hijo: la primera opción no lleva margen extra.
            boolean first = stageInline.getChildCount() == 0
                    || (stageInline.getChildCount() == 1 && stageInline.getChildAt(0) instanceof TextView);
            if (!first) params.setMarginStart(dp(17));
            stageInline.addView(item, params);
        }
    }

    /** «Cambio automático · Ocultar en esta TV · Papelera» a la derecha de la escala. */
    void minimalActions(int line) {
        Channel channel = playbackChannel;
        if (channel == null || stageActions == null) return;
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
        }
        items.add(minimalOption(getString(R.string.channel_hide_title), false, false, false, TYPE_OPTION_SP));
        clicks.add(() -> hideChannelOnThisTv(channel));
        items.add(minimalOption(getString(R.string.source_selector_trash_short), false, false, true, TYPE_OPTION_SP));
        clicks.add(() -> confirmTrashChannel(channel));
        for (int index = 0; index < items.size(); index++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (index > 0) params.setMarginStart(dp(20));
            stageActions.addView(items.get(index), params);
            sourceActionRunnables.add(clicks.get(index));
            registerMinimalItem(items.get(index), MINIMAL_GROUP_ACTIONS, clicks.get(index), line);
        }
    }

    View minimalAutoToggleView;

    View minimalToggle(String label, boolean on) {
        View option = minimalOption(label, false, false, false, TYPE_OPTION_SP);
        LinearLayout titleLine = (LinearLayout) ((LinearLayout) option).getChildAt(0);
        FrameLayout track = new FrameLayout(this);
        View knob = new View(this);
        android.graphics.drawable.GradientDrawable knobShape = new android.graphics.drawable.GradientDrawable();
        knobShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        knobShape.setColor(0xFFFFFFFF);
        knob.setBackground(knobShape);
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(7f), dp(7f));
        knobParams.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
        knobParams.setMargins(dp(1.5f), 0, dp(1.5f), 0);
        track.addView(knob, knobParams);
        track.setBackground(minimalTrack(on));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(dp(17), dp(9.5f));
        trackParams.setMarginEnd(dp(6));
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

    /** Línea técnica a la derecha de la señal: resolución, cuadros, códec y tasa medida. */
    TextView minimalTech() {
        if (player == null) return null;
        Format video = player.getVideoFormat();
        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
        List<String> parts = new ArrayList<>();
        if (video != null && video.width > 0 && video.height > 0) parts.add(video.width + " × " + video.height);
        float frameRate = shownFrameRate(measurements);
        if (frameRate > 0) parts.add(trimDecimal(frameRate) + " FPS");
        if (video != null && video.sampleMimeType != null) parts.add(codecName(video.sampleMimeType));
        long bitrate = measurements.muxedStream ? measurements.streamBitrate : measurements.videoBitrate;
        if (bitrate > 0) parts.add(formatBitrate(bitrate));
        if (parts.isEmpty()) return null;
        TextView meta = new TextView(this);
        meta.setText(TextUtils.join(" · ", parts));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_TECH_SP);
        meta.setTextColor(0x8CFFFFFF);
        meta.setFontFeatureSettings("tnum");
        meta.setIncludeFontPadding(false);
        meta.setSingleLine(true);
        meta.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
        return meta;
    }

    /** La calidad que se está viendo («720p»), o vacío si el video aún no informa su alto. */
    String minimalHeroQuality() {
        Format video = player == null ? null : player.getVideoFormat();
        return video != null && video.height > 0 ? video.height + "p" : "";
    }

    /** «2.2 Mbps · 60 fps»: junto a la calidad grande. */
    String minimalHeroMeta() {
        List<String> parts = new ArrayList<>();
        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
        long bitrate = measurements.muxedStream ? measurements.streamBitrate : measurements.videoBitrate;
        if (bitrate > 0) parts.add(formatBitrate(bitrate));
        float frameRate = shownFrameRate(measurements);
        if (frameRate > 0) parts.add(trimDecimal(frameRate) + " fps");
        return TextUtils.join(" · ", parts);
    }

    /** «036 · ESPN 2»: se antepone la sección en la etiqueta («CALIDAD · 036 · ESPN 2»). */
    String minimalChannelLabel = "";

    void setMinimalEyebrow(String section) {
        sourceSelectorChannel.setText(AppStrings.isBlank(minimalChannelLabel)
                ? section : section + " · " + minimalChannelLabel);
    }

    /** Texto junto a la calidad grande; con señal en vivo, la etiqueta lleva el punto verde. */
    void setMinimalStatus(String text, boolean live) {
        sourceSelectorStatus.setText(text);
        if (live) {
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(0xFF5BE9A0);
            dot.setSize(dp(5f), dp(5f));
            sourceSelectorChannel.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            sourceSelectorChannel.setCompoundDrawablePadding(dp(8));
        } else {
            sourceSelectorChannel.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
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

    /**
     * Render final: la escala (calidades; si no hay, las señales o versiones), la señal o
     * versión en línea debajo, las acciones y la línea técnica. Las vistas se registran en el
     * orden de playbackOptions: selectFocusedSource usa ese mismo índice.
     */
    void renderMinimalOptions(String fallbackStatus) {
        beginMinimalSelector();
        List<String> sourceNames = new ArrayList<>();
        int qualityCount = 0;
        for (PlaybackOption option : playbackOptions) {
            if (option.sourceCandidate != null) sourceNames.add(option.label);
            else qualityCount++;
        }
        boolean hasSources = !sourceNames.isEmpty();
        boolean hasQualities = qualityCount > 0;
        boolean versions = sourceSelectorFixedHint;
        String sourceLabel = getString(versions ? R.string.source_selector_section_versions
                : R.string.source_selector_section_signal);
        boolean sourcesInLadder = !hasQualities;
        List<String> shortNames = versions ? shortVersionNames(sourceNames) : sourceNames;
        minimalStage(hasSources && !sourcesInLadder ? sourceLabel : "");
        int segment = stageSegmentWidth(sourcesInLadder ? sourceNames.size() : qualityCount);
        int actionsLine = hasSources && hasQualities ? 2 : 1;
        String inUseSource = "";
        int sourcePosition = 0;
        for (int index = 0; index < playbackOptions.size(); index++) {
            final int optionIndex = index;
            PlaybackOption option = playbackOptions.get(index);
            View view;
            boolean ladder;
            if (option.sourceCandidate != null) {
                String shortName = shortNames.get(sourcePosition++);
                ResolvedPlaybackCandidate candidate = option.sourceCandidate;
                String subtitle = !candidate.isAvailable() ? getString(R.string.source_selector_no_signal_short)
                        : !AppStrings.isBlank(candidate.getQuality()) ? candidate.getQuality()
                        : candidate.isPreferred() ? getString(R.string.source_selector_principal_short) : "";
                ladder = sourcesInLadder;
                view = ladder
                        ? minimalSegment(shortName, subtitle, option.selected, !candidate.isAvailable(), segment)
                        : minimalOption(inlineTitle(shortName, subtitle), option.selected,
                                !candidate.isAvailable(), false, TYPE_INLINE_SP);
                if (option.selected) inUseSource = shortName;
            } else {
                ladder = true;
                view = minimalSegment(qualityTitle(option), qualitySubtitle(option), option.selected, false, segment);
            }
            stageAdd(view, ladder, segment);
            registerMinimalItem(view, MINIMAL_GROUP_OPTIONS, () -> selectPlaybackOption(optionIndex), ladder ? 0 : 1);
        }
        String section = hasSources && hasQualities ? getString(R.string.source_selector_title)
                : hasQualities ? getString(R.string.source_selector_quality_title) : sourceLabel;
        setMinimalEyebrow(section);
        String hero = minimalHeroQuality();
        sourceSelectorTitle.setText(!AppStrings.isBlank(hero) ? hero
                : !AppStrings.isBlank(inUseSource) ? inUseSource : section);
        boolean anyOption = hasSources || hasQualities;
        if (anyOption) {
            setMinimalStatus(minimalHeroMeta(), true);
        } else {
            setMinimalStatus(fallbackStatus, false);
        }
        minimalActions(actionsLine);
        TextView tech = minimalTech();
        if (tech != null) {
            LinearLayout.LayoutParams techParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            techParams.topMargin = dp(10);
            stageRight.addView(tech, techParams);
        }
        fitSourceSelectorList(sourceCandidateViews.size());
        int focusIndex = 0;
        for (int index = 0; index < playbackOptions.size(); index++) {
            if (playbackOptions.get(index).selected && minimalItemLine.get(index) == 0) {
                focusIndex = index;
                break;
            }
        }
        if (!sourceCandidateViews.isEmpty()) {
            sourceCandidateFocusIndex = Math.min(focusIndex, sourceCandidateViews.size() - 1);
            sourceCandidateViews.get(sourceCandidateFocusIndex).requestFocus();
        }
    }

    /** Mientras consulta: los tramos que tendrá (versiones conocidas o «Señal X») y las acciones. */
    void renderMinimalPlaceholders(List<String> names, List<Boolean> preferred, String status) {
        beginMinimalSelector();
        minimalStage("");
        String section = getString(sourceSelectorFixedHint ? R.string.source_selector_section_versions
                : R.string.source_selector_section_signal);
        setMinimalEyebrow(section);
        String hero = minimalHeroQuality();
        sourceSelectorTitle.setText(AppStrings.isBlank(hero) ? section : hero);
        setMinimalStatus(status, false);
        names = sourceSelectorFixedHint ? shortVersionNames(names) : names;
        int segment = stageSegmentWidth(names.size());
        for (int index = 0; index < names.size(); index++) {
            View view = minimalSegment(names.get(index), getString(R.string.source_selector_testing_short),
                    false, false, segment);
            stageAdd(view, true, segment);
            registerMinimalItem(view, MINIMAL_GROUP_OPTIONS, () -> { }, 0);
        }
        minimalActions(1);
        fitSourceSelectorList(sourceCandidateViews.size());
        sourceCandidateFocusIndex = 0;
        if (!sourceCandidateViews.isEmpty()) sourceCandidateViews.get(0).requestFocus();
    }

    /** ◀ ▶ dentro de la misma línea (escala, señal o acciones); false si no hay a dónde ir. */
    boolean moveMinimalHorizontal(int delta) {
        int current = sourceCandidateFocusIndex;
        if (current < 0 || current >= sourceCandidateViews.size()) return false;
        int line = minimalItemLine.get(current);
        // La señal se registra entre las calidades (orden de playbackOptions): se busca la
        // siguiente opción de la misma línea, no la vecina en la lista.
        for (int target = current + delta; target >= 0 && target < sourceCandidateViews.size(); target += delta) {
            if (minimalItemLine.get(target) != line) continue;
            if (!minimalItemGroup.get(target).equals(minimalItemGroup.get(current))) return false;
            sourceCandidateViews.get(target).requestFocus();
            return true;
        }
        return false;
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

    /** Deja el pedido en GitHub; solo reabre el Device Flow si GitHub confirma que no puede renovar. */
    void sendChannelToTrash(Channel channel, String key, boolean mayRelink) {
        String name = channel.getName();
        showNotice(NOTICE_PROGRESS, R.string.notice_list, getString(R.string.channel_trash_sending, name), false);
        GitHubCredentialStore store = new GitHubCredentialStore(this);
        networkExecutor.execute(() -> {
            // Varias solicitudes de papelera comparten la misma credencial. Serializarlas evita
            // que dos respuestas 401 intenten consumir a la vez el mismo refresh token rotatorio.
            synchronized (GitHubCredentialStore.AUTH_LOCK) {
                GitHubCredentialStore.Credentials credentials = store.credentials();
                if (credentials == null) {
                    mainHandler.post(() -> {
                        if (!isFinishing() && mayRelink) {
                            ChannelActionScenes.showGitHubLink(this,
                                    () -> sendChannelToTrash(channel, key, false));
                        }
                    });
                    return;
                }
                try {
                    new GitHubChannelTrash().requestTrash(credentials, key, name, BuildConfig.VERSION_NAME,
                            rotated -> {
                                try {
                                    store.save(rotated);
                                } catch (java.security.GeneralSecurityException saveError) {
                                    throw new java.io.IOException(
                                            "No se pudo guardar la renovación de GitHub.", saveError);
                                }
                            });
                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        channelVisibility.markTrashed(key, name, System.currentTimeMillis());
                        showNotice(NOTICE_OK, R.string.notice_list,
                                getString(R.string.channel_trash_done, name), true);
                        reapplyChannelList();
                    });
                } catch (GitHubChannelTrash.ReauthorizationRequiredException reauthorizationRequired) {
                    store.clear();
                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        if (mayRelink) {
                            ChannelActionScenes.showGitHubLink(this,
                                    () -> sendChannelToTrash(channel, key, false));
                        } else {
                            showNotice(NOTICE_WARN, R.string.notice_trash,
                                    getString(R.string.channel_trash_failed,
                                            reauthorizationRequired.getMessage()), true);
                        }
                        });
                } catch (java.io.IOException error) {
                    mainHandler.post(() -> showNotice(NOTICE_WARN, R.string.notice_trash,
                            getString(R.string.channel_trash_failed,
                                    AppStrings.isBlank(error.getMessage()) ? "sin conexión" : error.getMessage()),
                            true));
                }
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
        if (loadingPanel != null) {
            loadingPanel.setTranslationX(0f);
            loadingPanel.setTranslationY(0f);
        }
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

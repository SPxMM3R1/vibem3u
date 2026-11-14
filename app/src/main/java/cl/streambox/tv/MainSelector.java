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
import java.util.HashMap;
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
        if (!classicUi) {
            // Animaciones B: la franja sube desde abajo con resorte.
            Motion.enter(findViewById(R.id.source_selector_panel), 0, dp(28f));
        }
        // Moderno: «Cargando…» o «Buffer insuficiente» suben a la zona libre, sobre la franja
        // del selector, en vez de quedar debajo de su texto (0.6.22: antes iba a la izquierda).
        if (!classicUi && loadingPanel != null) {
            View panel = findViewById(R.id.source_selector_panel);
            int height = panel != null && panel.getHeight() > 0 ? panel.getHeight() : dp(170);
            loadingPanel.setTranslationY(-height / 2f);
        }
        sourceSelectorFixedHint = !classicUi && resolver instanceof TvVooStreamResolver;
        minimalFocusActions = false;
        minimalFocusOpening = true;
        if (resolver != null && supportsSourceSelector(resolver)) {
            startSourceSelectorQuery(playbackChannel, versionsChannel, resolver);
            // Moderno (0.5.92): mientras consulta ya están las filas que tendrá al final
            // (versiones conocidas o una fila «Consultando…») y las acciones de «Este canal».
            // Antes reservaba 5 filas vacías y el panel se encogía al cargar.
            List<TvVooStreamResolver.PlannedVersion> planned = sourceSelectorFixedHint
                    ? ((TvVooStreamResolver) resolver).plannedVersions(versionsChannel) : null;
            if (sourceSelectorFixedHint && minimalSelector()) {
                renderProviderPlaceholders(planned);
            } else if (planned != null && !planned.isEmpty()) {
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
        screensMode = false;
        screensQualities.clear();
        screensSignals.clear();
        sourceSelectorCloseFocused = false;
        resetProviderStage();
        pulsePlayingVariant = "";
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
            // Animaciones B: la línea viaja desde la opción anterior y la opción (texto y línea
            // juntos) crece.
            Motion.focusLine(underline, focused);
            Motion.focusScale(box, focused, Motion.FOCUS_SCALE);
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
            Motion.focusLine(underline, focused);
            Motion.focusScale(text, focused, Motion.FOCUS_SCALE);
        });
        return box;
    }

    // Pulso (0.6.48, mockup «V2 · Pulso» aprobado): con respaldos directos, la señal es una fila
    // de barras, una por señal, con alto = bitrate medido (backups.json). Blanca la marcada,
    // verde la que suena, un guion abajo si no abrió en la última medición. A la derecha, el
    // detalle de la marcada. Tamaños: los del mockup de 1920×1080 a la mitad.
    static final float PULSE_MAX_DP = 46f;
    static final float PULSE_BAR_DP = 6f;
    static final float PULSE_PRINCIPAL_BAR_DP = 11f;

    /** Calidad medida de cada señal directa, por su variantId («direct-principal», «direct-backup-N»). */
    final Map<String, PublishedDirectBackups.Quality> pulseQuality = new HashMap<>();
    TextView pulseEyebrow;
    TextView pulseQualityLine;
    TextView pulseStatus;

    static boolean isDirectSignal(ResolvedPlaybackCandidate candidate) {
        return candidate != null && candidate.getVariantId().startsWith("direct-");
    }

    /** «P» para la principal y «09» para el respaldo 9. */
    static String pulseNumber(int position) {
        return position == 0 ? "P" : String.format(java.util.Locale.ROOT, "%02d", position);
    }

    /**
     * Medición en vivo (0.6.49, pedido del usuario «que mida cuando el canal está abierto, como
     * TvVoo»): al abrir el selector se baja un segmento de cada señal (tres a la vez, en segundo
     * plano) y la barra pasa de la medición diaria a la de ahora. Se guarda mientras no cambie el
     * canal; cerrar el selector cancela lo pendiente.
     */
    final ExecutorService pulseExecutor = idlePool(3, "vibem3u-pulse", true);
    final Map<String, PublishedDirectBackups.Quality> pulseLive = new HashMap<>();
    final Set<String> pulsePending = new HashSet<>();
    final List<Future<?>> pulseJobs = new ArrayList<>();
    final List<PulseBar> pulseBars = new ArrayList<>();
    String pulseLiveChannel = "";
    int pulseSession;
    int pulseTotal;
    int pulseInUse = -1;

    /** Una barra del pulso y lo que se sabe de su señal. */
    static final class PulseBar {
        final int position;
        final String variant;
        final boolean inUse;
        final View bar;
        final TextView tag;
        final android.graphics.drawable.GradientDrawable shape;
        PublishedDirectBackups.Quality quality;
        boolean focused;

        PulseBar(int position, String variant, boolean inUse, View bar, TextView tag,
                android.graphics.drawable.GradientDrawable shape) {
            this.position = position;
            this.variant = variant;
            this.inUse = inUse;
            this.bar = bar;
            this.tag = tag;
            this.shape = shape;
        }
    }

    /**
     * Señal que suena (0.6.60, pedido del usuario: «si está en reproducción, ¿no debería usar los
     * datos en tiempo real?»): se reconoce con la misma regla que la pinta de verde, no por su URL
     * (TVN y otros abren por su resolutor y la URL no calza), y nunca se vuelve a bajar.
     */
    String pulsePlayingVariant = "";

    /** Mientras el selector está abierto, la barra de la que suena sigue al reproductor. */
    final Runnable pulsePlayingTick = new Runnable() {
        @Override
        public void run() {
            if (!isSourceSelectorVisible() || pulsePlayingVariant.isEmpty()) return;
            for (PulseBar item : pulseBars) {
                if (!item.variant.equals(pulsePlayingVariant)) continue;
                item.quality = pulseQualityOf(item.variant);
                if (item.focused) showPulseDetail(item);
            }
            refreshPulseBars();
            mainHandler.postDelayed(this, 1_000L);
        }
    };

    /**
     * Calidad a mostrar: la de la que suena, del reproductor y al momento (si ya midió su tasa);
     * si no, la medida ahora; si no, la diaria de backups.json.
     */
    PublishedDirectBackups.Quality pulseQualityOf(String variant) {
        if (variant.equals(pulsePlayingVariant)) {
            PublishedDirectBackups.Quality playing = playingQuality();
            if (playing != null && playing.kbps > 0) return playing;
        }
        PublishedDirectBackups.Quality live = pulseLive.get(variant);
        return live != null ? live : pulseQuality.get(variant);
    }

    /** Alto y color de todas las barras (el alto es relativo a la de más bitrate). */
    void refreshPulseBars() {
        int maxKbps = 1;
        for (PulseBar item : pulseBars) {
            PublishedDirectBackups.Quality quality = item.quality;
            if (quality != null && quality.ok) maxKbps = Math.max(maxKbps, quality.kbps);
        }
        // La barra más alta deja lugar al número que va debajo de la base: si no, la fila la
        // desborda (2026-11-14).
        int maxBar = dp(providerMode ? PROVIDER_PULSE_MAX_DP : PULSE_MAX_DP);
        if (!pulseBars.isEmpty()) {
            PulseBar first = pulseBars.get(0);
            View box = (View) first.bar.getParent();
            View row = box == null ? null : (View) box.getParent();
            int room = row == null ? 0 : row.getHeight() - first.tag.getHeight() - dp(4);
            if (row != null && row.getHeight() > 0 && first.tag.getHeight() > 0 && room >= dp(12)) {
                maxBar = Math.min(maxBar, room);
            }
        }
        for (PulseBar item : pulseBars) {
            PublishedDirectBackups.Quality quality = item.quality;
            boolean measured = quality != null;
            boolean failed = measured && !quality.ok;
            int height = !measured ? dp(10)
                    : failed ? dp(2)
                    : Math.max(dp(4), Math.round(maxBar * quality.kbps / (float) maxKbps));
            ViewGroup.LayoutParams params = item.bar.getLayoutParams();
            if (params.height != height) {
                params.height = height;
                item.bar.setLayoutParams(params);
            }
            item.shape.setColor(item.focused ? 0xFFFFFFFF : item.inUse ? 0xFF5BE9A0
                    : failed ? 0x24FFFFFF : !measured ? 0x40FFFFFF : 0x57FFFFFF);
            item.tag.setVisibility(item.focused ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Lanza la medición en vivo de las señales directas que aún no se midieron ahora. */
    void startPulseLiveMeasure(String channelKey) {
        if (!channelKey.equals(pulseLiveChannel)) {
            cancelPulseLiveMeasure();
            pulseLive.clear();
            pulseLiveChannel = channelKey;
        }
        final int session = pulseSession;
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            if (!isDirectSignal(candidate) || candidate.getSource() == null) continue;
            String variant = candidate.getVariantId();
            if (pulseLive.containsKey(variant) || pulsePending.contains(variant)) continue;
            URI uri = candidate.getSource().getPlaybackUri();
            String scheme = uri == null ? "" : String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) continue;
            // La que suena no se vuelve a bajar: una segunda conexión al mismo servidor suele
            // cortarse («unexpected end of stream»). Usa lo que mide el reproductor (OSD), al día.
            if (variant.equals(pulsePlayingVariant) || isCurrentSource(candidate.getSource())) continue;
            Map<String, String> headers = new LinkedHashMap<>(candidate.getSource().getRequestHeaders());
            // Conexión propia, sin reutilizar una que el servidor ya cerró.
            headers.put("Connection", "close");
            pulsePending.add(variant);
            pulseJobs.add(pulseExecutor.submit(() -> {
                PublishedDirectBackups.Quality measured;
                try {
                    HlsStreamValidator.LiveSample sample;
                    try {
                        sample = new HlsStreamValidator(new TokenHttpClient(4_000, 10_000))
                                .measureLive(uri, headers, new ResolutionContext(15_000L));
                    } catch (java.net.ProtocolException closed) {
                        // Algunos servidores cortan la primera conexión: un reintento.
                        sample = new HlsStreamValidator(new TokenHttpClient(4_000, 10_000))
                                .measureLive(uri, headers, new ResolutionContext(15_000L));
                    }
                    int height = sample.info == null ? 0 : sample.info.height;
                    int fps = sample.info == null ? 0 : sample.info.fps;
                    // Sin SPS legible (otro códec): abrió igual, se muestra con su bitrate.
                    measured = new PublishedDirectBackups.Quality(height,
                            (int) Math.min(Integer.MAX_VALUE, sample.kbps), fps, true, true,
                            sample.info != null && sample.info.interlaced,
                            sample.info != null && "h264".equals(sample.info.codec));
                } catch (Exception failed) {
                    Log.i("VibeM3U-Pulse", variant + " sin medición: " + failed.getClass().getSimpleName()
                            + " " + SafePlaybackText.detail(String.valueOf(failed.getMessage())));
                    measured = new PublishedDirectBackups.Quality(0, 0, 0f, false, true);
                }
                final PublishedDirectBackups.Quality result = measured;
                mainHandler.post(() -> {
                    if (session != pulseSession) return;
                    pulsePending.remove(variant);
                    pulseLive.put(variant, result);
                    for (PulseBar item : pulseBars) {
                        if (!item.variant.equals(variant)) continue;
                        item.quality = result;
                        refreshPulseBars();
                        if (item.focused) showPulseDetail(item);
                    }
                });
            }));
        }
        mainHandler.removeCallbacks(pulsePlayingTick);
        if (!pulsePlayingVariant.isEmpty()) mainHandler.postDelayed(pulsePlayingTick, 1_000L);
    }

    /** Calidad de la señal que suena, medida por el reproductor (lo mismo que muestra el OSD). */
    PublishedDirectBackups.Quality playingQuality() {
        Format video = player == null ? null : player.getVideoFormat();
        if (video == null || video.height <= 0) return null;
        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
        long bitrate = measurements.muxedStream ? measurements.streamBitrate : measurements.videoBitrate;
        return new PublishedDirectBackups.Quality(video.height, (int) Math.max(0L, bitrate / 1000L),
                shownFrameRate(measurements), true, true, playingInterlaced(), playingScanKnown());
    }

    void cancelPulseLiveMeasure() {
        pulseSession++;
        mainHandler.removeCallbacks(pulsePlayingTick);
        for (Future<?> job : pulseJobs) job.cancel(true);
        pulseJobs.clear();
        pulsePending.clear();
    }

    /**
     * Máximo de barras a la vista (0.6.55, pedido del usuario con foto de 16 respaldos: «se solapa…
     * haz que el selector de señales tenga un máximo y haga scroll lateral»). Con más, la fila se
     * desplaza sola al mover el foco y los bordes se esfuman para indicar que hay más.
     */
    static final int PULSE_MAX_VISIBLE = 10;

    int pulseScrollerWidth(int bars) {
        int visible = Math.max(1, Math.min(bars, PULSE_MAX_VISIBLE));
        // Principal 15 dp; cada respaldo 10 dp + 3 dp de separación.
        return dp(15 + (visible - 1) * 13 + 2);
    }

    View pulseScroller(LinearLayout row, int bars) {
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        scroller.setFocusable(false);
        scroller.setClipToPadding(false);
        if (bars > PULSE_MAX_VISIBLE) {
            scroller.setHorizontalFadingEdgeEnabled(true);
            scroller.setFadingEdgeLength(dp(14));
        }
        scroller.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return scroller;
    }

    /** Una barra y, debajo de su base, su número (solo con foco). */
    View pulseBar(int position, String variant, boolean inUse) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        TextView tag = new TextView(this);
        tag.setText(pulseNumber(position));
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        tag.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tag.setTextColor(0xFFFFFFFF);
        tag.setFontFeatureSettings("tnum");
        tag.setIncludeFontPadding(false);
        tag.setSingleLine(true);
        tag.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
        tag.setVisibility(View.INVISIBLE);
        View bar = new View(this);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(1));
        bar.setBackground(shape);
        box.addView(bar, new LinearLayout.LayoutParams(
                dp(position == 0 ? PULSE_PRINCIPAL_BAR_DP : PULSE_BAR_DP), dp(10)));
        // Número debajo de la base (2026-11-14, foto del usuario de ESPN 2 096: arriba de la
        // barra más alta quedaba fuera de la fila y no se veía; «Aplica la propuesta»). Ocupa
        // su lugar aunque esté oculto, así todas las barras comparten la misma base.
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagParams.topMargin = dp(4);
        box.addView(tag, tagParams);
        if (inUse) pulsePlayingVariant = variant;
        PulseBar item = new PulseBar(position, variant, inUse, bar, tag, shape);
        item.quality = pulseQualityOf(variant);
        pulseBars.add(item);
        box.setTag(R.id.source_selector_options, (View.OnFocusChangeListener) (view, focused) -> {
            item.focused = focused;
            refreshPulseBars();
            // Animaciones B: la barra marcada se ensancha (solo a lo ancho: su alto es el bitrate y
            // su base debe seguir alineada con las demás).
            bar.setPivotX(bar.getWidth() / 2f);
            bar.animate().scaleX(focused ? 1.3f : 1f)
                    .setDuration(Motion.SCALE_MS).setInterpolator(Motion.SPRING).start();
            if (focused) showPulseDetail(item);
        });
        return box;
    }

    /** El detalle a la derecha de las barras: «RESPALDO 09 DE 21», calidad y estado. */
    LinearLayout pulseSide() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        pulseEyebrow = new TextView(this);
        pulseEyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        pulseEyebrow.setLetterSpacing(0.24f);
        pulseEyebrow.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        pulseEyebrow.setTextColor(0x73FFFFFF);
        pulseEyebrow.setIncludeFontPadding(false);
        pulseQualityLine = new TextView(this);
        pulseQualityLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        pulseQualityLine.setTextColor(0xFFFFFFFF);
        pulseQualityLine.setFontFeatureSettings("tnum");
        pulseQualityLine.setIncludeFontPadding(false);
        pulseQualityLine.setSingleLine(true);
        pulseQualityLine.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
        pulseStatus = new TextView(this);
        pulseStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        pulseStatus.setTextColor(0x80FFFFFF);
        pulseStatus.setIncludeFontPadding(false);
        pulseStatus.setSingleLine(true);
        side.addView(pulseEyebrow);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lineParams.topMargin = dp(5);
        side.addView(pulseQualityLine, lineParams);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(5);
        side.addView(pulseStatus, statusParams);
        return side;
    }

    /** Llena el detalle con la señal marcada: «RESPALDO 09 DE 21», su calidad y su estado. */
    void showPulseDetail(PulseBar item) {
        if (pulseEyebrow == null || item == null) return;
        int position = item.position;
        PublishedDirectBackups.Quality quality = item.quality;
        boolean pending = pulsePending.contains(item.variant);
        pulseEyebrow.setText(position == 0 ? getString(R.string.source_selector_pulse_principal)
                : getString(R.string.source_selector_pulse_backup, pulseNumber(position), pulseTotal));
        android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
        if (quality != null && quality.ok) {
            if (quality.height > 0) {
                line.append(scanLabel(quality.height, quality.interlaced));
                line.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            List<String> parts = new ArrayList<>();
            if (quality.kbps > 0) parts.add(formatBitrate(quality.kbps * 1000L));
            if (quality.fps > 0) parts.add(fpsText(quality.fps, quality.interlaced) + " fps");
            if (!parts.isEmpty()) {
                int start = line.length();
                line.append(start > 0 ? "  " : "").append(TextUtils.join(" · ", parts));
                line.setSpan(new ForegroundColorSpan(0xB3FFFFFF), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                line.setSpan(new android.text.style.RelativeSizeSpan(0.87f), start, line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (quality.scanKnown) appendScanChip(line, quality.interlaced);
        } else {
            line.append(getString(quality == null
                    ? (pending ? R.string.source_selector_pulse_measuring : R.string.source_selector_pulse_unmeasured)
                    : quality.live ? R.string.source_selector_pulse_no_signal_now
                    : R.string.source_selector_pulse_no_signal));
        }
        pulseQualityLine.setText(line);
        android.text.SpannableStringBuilder status = new android.text.SpannableStringBuilder();
        if (quality != null && quality.ok) {
            status.append("● ");
            status.setSpan(new ForegroundColorSpan(0xFF5BE9A0), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            status.append(getString(quality.live ? R.string.source_selector_pulse_responds_now
                    : R.string.source_selector_pulse_responded_daily)).append("  ·  ");
        } else if (pending && quality != null) {
            status.append(getString(R.string.source_selector_pulse_measuring)).append("  ·  ");
        }
        status.append(pulseInUse < 0 ? getString(R.string.source_selector_pulse_hint)
                : getString(R.string.source_selector_pulse_in_use, pulseInUse == 0
                        ? getString(R.string.source_selector_pulse_principal_short) : pulseNumber(pulseInUse)));
        pulseStatus.setText(status);
        if (pulseCompact != null) {
            android.text.SpannableStringBuilder compact = new android.text.SpannableStringBuilder(position == 0
                    ? getString(R.string.source_selector_compact_principal)
                    : getString(R.string.source_selector_compact_backup, pulseNumber(position)));
            compact.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, compact.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            compact.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, compact.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (position > 0) compact.append(getString(R.string.source_selector_compact_of, pulseTotal));
            // Sin la etiqueta de barrido: ya va junto a la calidad grande y no cabe en una línea.
            String plain = line.toString()
                    .replace(getString(R.string.source_selector_interlaced), "")
                    .replace(getString(R.string.source_selector_progressive), "")
                    .trim().replace("  ", " · ");
            compact.append(" · ").append(plain);
            pulseCompact.setText(compact);
        }
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
                minimalFocusActions = group == MINIMAL_GROUP_ACTIONS;
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

    /**
     * Con el foco en las acciones, los repintados lo mantienen allí.
     */
    boolean minimalFocusActions;
    /**
     * Al abrir, el foco empieza en la señal (0.6.58, pedido del usuario: «el foco al principio esté
     * en la calidad de señal»; antes, en las acciones) y sigue allí hasta que se aprieta una flecha.
     */
    boolean minimalFocusOpening;

    /** Primera acción («Cambio automático» u «Ocultar en esta TV»), o -1 si no hay. */
    int firstMinimalAction() {
        return minimalFocusActions ? minimalItemGroup.indexOf(MINIMAL_GROUP_ACTIONS) : -1;
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
        if (frameRate > 0) parts.add(fpsText(frameRate, playingInterlaced()) + " FPS");
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
        return video != null && video.height > 0 ? scanLabel(video.height, playingInterlaced()) : "";
    }

    /**
     * Barrido (0.6.56, mockups «D · 59.9 + INTERLACED» y «E · PROGRESSIVE» aprobados, y «que
     * ahí salgan solo los fps» en la línea técnica): la calidad sigue «1080p»; una señal
     * entrelazada muestra sus campos por segundo (29,97 → «59.9») y una etiqueta con recuadro
     * «INTERLACED» o «PROGRESSIVE» junto a la calidad grande y en el detalle de cada respaldo.
     */
    static String scanLabel(int height, boolean interlaced) {
        return height + "p";
    }

    /** «59.9» para 29,97 entrelazado (campos), «50» para 25; si no, los cuadros reales. */
    static String fpsText(float fps, boolean interlaced) {
        if (!interlaced || fps <= 0) return trimDecimal(fps);
        if (Math.abs(fps - 30f) < 1.2f) return "59.9";
        return trimDecimal(Math.round(fps * 2f * 10f) / 10f);
    }

    /** Etiqueta con recuadro al final de la línea: « INTERLACED » o « PROGRESSIVE ». */
    void appendScanChip(android.text.SpannableStringBuilder line, boolean interlaced) {
        line.append("  ");
        int start = line.length();
        line.append(getString(interlaced ? R.string.source_selector_interlaced : R.string.source_selector_progressive));
        line.setSpan(new ScanChipSpan(dp(1.2f), dp(5), dp(2.5f), 0.55f), start, line.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Texto en mayúsculas, chico y en negrita, dentro de un recuadro de borde fino. */
    static final class ScanChipSpan extends android.text.style.ReplacementSpan {
        final float stroke;
        final float padX;
        final float radius;
        final float scale;

        ScanChipSpan(float stroke, float padX, float radius, float scale) {
            this.stroke = stroke;
            this.padX = padX;
            this.radius = radius;
            this.scale = scale;
        }

        private android.graphics.Paint chipPaint(android.graphics.Paint base) {
            android.graphics.Paint paint = new android.graphics.Paint(base);
            paint.setTextSize(base.getTextSize() * scale);
            paint.setFakeBoldText(true);
            paint.setLetterSpacing(0.1f);
            return paint;
        }

        @Override
        public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end,
                           android.graphics.Paint.FontMetricsInt fm) {
            return Math.round(chipPaint(paint).measureText(text, start, end) + padX * 2 + stroke * 2);
        }

        @Override
        public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, android.graphics.Paint paint) {
            android.graphics.Paint chip = chipPaint(paint);
            float width = chip.measureText(text, start, end) + padX * 2;
            android.graphics.Paint.FontMetrics metrics = chip.getFontMetrics();
            float textHeight = metrics.descent - metrics.ascent;
            float center = y - paint.getTextSize() * 0.35f;
            float boxTop = center - textHeight / 2f - stroke * 2;
            float boxBottom = center + textHeight / 2f + stroke * 2;
            android.graphics.Paint border = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            border.setStyle(android.graphics.Paint.Style.STROKE);
            border.setStrokeWidth(stroke);
            border.setColor(0xBFFFFFFF);
            canvas.drawRoundRect(x + stroke / 2f, boxTop, x + stroke / 2f + width + stroke, boxBottom,
                    radius, radius, border);
            chip.setColor(0xFFFFFFFF);
            canvas.drawText(text, start, end, x + stroke + padX, center - (metrics.ascent + metrics.descent) / 2f, chip);
        }
    }

    /** Título de una calidad de la señal que suena («1080p»). */
    String screenTitle(PlaybackOption option) {
        return qualityTitle(option);
    }

    Format interlacedFormat;
    boolean interlacedValue;
    boolean scanKnownValue;

    /** Se pudo leer el SPS H.264 de la que suena (si no, no se muestra la etiqueta). */
    boolean playingScanKnown() {
        playingInterlaced();
        return scanKnownValue;
    }

    /** Lee el SPS H.264 del video que suena (initializationData del Format): entrelazado o no. */
    boolean playingInterlaced() {
        Format video = player == null ? null : player.getVideoFormat();
        if (video == null) return false;
        if (video != interlacedFormat) {
            interlacedFormat = video;
            interlacedValue = false;
            scanKnownValue = false;
            for (byte[] data : video.initializationData) {
                VideoSampleInfo info = VideoSampleInfo.probe(data);
                if (info != null) {
                    interlacedValue = info.interlaced;
                    scanKnownValue = "h264".equals(info.codec);
                    break;
                }
            }
        }
        return interlacedValue;
    }

    /** «2.2 Mbps · 59.9 fps [INTERLACED]»: junto a la calidad grande. */
    CharSequence minimalHeroMeta() {
        List<String> parts = new ArrayList<>();
        PlaybackDiagnosticsWorker.Snapshot measurements = playbackBitrateMeter == null
                ? PlaybackDiagnosticsWorker.Snapshot.EMPTY : playbackBitrateMeter.snapshot();
        long bitrate = measurements.muxedStream ? measurements.streamBitrate : measurements.videoBitrate;
        if (bitrate > 0) parts.add(formatBitrate(bitrate));
        float frameRate = shownFrameRate(measurements);
        if (frameRate > 0) parts.add(fpsText(frameRate, playingInterlaced()) + " fps");
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(TextUtils.join(" · ", parts));
        if (playingScanKnown()) appendScanChip(text, playingInterlaced());
        return text;
    }

    /** «036 · ESPN 2»: se antepone la sección en la etiqueta («CALIDAD · 036 · ESPN 2»). */
    String minimalChannelLabel = "";

    void setMinimalEyebrow(String section) {
        sourceSelectorChannel.setText(AppStrings.isBlank(minimalChannelLabel)
                ? section : section + " · " + minimalChannelLabel);
    }

    /** Texto junto a la calidad grande; con señal en vivo, la etiqueta lleva el punto verde. */
    void setMinimalStatus(CharSequence text, boolean live) {
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
        if (versions && hasSources) {
            providerMode = true;
            renderMinimalScreens(false, sourceNames.size());
            return;
        }
        String sourceLabel = getString(versions ? R.string.source_selector_section_versions
                : R.string.source_selector_section_signal);
        // Pulso: solo señales directas (principal + respaldos), no versiones de un proveedor.
        boolean pulse = !versions && sourceNames.size() >= 2;
        pulseBars.clear();
        pulseInUse = -1;
        pulseTotal = sourceNames.size() - 1;
        int sourceIndex = 0;
        for (PlaybackOption option : playbackOptions) {
            if (option.sourceCandidate == null) continue;
            pulse &= isDirectSignal(option.sourceCandidate);
            if (option.selected) pulseInUse = sourceIndex;
            sourceIndex++;
        }
        boolean sourcesInLadder = !hasQualities && !pulse;
        List<String> shortNames = versions ? shortVersionNames(sourceNames) : sourceNames;
        if (hasQualities && (!hasSources || pulse)) {
            renderMinimalScreens(pulse, sourceNames.size());
            return;
        }
        minimalStage(hasSources && !sourcesInLadder ? sourceLabel : "");
        LinearLayout pulseRow = null;
        if (pulse) {
            stageInline.setGravity(Gravity.BOTTOM);
            pulseRow = new LinearLayout(this);
            // Base común: sin esto la fila alinea las barras por la línea de texto de su número.
            pulseRow.setBaselineAligned(false);
            pulseRow.setOrientation(LinearLayout.HORIZONTAL);
            pulseRow.setGravity(Gravity.BOTTOM);
            // Ya medida la fila, las barras se ajustan a su alto (ver refreshPulseBars).
            pulseRow.post(this::refreshPulseBars);
            View scroller = pulseScroller(pulseRow, sourceNames.size());
            int scrollerWidth = pulseScrollerWidth(sourceNames.size());
            if (sourceNames.size() > PULSE_MAX_VISIBLE) {
                // Pistas a ancho fijo bajo las barras (0.6.60): ‹ › en los bordes y la barrita.
                LinearLayout column = new LinearLayout(this);
                column.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                        scrollerWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
                scrollerParams.setMarginStart(dp(PULSE_ARROW_GUTTER_DP));
                scrollerParams.bottomMargin = dp(5);
                column.addView(scroller, scrollerParams);
                column.addView(pulseIndicator((android.widget.HorizontalScrollView) scroller, scrollerWidth),
                        new LinearLayout.LayoutParams(scrollerWidth + 2 * dp(PULSE_ARROW_GUTTER_DP), dp(9)));
                stageInline.addView(column, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            } else {
                stageInline.addView(scroller, new LinearLayout.LayoutParams(
                        scrollerWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        int segment = stageSegmentWidth(sourcesInLadder ? sourceNames.size() : qualityCount);
        int actionsLine = hasSources && hasQualities ? 2 : 1;
        String inUseSource = "";
        int sourcePosition = 0;
        for (int index = 0; index < playbackOptions.size(); index++) {
            final int optionIndex = index;
            PlaybackOption option = playbackOptions.get(index);
            View view;
            boolean ladder;
            if (option.sourceCandidate != null && pulse) {
                final int position = sourcePosition;
                if (option.selected) inUseSource = shortNames.get(sourcePosition);
                sourcePosition++;
                view = pulseBar(position, option.sourceCandidate.getVariantId(), option.selected);
                LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                        dp(position == 0 ? 15 : 10), LinearLayout.LayoutParams.WRAP_CONTENT);
                if (position > 0) barParams.setMarginStart(dp(3));
                pulseRow.addView(view, barParams);
                registerMinimalItem(view, MINIMAL_GROUP_OPTIONS, () -> selectPlaybackOption(optionIndex),
                        hasQualities ? 1 : 0);
                continue;
            }
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
        if (pulse) {
            LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sideParams.setMarginStart(dp(22));
            stageInline.addView(pulseSide(), sideParams);
            refreshPulseBars();
            showPulseDetail(pulseBars.get(Math.min(Math.max(0, pulseInUse), pulseBars.size() - 1)));
            Channel pulseChannel = playbackChannel;
            startPulseLiveMeasure(pulseChannel == null ? "" : PlaybackPreferences.channelIdentity(pulseChannel));
        } else {
            pulseEyebrow = null;
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
        int action = firstMinimalAction();
        if (!minimalFocusOpening && action >= 0) focusIndex = action;
        if (!sourceCandidateViews.isEmpty()) {
            sourceCandidateFocusIndex = Math.min(focusIndex, sourceCandidateViews.size() - 1);
            sourceCandidateViews.get(sourceCandidateFocusIndex).requestFocus();
        }
    }

    // Pantallas (0.6.54, mockup «E2 · Resoluciones como pantallas», pedido del usuario: «aplica el
    // E2, me encantó»). Las calidades de la señal que suena son contornos de pantalla anidados,
    // del tamaño de su resolución; las otras señales quedan aparte en barras (Mbps). No se
    // mezclan: cada grupo con su rótulo. ▲▼ recorre las pantallas (y «Auto»); ◀▶ pasa de grupo.
    static final float SCREENS_WIDTH_DP = 130f;
    boolean screensMode;
    /** Índices (en sourceCandidateViews) de las calidades, de la más grande a «Auto». */
    final List<Integer> screensQualities = new ArrayList<>();
    /** Índices de las barras visibles de las otras señales, de izquierda a derecha. */
    final List<Integer> screensSignals = new ArrayList<>();

    void renderMinimalScreens(boolean pulse, int sourceCount) {
        minimalStage("");
        screensMode = true;
        stageBottom.setVisibility(View.GONE);
        pulseBars.clear();
        pulseInUse = -1;
        // Proveedores (0.6.60, mockup «B3» aprobado): las señales directas siguen en barras y las
        // versiones de TvVoo van aparte, en texto (calidad · fps · país), sin bitrate.
        int directCount = 0;
        int versionCount = 0;
        int versionInUse = -1;
        for (PlaybackOption option : playbackOptions) {
            if (option.sourceCandidate == null) continue;
            if (!providerMode || isDirectSignal(option.sourceCandidate)) {
                if (option.selected) pulseInUse = directCount;
                directCount++;
            } else {
                if (option.selected) versionInUse = versionCount;
                versionCount++;
            }
        }
        pulseTotal = directCount - 1;
        boolean bars = providerMode ? directCount > 0 : pulse;
        Format video = player == null ? null : player.getVideoFormat();
        int playingHeight = video == null ? 0 : video.height;
        String playingLabel = playingHeight > 0 ? scanLabel(playingHeight, playingInterlaced()) : "";
        boolean automatic = false;
        int maxHeight = 1;
        for (PlaybackOption option : playbackOptions) {
            if (option.automatic && option.selected) automatic = true;
            if (option.quality != null) maxHeight = Math.max(maxHeight, option.quality.height);
        }
        // En uso: la fija elegida o, con «Auto», la de alto más cercano al que suena.
        int inUseQuality = -1;
        int bestGap = Integer.MAX_VALUE;
        for (int index = 0; index < playbackOptions.size(); index++) {
            PlaybackOption option = playbackOptions.get(index);
            if (option.quality == null) continue;
            if (!automatic && option.selected) {
                inUseQuality = index;
                break;
            }
            int gap = Math.abs(option.quality.height - playingHeight);
            if (automatic && playingHeight > 0 && gap < bestGap) {
                bestGap = gap;
                inUseQuality = index;
            }
        }

        // Izquierda: rótulo «EN USO · PRINCIPAL» y las pantallas, con «Auto» debajo.
        LinearLayout inUseGroup = new LinearLayout(this);
        inUseGroup.setOrientation(LinearLayout.VERTICAL);
        String owner = versionInUse >= 0 ? getString(R.string.source_selector_provider_tvvoo_owner)
                : (providerMode ? directCount : sourceCount) == 0 ? "" : pulseInUse <= 0
                ? getString(R.string.source_selector_pulse_principal)
                : getString(R.string.source_selector_screens_backup, pulseNumber(pulseInUse));
        TextView inUseLabel = minimalSectionLabel(AppStrings.isBlank(owner)
                ? getString(R.string.source_selector_screens_in_use)
                : getString(R.string.source_selector_screens_in_use_of, owner));
        inUseLabel.setTextColor(0xFF5BE9A0);
        inUseGroup.addView(inUseLabel, screensLabelParams());
        int boxWidth = dp(SCREENS_WIDTH_DP);
        int boxHeight = Math.round(boxWidth * 9f / 16f);
        FrameLayout screens = new FrameLayout(this);
        inUseGroup.addView(screens, new LinearLayout.LayoutParams(boxWidth, boxHeight));

        // De la más grande a la más chica: la chica queda encima.
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < playbackOptions.size(); index++) {
            if (playbackOptions.get(index).quality != null) order.add(index);
        }
        java.util.Collections.sort(order, (a, b) -> Integer.compare(
                playbackOptions.get(b).quality.height, playbackOptions.get(a).quality.height));
        Map<Integer, View> views = new HashMap<>();
        int previousWidth = Integer.MAX_VALUE;
        int previousHeight = -1;
        int[] widths = new int[order.size()];
        int[] heights = new int[order.size()];
        for (int position = 0; position < order.size(); position++) {
            VideoTrackOption quality = playbackOptions.get(order.get(position)).quality;
            int width = Math.round(boxWidth * Math.min(1f, quality.height / (float) maxHeight));
            // Dos pistas del mismo alto: la segunda, un poco más chica, para que se vea.
            if (quality.height == previousHeight || width >= previousWidth) width = previousWidth - dp(8);
            width = Math.max(dp(28), width);
            widths[position] = width;
            heights[position] = Math.round(width * 9f / 16f);
            previousWidth = width;
            previousHeight = quality.height;
        }
        // Etiquetas sin solaparse (0.6.61, foto del usuario de Al Jazeera: «no queda bien, se solapa»;
        // mockup aprobado): el número de una pantalla se escribe solo si ningún borde de una más
        // chica lo cruza. Si no, aparece al marcarla, sobre un fondo oscuro.
        android.graphics.Paint labelPaint = new android.graphics.Paint();
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f,
                getResources().getDisplayMetrics()));
        labelPaint.setFakeBoldText(true);
        // Caja de cada número (arriba a la derecha de su pantalla).
        int[] labelTops = new int[order.size()];
        int[] labelBottoms = new int[order.size()];
        int[] labelLefts = new int[order.size()];
        int[] labelRights = new int[order.size()];
        for (int position = 0; position < order.size(); position++) {
            String title = screenTitle(playbackOptions.get(order.get(position)));
            labelTops[position] = boxHeight - heights[position] + dp(3);
            labelBottoms[position] = labelTops[position] + Math.round(labelPaint.getTextSize() * 1.2f);
            labelRights[position] = widths[position] - dp(4);
            labelLefts[position] = labelRights[position] - Math.round(labelPaint.measureText(title));
        }
        // El de la que suena siempre se ve; otro número tampoco se escribe si choca con uno visible
        // (Mega con dos pistas 1080: «1080p» salía dos veces, una sobre otra).
        List<Integer> shown = new ArrayList<>();
        int inUsePosition = order.indexOf(inUseQuality);
        if (inUsePosition >= 0) shown.add(inUsePosition);
        for (int position = 0; position < order.size(); position++) {
            int index = order.get(position);
            String title = screenTitle(playbackOptions.get(index));
            boolean crowded = false;
            for (int smaller = position + 1; smaller < order.size() && !crowded; smaller++) {
                int border = boxHeight - heights[smaller];
                crowded = border >= labelTops[position] - dp(1) && border <= labelBottoms[position] + dp(1)
                        && widths[smaller] > labelLefts[position];
            }
            if (position != inUsePosition) {
                for (int other : shown) {
                    crowded |= labelLefts[position] < labelRights[other] + dp(2)
                            && labelLefts[other] < labelRights[position] + dp(2)
                            && labelTops[position] < labelBottoms[other] + dp(1)
                            && labelTops[other] < labelBottoms[position] + dp(1);
                }
                if (!crowded) shown.add(position);
            }
            View screen = screenOutline(title, index == inUseQuality, crowded);
            screens.addView(screen, new FrameLayout.LayoutParams(widths[position], heights[position],
                    Gravity.BOTTOM | Gravity.START));
            views.put(index, screen);
        }
        if (order.isEmpty()) {
            // Una sola calidad (proveedores): la pantalla de lo que suena, solo informativa.
            View screen = screenOutline(playingLabel, true, false);
            screen.setFocusable(false);
            screens.addView(screen, new FrameLayout.LayoutParams(boxWidth, boxHeight, Gravity.BOTTOM | Gravity.START));
        }
        View auto = null;
        int autoIndex = -1;
        for (int index = 0; index < playbackOptions.size(); index++) {
            if (!playbackOptions.get(index).automatic) continue;
            autoIndex = index;
            String title = automatic && !AppStrings.isBlank(playingLabel)
                    ? getString(R.string.source_selector_screens_auto_playing, playingLabel)
                    : getString(R.string.source_selector_screens_auto);
            auto = minimalOption(title, false, false, false, TYPE_INLINE_SP);
            if (automatic) {
                TextView autoTitle = (TextView) ((LinearLayout) ((LinearLayout) auto).getChildAt(0)).getChildAt(0);
                autoTitle.setTextColor(0xFF5BE9A0);
                autoTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                View.OnFocusChangeListener base = (View.OnFocusChangeListener) auto.getTag(R.id.source_selector_options);
                auto.setTag(R.id.source_selector_options, (View.OnFocusChangeListener) (view, focused) -> {
                    base.onFocusChange(view, focused);
                    autoTitle.setTextColor(focused ? 0xFFFFFFFF : 0xFF5BE9A0);
                    autoTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                });
            }
            LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            autoParams.topMargin = dp(7);
            inUseGroup.addView(auto, autoParams);
        }
        stageLadder.setGravity(Gravity.BOTTOM);
        stageLadder.addView(inUseGroup, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Centro: las señales directas en barras. Con más de 10 se desplazan y debajo van las
        // pistas (‹ › y la barrita de posición), a ancho fijo.
        LinearLayout pulseRow = null;
        int slot = 0;
        if (bars) {
            LinearLayout signals = new LinearLayout(this);
            signals.setOrientation(LinearLayout.VERTICAL);
            TextView signalsLabel = minimalSectionLabel(providerMode ? directProviderLabel()
                    : getString(R.string.source_selector_screens_signals));
            if (providerMode && pulseInUse >= 0) {
                android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setColor(0xFF5BE9A0);
                dot.setSize(dp(4.5f), dp(4.5f));
                signalsLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, dot, null);
                signalsLabel.setCompoundDrawablePadding(dp(7));
            }
            signals.addView(signalsLabel, screensLabelParams());
            pulseRow = new LinearLayout(this);
            // Base común: sin esto la fila alinea las barras por la línea de texto de su número.
            pulseRow.setBaselineAligned(false);
            pulseRow.setOrientation(LinearLayout.HORIZONTAL);
            pulseRow.setGravity(Gravity.BOTTOM);
            boolean scrolls = directCount > PULSE_MAX_VISIBLE;
            boolean spread = providerMode && scrolls;
            int scrollerWidth = spread ? dp(PROVIDER_TRACK_DP) : pulseScrollerWidth(directCount);
            if (spread) slot = Math.round(scrollerWidth / (float) PULSE_MAX_VISIBLE);
            // Ya medida la fila, las barras se ajustan a su alto (ver refreshPulseBars).
            pulseRow.post(this::refreshPulseBars);
            View scroller = pulseScroller(pulseRow, directCount);
            // Rótulo a la altura de «EN USO» y barras apoyadas en la base de las pantallas.
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(scrollerWidth, 0, 1f);
            if (scrolls) rowParams.setMarginStart(dp(PULSE_ARROW_GUTTER_DP));
            // 2026-11-14 («ajusta la posición a la altura de los demás»): sin margen, la base de las
            // barras con su número queda a la altura del borde inferior del cuadro «En uso».
            rowParams.bottomMargin = scrolls ? dp(5) : providerMode ? dp(8) : 0;
            signals.addView(scroller, rowParams);
            if (scrolls) {
                LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(
                        scrollerWidth + 2 * dp(PULSE_ARROW_GUTTER_DP), dp(9));
                indicatorParams.bottomMargin = providerMode ? dp(5) : 0;
                signals.addView(pulseIndicator((android.widget.HorizontalScrollView) scroller, scrollerWidth),
                        indicatorParams);
            }
            if (providerMode) {
                pulseCompact = new TextView(this);
                pulseCompact.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
                pulseCompact.setTextColor(0xBFFFFFFF);
                pulseCompact.setFontFeatureSettings("tnum");
                pulseCompact.setIncludeFontPadding(false);
                pulseCompact.setSingleLine(true);
                pulseCompact.setEllipsize(TextUtils.TruncateAt.END);
                pulseCompact.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
                signals.addView(pulseCompact, new LinearLayout.LayoutParams(
                        dp(PROVIDER_COLUMN_DP), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams signalsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
            signalsParams.setMarginStart(dp(30));
            stageLadder.addView(signals, signalsParams);
        }

        // Derecha (proveedores): las versiones de TvVoo, tres a la vista; con más, se desplazan y
        // la línea divisoria y las flechas ⌃ ⌄ indican que hay más.
        LinearLayout versionRows = null;
        versionTotal = versionCount;
        if (providerMode && (versionCount > 0 || providerPending)) {
            boolean scrolls = versionCount > PROVIDER_VERSIONS_VISIBLE;
            if (bars || scrolls) {
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        dp(4), LinearLayout.LayoutParams.MATCH_PARENT);
                dividerParams.setMarginStart(dp(bars ? 22 : 30));
                stageLadder.addView(versionDivider(scrolls), dividerParams);
            }
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            versionHeader = minimalSectionLabel("");
            column.addView(versionHeader, screensLabelParams());
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.HORIZONTAL);
            versionScroll = new android.widget.ScrollView(this);
            versionScroll.setVerticalScrollBarEnabled(false);
            versionScroll.setFocusable(false);
            versionScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            versionScroll.setVerticalFadingEdgeEnabled(scrolls);
            versionScroll.setFadingEdgeLength(dp(12));
            versionRows = new LinearLayout(this);
            versionRows.setOrientation(LinearLayout.VERTICAL);
            versionScroll.addView(versionRows, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            int visible = Math.max(1, Math.min(versionCount, PROVIDER_VERSIONS_VISIBLE));
            // Ancho fijo: las flechas quedan siempre en el mismo lugar y ninguna fila se corta.
            body.addView(versionScroll, new LinearLayout.LayoutParams(
                    dp(PROVIDER_VERSIONS_WIDTH_DP), dp(PROVIDER_VERSION_ROW_DP * visible)));
            if (scrolls) {
                FrameLayout arrows = new FrameLayout(this);
                versionUp = new ChevronView(this, ChevronView.UP, dp(1.3f));
                versionDown = new ChevronView(this, ChevronView.DOWN, dp(1.3f));
                arrows.addView(versionUp, new FrameLayout.LayoutParams(dp(9), dp(5), Gravity.TOP));
                arrows.addView(versionDown, new FrameLayout.LayoutParams(dp(9), dp(5), Gravity.BOTTOM));
                LinearLayout.LayoutParams arrowsParams = new LinearLayout.LayoutParams(
                        dp(9), dp(PROVIDER_VERSION_ROW_DP * visible) - dp(8));
                arrowsParams.setMarginStart(dp(14));
                arrowsParams.topMargin = dp(4);
                body.addView(arrows, arrowsParams);
                versionScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> refreshVersionIndicator());
            }
            column.addView(body);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
            columnParams.setMarginStart(dp(bars || scrolls ? 14 : 30));
            stageLadder.addView(column, columnParams);
        }
        if (!providerMode) {
            LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sideParams.setMarginStart(dp(26));
            stageLadder.addView(pulseSide(), sideParams);
        } else {
            // Detalle sin mostrar: showScreenDetail y showPulseDetail lo siguen llenando.
            pulseSide();
        }

        // Registro en el orden de playbackOptions (selectFocusedSource usa ese índice).
        int sourcePosition = 0;
        int versionPosition = 0;
        for (int index = 0; index < playbackOptions.size(); index++) {
            final int optionIndex = index;
            PlaybackOption option = playbackOptions.get(index);
            View view;
            if (option.sourceCandidate != null && providerMode && !isDirectSignal(option.sourceCandidate)) {
                view = versionRow(option, versionPosition++);
                versionRows.addView(view, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(PROVIDER_VERSION_ROW_DP)));
                screensVersions.add(sourceCandidateViews.size());
            } else if (option.sourceCandidate != null) {
                final int barPosition = sourcePosition++;
                view = pulseBar(barPosition, option.sourceCandidate.getVariantId(), option.selected);
                LinearLayout.LayoutParams barParams;
                if (slot > 0) {
                    barParams = new LinearLayout.LayoutParams(slot, LinearLayout.LayoutParams.WRAP_CONTENT);
                } else {
                    barParams = new LinearLayout.LayoutParams(
                            dp(barPosition == 0 ? 15 : 10), LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (barPosition > 0) barParams.setMarginStart(dp(3));
                }
                pulseRow.addView(view, barParams);
                // La que suena también se ve, en verde (pedido del usuario: «la barra … en uso se vea
                // de otro color para encontrarlo rápidamente… del mismo verde que en uso principal»).
                screensSignals.add(sourceCandidateViews.size());
            } else {
                view = option.automatic ? auto : views.get(index);
                View.OnFocusChangeListener visual = (View.OnFocusChangeListener) view.getTag(R.id.source_selector_options);
                view.setTag(R.id.source_selector_options, (View.OnFocusChangeListener) (v, focused) -> {
                    visual.onFocusChange(v, focused);
                    if (focused) showScreenDetail(option);
                });
            }
            registerMinimalItem(view, MINIMAL_GROUP_OPTIONS, () -> selectPlaybackOption(optionIndex), 0);
        }
        screensQualities.addAll(order);
        if (autoIndex >= 0) screensQualities.add(autoIndex);
        if (bars) {
            refreshPulseBars();
            Channel pulseChannel = playbackChannel;
            startPulseLiveMeasure(pulseChannel == null ? "" : PlaybackPreferences.channelIdentity(pulseChannel));
        }

        String section = (providerMode ? directCount + versionCount : sourceCount) > 0
                ? getString(R.string.source_selector_title)
                : getString(R.string.source_selector_quality_title);
        setMinimalEyebrow(section);
        String hero = minimalHeroQuality();
        sourceSelectorTitle.setText(!AppStrings.isBlank(hero) ? hero : section);
        setMinimalStatus(minimalHeroMeta(), true);
        minimalActions(0);
        // Acciones a la altura de las pantallas; la línea técnica, encima.
        TextView tech = minimalTech();
        if (tech != null) {
            LinearLayout.LayoutParams techParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            techParams.bottomMargin = dp(12);
            stageRight.addView(tech, 0, techParams);
        }
        fitSourceSelectorList(sourceCandidateViews.size());
        if (inUseQuality >= 0) showScreenDetail(playbackOptions.get(inUseQuality));
        else if (autoIndex >= 0) showScreenDetail(playbackOptions.get(autoIndex));
        if (pulseCompact != null && !pulseBars.isEmpty()) {
            showPulseDetail(pulseBars.get(Math.min(Math.max(0, pulseInUse), pulseBars.size() - 1)));
        }
        focusedVersion = -1;
        refreshVersionHeader();
        int focusIndex = minimalFocusOpening ? screensOpeningFocus() : firstMinimalAction();
        if (focusIndex < 0) focusIndex = screensFocusedQuality();
        if (focusIndex < 0) focusIndex = minimalItemGroup.indexOf(MINIMAL_GROUP_ACTIONS);
        if (focusIndex >= 0 && focusIndex < sourceCandidateViews.size()) {
            sourceCandidateFocusIndex = focusIndex;
            sourceCandidateViews.get(focusIndex).requestFocus();
        }
        if (versionScroll != null) versionScroll.post(this::refreshVersionIndicator);
    }

    // ------------------------------------------------------------------------------------
    // Proveedores (0.6.60, mockups «B3» aprobados: «me encanta», «Aplícalo»). Las versiones de
    // TvVoo son la misma señal de otro proveedor: no importa su bitrate ni su nombre, sino su
    // calidad, sus fps y de qué país vienen. Tres a la vista; con más, se desplazan con el foco
    // al centro. Las pistas («hay más que no se ven») van a ancho fijo, en los bordes.

    static final float PROVIDER_COLUMN_DP = 158f;
    static final float PROVIDER_TRACK_DP = 134f;
    static final float PULSE_ARROW_GUTTER_DP = 12f;
    static final int PROVIDER_VERSIONS_VISIBLE = 3;
    static final float PROVIDER_VERSION_ROW_DP = 25f;
    static final float PROVIDER_VERSIONS_WIDTH_DP = 190f;
    /** Alto máximo de las barras con proveedores: deja sitio al número y al detalle de abajo. */
    static final float PROVIDER_PULSE_MAX_DP = 34f;

    /** Selector con versiones de TvVoo: proveedores separados (directas · TvVoo). */
    boolean providerMode;
    /** Mientras TvVoo responde, sus filas dicen «Probando…». */
    boolean providerPending;
    /** Índices (en sourceCandidateViews) de las versiones de TvVoo, de arriba abajo. */
    final List<Integer> screensVersions = new ArrayList<>();
    TextView pulseCompact;
    android.widget.HorizontalScrollView pulseScrollView;
    View pulseThumb;
    ChevronView pulseLeft;
    ChevronView pulseRight;
    int pulseTrackWidth;
    android.widget.ScrollView versionScroll;
    TextView versionHeader;
    View versionThumb;
    ChevronView versionUp;
    ChevronView versionDown;
    int versionTotal;
    int focusedVersion = -1;

    /** Reinicia lo propio de proveedores y pistas al armar el selector de nuevo. */
    void resetProviderStage() {
        providerMode = false;
        screensVersions.clear();
        pulseCompact = null;
        pulseScrollView = null;
        pulseThumb = null;
        pulseLeft = null;
        pulseRight = null;
        versionScroll = null;
        versionHeader = null;
        versionThumb = null;
        versionUp = null;
        versionDown = null;
        versionTotal = 0;
        focusedVersion = -1;
    }

    /** «Lista M3U», o «Highfly» si la señal principal la entrega Highfly (Sky, TNT). */
    String directProviderLabel() {
        StreamResolver resolver = streamResolverRegistry == null || playbackChannel == null
                ? null : streamResolverRegistry.find(playbackChannel);
        return resolver != null && "highfly".equalsIgnoreCase(resolver.getId())
                ? "Highfly" : getString(R.string.source_selector_provider_list);
    }

    /** Nombre del país de una versión TvVoo por su código («uk» → «Reino Unido»). */
    static String countryName(String code) {
        if (code == null || code.isEmpty()) return "";
        switch (code) {
            case "uk": return "Reino Unido";
            case "es": return "España";
            case "pt": return "Portugal";
            case "de": return "Alemania";
            case "fr": return "Francia";
            case "it": return "Italia";
            case "nl": return "Países Bajos";
            case "be": return "Bélgica";
            case "ar": return "Arabia";
            case "tr": return "Turquía";
            case "pl": return "Polonia";
            case "ro": return "Rumania";
            case "gr": return "Grecia";
            case "al": return "Albania";
            case "bg": return "Bulgaria";
            case "ru": return "Rusia";
            case "us": return "Estados Unidos";
            case "mx": return "México";
            case "at": return "Austria";
            case "ch": return "Suiza";
            default: return code.toUpperCase(Locale.ROOT);
        }
    }

    /** Una versión: calidad grande y, al lado, fps y país; «Sin señal» o «Probando…» si aún no. */
    View versionRow(PlaybackOption option, int position) {
        ResolvedPlaybackCandidate candidate = option.sourceCandidate;
        String country = countryName(TvVooStreamResolver.groupCode(candidate.getVariantId()));
        boolean pending = providerPending && !candidate.isAvailable();
        String quality = candidate.getQuality() == null ? "" : candidate.getQuality().trim();
        int split = quality.indexOf(" · ");
        String head = split > 0 ? quality.substring(0, split) : quality;
        String title;
        List<String> details = new ArrayList<>();
        if (pending) {
            title = getString(R.string.source_selector_version_testing);
        } else if (!candidate.isAvailable()) {
            title = getString(R.string.source_selector_version_no_signal);
        } else if (head.matches("\\d+p")) {
            title = head;
            if (split > 0) details.add(quality.substring(split + 3));
        } else {
            title = getString(R.string.source_selector_version_no_data);
        }
        if (!country.isEmpty()) details.add(country);
        View row = minimalOption(inlineTitle(title, TextUtils.join(" · ", details)), option.selected,
                !candidate.isAvailable() && !pending, false, TYPE_OPTION_SP);
        if (pending) row.setAlpha(0.55f);
        View.OnFocusChangeListener base = (View.OnFocusChangeListener) row.getTag(R.id.source_selector_options);
        row.setTag(R.id.source_selector_options, (View.OnFocusChangeListener) (view, focused) -> {
            base.onFocusChange(view, focused);
            if (!focused) return;
            focusedVersion = position;
            refreshVersionHeader();
            centerVersionRow(view);
        });
        return row;
    }

    /** «TVVOO · 3 VERSIONES», o «TVVOO · 4 DE 7» con más de tres y el foco en una. */
    void refreshVersionHeader() {
        if (versionHeader == null) return;
        String text;
        if (versionTotal == 0) {
            text = getString(R.string.source_selector_provider_tvvoo_consulting);
        } else if (versionTotal > PROVIDER_VERSIONS_VISIBLE && focusedVersion >= 0) {
            text = getString(R.string.source_selector_provider_tvvoo_position, focusedVersion + 1, versionTotal);
        } else if (versionTotal == 1) {
            text = getString(R.string.source_selector_provider_tvvoo_one);
        } else {
            text = getString(R.string.source_selector_provider_tvvoo_count, versionTotal);
        }
        versionHeader.setText(text.toUpperCase(Locale.ROOT));
    }

    /** Deja la versión con foco al centro de las tres a la vista. */
    void centerVersionRow(View row) {
        android.widget.ScrollView scroll = versionScroll;
        if (scroll == null || versionTotal <= PROVIDER_VERSIONS_VISIBLE) return;
        scroll.post(() -> {
            int target = row.getTop() - (scroll.getHeight() - row.getHeight()) / 2;
            scroll.smoothScrollTo(0, Math.max(0, target));
        });
    }

    /** Línea divisoria entre proveedores; con más de tres versiones, su tramo claro es la posición. */
    View versionDivider(boolean scrolls) {
        FrameLayout track = new FrameLayout(this);
        View line = new View(this);
        line.setBackgroundColor(0x29FFFFFF);
        track.addView(line, new FrameLayout.LayoutParams(Math.max(1, dp(1)),
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        versionThumb = null;
        if (scrolls) {
            versionThumb = new View(this);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(dp(1));
            shape.setColor(0xCCFFFFFF);
            versionThumb.setBackground(shape);
            versionThumb.setVisibility(View.INVISIBLE);
            track.addView(versionThumb, new FrameLayout.LayoutParams(dp(2), dp(10),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }
        return track;
    }

    /** Tramo claro de la línea y flechas ⌃ ⌄ según lo que queda arriba y abajo. */
    void refreshVersionIndicator() {
        android.widget.ScrollView scroll = versionScroll;
        if (scroll == null || scroll.getChildCount() == 0) return;
        if (versionUp != null) versionUp.setVisibility(scroll.canScrollVertically(-1) ? View.VISIBLE : View.INVISIBLE);
        if (versionDown != null) versionDown.setVisibility(scroll.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE);
        if (versionThumb == null || !(versionThumb.getParent() instanceof View)) return;
        int content = scroll.getChildAt(0).getHeight();
        int visible = scroll.getHeight();
        int track = ((View) versionThumb.getParent()).getHeight();
        if (content <= visible || visible <= 0 || track <= 0) {
            versionThumb.setVisibility(View.INVISIBLE);
            return;
        }
        int height = Math.max(dp(6), Math.round(track * visible / (float) content));
        int top = Math.round((track - height) * scroll.getScrollY() / (float) (content - visible));
        ViewGroup.LayoutParams params = versionThumb.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            versionThumb.setLayoutParams(params);
        }
        versionThumb.setTranslationY(top);
        versionThumb.setVisibility(View.VISIBLE);
    }

    /** Pistas bajo las barras: ‹ en el borde izquierdo, › en el derecho y la barrita entre ellas. */
    View pulseIndicator(android.widget.HorizontalScrollView scroller, int trackWidth) {
        FrameLayout box = new FrameLayout(this);
        int gutter = dp(PULSE_ARROW_GUTTER_DP);
        pulseLeft = new ChevronView(this, ChevronView.LEFT, dp(1.3f));
        pulseRight = new ChevronView(this, ChevronView.RIGHT, dp(1.3f));
        box.addView(pulseLeft, new FrameLayout.LayoutParams(dp(5), dp(9), Gravity.START | Gravity.CENTER_VERTICAL));
        box.addView(pulseRight, new FrameLayout.LayoutParams(dp(5), dp(9), Gravity.END | Gravity.CENTER_VERTICAL));
        View line = new View(this);
        line.setBackgroundColor(0x29FFFFFF);
        FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(trackWidth, Math.max(1, dp(1)),
                Gravity.START | Gravity.CENTER_VERTICAL);
        lineParams.leftMargin = gutter;
        box.addView(line, lineParams);
        pulseThumb = new View(this);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(1));
        shape.setColor(0xCCFFFFFF);
        pulseThumb.setBackground(shape);
        FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(dp(10), dp(2),
                Gravity.START | Gravity.CENTER_VERTICAL);
        thumbParams.leftMargin = gutter;
        box.addView(pulseThumb, thumbParams);
        pulseTrackWidth = trackWidth;
        pulseScrollView = scroller;
        scroller.setHorizontalFadingEdgeEnabled(false);
        scroller.setOnScrollChangeListener((view, x, y, oldX, oldY) -> refreshPulseIndicator());
        scroller.post(this::refreshPulseIndicator);
        return box;
    }

    void refreshPulseIndicator() {
        android.widget.HorizontalScrollView scroller = pulseScrollView;
        if (scroller == null || pulseThumb == null || scroller.getChildCount() == 0) return;
        int content = scroller.getChildAt(0).getWidth();
        int visible = scroller.getWidth();
        if (content <= 0 || visible <= 0) return;
        int width = Math.max(dp(6), Math.round(pulseTrackWidth * Math.min(1f, visible / (float) content)));
        int left = content > visible
                ? Math.round((pulseTrackWidth - width) * scroller.getScrollX() / (float) (content - visible)) : 0;
        ViewGroup.LayoutParams params = pulseThumb.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            pulseThumb.setLayoutParams(params);
        }
        pulseThumb.setTranslationX(left);
        if (pulseLeft != null) pulseLeft.setVisibility(scroller.canScrollHorizontally(-1) ? View.VISIBLE : View.INVISIBLE);
        if (pulseRight != null) pulseRight.setVisibility(scroller.canScrollHorizontally(1) ? View.VISIBLE : View.INVISIBLE);
    }

    /** Flecha fina (‹ › ⌃ ⌄) en blanco tenue. */
    static final class ChevronView extends View {
        static final int UP = 0;
        static final int DOWN = 1;
        static final int LEFT = 2;
        static final int RIGHT = 3;
        private final int direction;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        ChevronView(android.content.Context context, int direction, float stroke) {
            super(context);
            this.direction = direction;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setColor(0x9EFFFFFF);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float inset = paint.getStrokeWidth();
            float right = getWidth() - inset;
            float bottom = getHeight() - inset;
            path.reset();
            switch (direction) {
                case UP:
                    path.moveTo(inset, bottom);
                    path.lineTo(getWidth() / 2f, inset);
                    path.lineTo(right, bottom);
                    break;
                case DOWN:
                    path.moveTo(inset, inset);
                    path.lineTo(getWidth() / 2f, bottom);
                    path.lineTo(right, inset);
                    break;
                case LEFT:
                    path.moveTo(right, inset);
                    path.lineTo(inset, getHeight() / 2f);
                    path.lineTo(right, bottom);
                    break;
                default:
                    path.moveTo(inset, inset);
                    path.lineTo(right, getHeight() / 2f);
                    path.lineTo(inset, bottom);
                    break;
            }
            canvas.drawPath(path, paint);
        }
    }

    LinearLayout.LayoutParams screensLabelParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        return params;
    }

    /** Un contorno de pantalla: verde con relleno si suena, blanco si tiene foco, gris el resto. */
    View screenOutline(String title, boolean inUse, boolean crowded) {
        FrameLayout screen = new FrameLayout(this);
        screen.setFocusable(true);
        screen.setFocusableInTouchMode(true);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(2));
        screen.setBackground(shape);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        label.setFontFeatureSettings("tnum");
        label.setIncludeFontPadding(false);
        label.setSingleLine(true);
        label.setShadowLayer(dp(3), 0, dp(1), 0x73000000);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        labelParams.setMargins(0, dp(3), dp(4), 0);
        screen.addView(label, labelParams);
        android.graphics.drawable.GradientDrawable chip = new android.graphics.drawable.GradientDrawable();
        chip.setCornerRadius(dp(1.5f));
        chip.setColor(0xB8000000);
        View.OnFocusChangeListener style = (view, focused) -> {
            int color = focused ? 0xFFFFFFFF : inUse ? 0xFF5BE9A0 : 0x66FFFFFF;
            shape.setStroke(focused || inUse ? dp(1.5f) : Math.max(1, dp(0.75f)), color);
            shape.setColor(inUse ? 0x1F5BE9A0 : 0x00000000);
            label.setTextColor(focused ? 0xFFFFFFFF : inUse ? 0xFF5BE9A0 : 0x8CFFFFFF);
            label.setTypeface(null, focused || inUse ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            // Si un borde cruza el número, solo se ve con foco o en uso, sobre un fondo oscuro.
            label.setVisibility(!crowded || focused || inUse ? View.VISIBLE : View.INVISIBLE);
            label.setBackground(crowded && (focused || inUse) ? chip : null);
            int padX = crowded && (focused || inUse) ? dp(2.5f) : 0;
            label.setPadding(padX, 0, padX, 0);
        };
        style.onFocusChange(screen, false);
        screen.setTag(R.id.source_selector_options, style);
        return screen;
    }

    /** Detalle a la derecha de una calidad: «CALIDAD», «1080p  1920 × 1080 · 5.1 Mbps» y en uso. */
    void showScreenDetail(PlaybackOption option) {
        if (pulseEyebrow == null) return;
        pulseEyebrow.setText(R.string.source_selector_screens_detail_eyebrow);
        android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder(option.automatic
                ? getString(R.string.source_selector_screens_auto) : screenTitle(option));
        line.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, line.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        List<String> parts = new ArrayList<>();
        if (option.automatic) {
            parts.add(getString(R.string.source_selector_screens_auto_detail));
        } else if (option.quality != null) {
            if (option.quality.width > 0 && option.quality.height > 0) {
                parts.add(option.quality.width + " × " + option.quality.height);
            }
            if (option.quality.bitrate > 0) parts.add(formatBitrate(option.quality.bitrate));
        }
        if (!parts.isEmpty()) {
            int start = line.length();
            line.append("  ").append(TextUtils.join(" · ", parts));
            line.setSpan(new ForegroundColorSpan(0xB3FFFFFF), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new android.text.style.RelativeSizeSpan(0.87f), start, line.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        pulseQualityLine.setText(line);
        String playing = minimalHeroQuality();
        boolean automatic = false;
        String fixed = "";
        for (PlaybackOption candidate : playbackOptions) {
            if (candidate.automatic && candidate.selected) automatic = true;
            if (candidate.quality != null && candidate.selected) fixed = screenTitle(candidate);
        }
        android.text.SpannableStringBuilder status = new android.text.SpannableStringBuilder("● ");
        status.setSpan(new ForegroundColorSpan(0xFF5BE9A0), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        status.append(automatic
                ? getString(R.string.source_selector_screens_status_auto, AppStrings.isBlank(playing) ? "—" : playing)
                : getString(R.string.source_selector_screens_status_fixed, AppStrings.isBlank(fixed) ? playing : fixed));
        pulseStatus.setText(status);
    }

    /**
     * Navegación de las pantallas: ▲▼ entre calidades (y «Auto»); ◀▶ dan la vuelta (0.6.58, pedido del
     * usuario: desde la señal, «a la derecha me voy a la resolución» y «hacia la izquierda… hacia
     * eliminación»): señales → calidades → acciones → señales. Devuelve false si no hay a dónde ir.
     */
    boolean moveScreens(int dx, int dy) {
        minimalFocusOpening = false;
        int current = sourceCandidateFocusIndex;
        if (current < 0 || current >= sourceCandidateViews.size()) return false;
        List<Integer> actions = new ArrayList<>();
        for (int index = 0; index < minimalItemGroup.size(); index++) {
            if (minimalItemGroup.get(index) == MINIMAL_GROUP_ACTIONS) actions.add(index);
        }
        // Vuelta con ▶: señales → versiones TvVoo → calidades → acciones → señales (◀, al revés).
        List<List<Integer>> groups = new ArrayList<>();
        for (List<Integer> group : java.util.Arrays.asList(screensSignals, screensVersions, screensQualities, actions)) {
            if (!group.isEmpty()) groups.add(group);
        }
        int groupIndex = -1;
        for (int index = 0; index < groups.size() && groupIndex < 0; index++) {
            if (groups.get(index).contains(current)) groupIndex = index;
        }
        if (groupIndex < 0) return false;
        List<Integer> group = groups.get(groupIndex);
        int position = group.indexOf(current);
        boolean vertical = group == screensQualities || group == screensVersions;
        int target = -1;
        if (dy != 0) {
            if (vertical) {
                int next = position + dy;
                if (next >= 0 && next < group.size()) target = group.get(next);
            } else if (group == actions && dy < 0) {
                target = screensFocusedQuality();
            }
        } else if (dx != 0) {
            if (!vertical) {
                int next = position + dx;
                if (next >= 0 && next < group.size()) target = group.get(next);
            }
            if (target < 0 && groups.size() > 1) {
                List<Integer> other = groups.get((groupIndex + dx + groups.size()) % groups.size());
                target = screensEntry(other, dx, actions);
            }
        }
        if (target < 0 || target >= sourceCandidateViews.size()) {
            // Animaciones B: no hay más hacia allá; la opción rebota hacia ese lado.
            if (!minimalFocusOpening) Motion.bump(sourceCandidateViews.get(current), dx * dp(5f), dy * dp(4f));
            return false;
        }
        sourceCandidateViews.get(target).requestFocus();
        return true;
    }

    /** Dónde entra el foco al llegar a un grupo: lo elegido, o el borde por el que se llega. */
    int screensEntry(List<Integer> group, int dx, List<Integer> actions) {
        if (group == screensQualities) return screensFocusedQuality();
        if (group == screensVersions) {
            for (int index : screensVersions) {
                if (playbackOptions.get(index).selected) return index;
            }
            return screensVersions.get(0);
        }
        return dx > 0 ? group.get(0) : group.get(group.size() - 1);
    }

    /** Foco al abrir: lo que suena (versión o señal), la primera señal o, sin señales, la calidad. */
    int screensOpeningFocus() {
        for (int index : screensVersions) {
            if (playbackOptions.get(index).selected) return index;
        }
        for (int index : screensSignals) {
            if (playbackOptions.get(index).selected) return index;
        }
        if (!screensSignals.isEmpty()) return screensSignals.get(0);
        if (!screensVersions.isEmpty()) return screensVersions.get(0);
        return screensFocusedQuality();
    }

    /** Calidad a la que se vuelve desde otro grupo: la elegida («Auto» o una fija), o la primera. */
    int screensFocusedQuality() {
        for (int index : screensQualities) {
            if (playbackOptions.get(index).selected) return index;
        }
        return screensQualities.isEmpty() ? -1 : screensQualities.get(0);
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
        sourceCandidateFocusIndex = minimalFocusOpening ? 0 : Math.max(0, firstMinimalAction());
        if (!sourceCandidateViews.isEmpty()) sourceCandidateViews.get(sourceCandidateFocusIndex).requestFocus();
    }

    /** ◀ ▶ dentro de la misma línea (escala, señal o acciones); false si no hay a dónde ir. */
    boolean moveMinimalHorizontal(int delta) {
        if (screensMode) return moveScreens(delta, 0);
        minimalFocusOpening = false;
        int current = sourceCandidateFocusIndex;
        if (current < 0 || current >= sourceCandidateViews.size()) return false;
        int line = minimalItemLine.get(current);
        // La señal se registra entre las calidades (orden de playbackOptions): se busca la
        // siguiente opción de la misma línea, no la vecina en la lista.
        for (int target = current + delta; target >= 0 && target < sourceCandidateViews.size(); target += delta) {
            if (minimalItemLine.get(target) != line) continue;
            if (!minimalItemGroup.get(target).equals(minimalItemGroup.get(current))) break;
            sourceCandidateViews.get(target).requestFocus();
            return true;
        }
        // Las acciones están a la derecha de la escala (0.6.54, pedido del usuario: «para ir a
        // calidades tengo que apretar hacia la derecha… está muy extraño»): ◀ desde la primera
        // acción va a la última opción de la escala y ▶ desde el final de la escala, a las acciones.
        boolean fromActions = minimalItemGroup.get(current) == MINIMAL_GROUP_ACTIONS;
        int target = -1;
        if (fromActions && delta < 0) {
            for (int index = 0; index < sourceCandidateViews.size(); index++) {
                if (minimalItemGroup.get(index) == MINIMAL_GROUP_OPTIONS && minimalItemLine.get(index) == 0) target = index;
            }
        } else if (!fromActions && delta > 0 && line == 0) {
            target = minimalItemGroup.indexOf(MINIMAL_GROUP_ACTIONS);
        } else if (!fromActions && delta < 0 && line == 0) {
            // Da la vuelta (0.6.58): ◀ desde el inicio de la escala va a «Papelera».
            target = minimalItemGroup.lastIndexOf(MINIMAL_GROUP_ACTIONS);
        } else if (fromActions && delta > 0) {
            for (int index = 0; index < sourceCandidateViews.size() && target < 0; index++) {
                if (minimalItemGroup.get(index) == MINIMAL_GROUP_OPTIONS && minimalItemLine.get(index) == 0) target = index;
            }
        }
        if (target < 0) return false;
        sourceCandidateViews.get(target).requestFocus();
        return true;
    }
    /** ▲ ▼ a la opción más cercana en horizontal de la línea vecina. */
    void moveMinimalVertical(int delta) {
        minimalFocusOpening = false;
        if (screensMode) {
            moveScreens(0, delta);
            return;
        }
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
        providerPending = false;
        sourceCandidates.clear();
        playbackOptions.clear();
        if (resolved != null) sourceCandidates.addAll(resolved);
        pulseQuality.clear();
        fillDirectQuality(channel);
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
        providerPending = false;
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
        boolean anyCurrent = false;
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            anyCurrent |= candidate.getSource() != null && isCurrentSource(candidate.getSource());
        }
        // Canal directo con respaldo TvVoo que suena por su resolutor (Highfly, TVN): la URL no
        // calza, pero sin respaldo activo lo que suena es la principal.
        boolean principalByState = !anyCurrent && playbackChannel != null
                && TvVooBackup.hasTvVoo(playbackChannel) && !tvvooBackupActive;
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            boolean current = candidate.getSource() != null && isCurrentSource(candidate.getSource());
            if (principalByState && "direct-principal".equals(candidate.getVariantId())) current = true;
            playbackOptions.add(PlaybackOption.source(candidate, current));
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
        pulseQuality.clear();
        // Canal con señal preferida o respaldos directos (0.5.66, varios desde la 0.5.72):
        // todas sus señales arriba y, debajo, sus calidades.
        int backupCount = TvVooBackup.directBackupsOf(channel).size();
        if (backupCount > 0) {
            sourceCandidates.addAll(directSignalCandidates(channel));
            fillDirectQuality(channel);
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

    /** Calidad diaria (backups.json) de la principal y de cada respaldo directo en sourceCandidates. */
    void fillDirectQuality(Channel channel) {
        if (channel == null) return;
        for (ResolvedPlaybackCandidate candidate : sourceCandidates) {
            String variant = candidate.getVariantId();
            PublishedDirectBackups.Quality quality = null;
            if ("direct-principal".equals(variant)) {
                quality = PublishedDirectBackups.qualityFor(channel.getTvgId());
            } else if (variant.startsWith("direct-backup-")) {
                try {
                    Channel backup = TvVooBackup.directResolutionChannel(channel,
                            Integer.parseInt(variant.substring("direct-backup-".length())));
                    if (backup != null) quality = PublishedDirectBackups.qualityFor(backup.getTvgId());
                } catch (NumberFormatException ignored) {
                    // Variante ajena: sin calidad diaria.
                }
            }
            if (quality != null) pulseQuality.put(variant, quality);
        }
    }

    /**
     * Mientras TvVoo responde (proveedores): las señales directas ya listas y una fila «Probando…»
     * por versión conocida, en el mismo lugar que tendrán al final.
     */
    void renderProviderPlaceholders(List<TvVooStreamResolver.PlannedVersion> versions) {
        Channel channel = playbackChannel;
        if (channel == null || !isSourceSelectorVisible()) return;
        providerPending = true;
        sourceCandidates.clear();
        pulseQuality.clear();
        if (TvVooBackup.hasTvVoo(channel)) {
            sourceCandidates.addAll(directSignalCandidates(channel));
            fillDirectQuality(channel);
        }
        if (versions != null) {
            for (TvVooStreamResolver.PlannedVersion version : versions) {
                sourceCandidates.add(ResolvedPlaybackCandidate.version(version.name, "", null,
                        version.alias, "", false, version.preferred, 0));
            }
        }
        renderSourceSelectorOptions();
    }

    /** Agrega «Auto» y las calidades del stream en reproducción; devuelve las calidades. */
    List<VideoTrackOption> appendQualityOptions(Channel channel) {
        List<VideoTrackOption> qualities = player == null
                ? Collections.emptyList()
                : collectVideoTrackOptions(player.getCurrentTracks());
        VideoTrackOption selected = selectedQualityOption(channel, qualities);
        if (!classicUi) qualities = onePerHeight(qualities, selected);
        if (qualities.size() > 1) {
            boolean automatic = playbackPreferences != null
                    && playbackPreferences.isAutomaticQuality(channel);
            playbackOptions.add(PlaybackOption.automatic(automatic));
        }
        for (VideoTrackOption quality : qualities) {
            playbackOptions.add(PlaybackOption.quality(
                    quality,
                    quality == selected
            ));
        }
        return qualities;
    }

    /**
     * Una calidad por resolución (0.6.62, pedido del usuario con foto de Mega, que trae dos pistas
     * 1080: «en la foto se ven 2», opción «1» = una sola pantalla por resolución). Queda la elegida
     * si es una de ellas; si no, la de mayor bitrate. «Auto» sigue usando todas.
     */
    static List<VideoTrackOption> onePerHeight(List<VideoTrackOption> qualities, VideoTrackOption selected) {
        Map<Integer, VideoTrackOption> best = new LinkedHashMap<>();
        for (VideoTrackOption quality : qualities) {
            VideoTrackOption kept = best.get(quality.height);
            if (kept == null || (kept != selected && (quality == selected || quality.bitrate > kept.bitrate))) {
                best.put(quality.height, quality);
            }
        }
        return best.size() == qualities.size() ? qualities : new ArrayList<>(best.values());
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
        String originId = key.startsWith("m3u:") ? key.substring("m3u:".length()) : "";
        // Con un respaldo ya pasado a principal y la lista aún sin llegar, en Lista M3U el canal es
        // esa señal, y el siguiente respaldo es el que sigue.
        List<String> promoted = channelPromotions.promotedIds(originId, System.currentTimeMillis());
        String requestKey = promoted.isEmpty() ? key : "m3u:" + promoted.get(promoted.size() - 1);
        List<Channel> backups = new ArrayList<>();
        for (Channel copy : PublishedDirectBackups.backupsFor(originId)) {
            if (!promoted.contains(copy.getTvgId())) backups.add(copy);
        }
        if (backups.isEmpty()) {
            ChannelActionScenes.showTrashConfirm(this, channel.getName(),
                    () -> trashWhenLinked(channel, key, requestKey, null));
            return;
        }
        Channel next = backups.get(0);
        ChannelActionScenes.showTrashWithBackups(this, channel.getName(), backups.size(),
                ChannelActionScenes.describeBackup(next.getName(), next.getStreamUri().getHost(),
                        PublishedDirectBackups.qualityFor(next.getTvgId())),
                () -> trashWhenLinked(channel, key, requestKey, next),
                () -> trashWhenLinked(channel, key, requestKey, null));
    }

    private void trashWhenLinked(Channel channel, String key, String requestKey, Channel backup) {
        if (new GitHubCredentialStore(this).linked()) sendChannelToTrash(channel, key, requestKey, backup, true);
        else ChannelActionScenes.showGitHubLink(this, () -> sendChannelToTrash(channel, key, requestKey, backup, true));
    }

    /** Deja el pedido en GitHub; solo reabre el Device Flow si GitHub confirma que no puede renovar. */
    void sendChannelToTrash(Channel channel, String key, String requestKey, Channel backup, boolean mayRelink) {
        String name = channel.getName();
        boolean promote = backup != null;
        int failedText = promote ? R.string.channel_promote_failed : R.string.channel_trash_failed;
        showNotice(NOTICE_PROGRESS, R.string.notice_list, getString(promote
                ? R.string.channel_promote_sending : R.string.channel_trash_sending, name), false);
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
                                    () -> sendChannelToTrash(channel, key, requestKey, backup, false));
                        }
                    });
                    return;
                }
                try {
                    new GitHubChannelTrash().requestTrash(credentials, requestKey, name,
                            promote ? backup.getTvgId() : null, BuildConfig.VERSION_NAME,
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
                        if (promote) {
                            // Al tiro en esta TV; la lista publicada la alcanza después.
                            channelPromotions.add(key.substring("m3u:".length()), backup.getTvgId(),
                                    backup.getStreamUri(), System.currentTimeMillis());
                            showNotice(NOTICE_OK, R.string.notice_list,
                                    getString(R.string.channel_promote_done, name), true);
                            reapplyChannelList();
                            return;
                        }
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
                                    () -> sendChannelToTrash(channel, key, requestKey, backup, false));
                        } else {
                            showNotice(NOTICE_WARN, R.string.notice_trash,
                                    getString(failedText, reauthorizationRequired.getMessage()), true);
                        }
                        });
                } catch (GitHubChannelTrash.RefreshFailedException refreshFailed) {
                    // La sesión se conserva, pero esta TV no puede seguir así: se avisa con el código
                    // de GitHub y se ofrece vincular de nuevo (cancelar no borra nada).
                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        showNotice(NOTICE_WARN, R.string.notice_trash,
                                getString(failedText, refreshFailed.getMessage()), true);
                        if (mayRelink) {
                            ChannelActionScenes.showGitHubLink(this,
                                    () -> sendChannelToTrash(channel, key, requestKey, backup, false));
                        }
                    });
                } catch (java.io.IOException error) {
                    mainHandler.post(() -> showNotice(NOTICE_WARN, R.string.notice_trash,
                            getString(failedText,
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
        // Mediciones del pulso: lo pendiente se cancela; lo ya medido queda para este canal.
        cancelPulseLiveMeasure();
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

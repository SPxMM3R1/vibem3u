package cl.streambox.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Guía completa a pantalla completa, dibujada en un solo canvas, con la línea «Flotante
 * minimalista» (0.5.96, `DISENO.md`): todo es texto sobre una sola capa oscura que deja ver el
 * video arriba a la derecha. Arriba «GUÍA», los filtros como texto y la hora; luego el detalle
 * del programa enfocado; debajo el eje de horas y las filas: número y logo del canal a la
 * izquierda y los programas como texto, separados por una línea vertical tenue, sobre
 * {@link EpgGuideNavigator#WINDOW_MILLIS}. Foco = negrita blanca con línea fina debajo; el canal
 * que se está viendo lleva un punto verde; la hora actual es una línea cian.
 */
public final class EpgGuideView extends View implements GuideSurface {
    /** Datos que la guía lee por fila (canal). */
    interface Source {
        int rowCount();
        String number(int row);
        String name(int row);
        /** Categoría del canal (grupo de la lista); vacío si no tiene. */
        String category(int row);
        /** Logo del canal si ya está cargado; si no, la fuente lo pide y redibuja la guía. */
        Bitmap logo(int row);
        List<EpgProgramme> programmes(int row, long fromMillis, long toMillis);
        int playingRow();
        /** True si el programa tiene un recordatorio (se dibuja un anillo cian). */
        boolean hasReminder(int row, EpgProgramme programme);
        /** Filtros de categoría; el primero es «Todos». */
        List<String> filters();
        int selectedFilter();
        /** True mientras el foco está en la fila de filtros. */
        boolean filtersFocused();
    }

    private static final int CYAN = 0xFF00B8D9;
    private static final int GREEN = 0xFF5BE9A0;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BASE = 0xFF000000;
    // Blancos con alfa: normal, apagado, etiquetas.
    private static final int TEXT_ON_AIR = 0xDBFFFFFF;
    private static final int TEXT_FUTURE = 0x8CFFFFFF;
    private static final int TEXT_PAST = 0x61FFFFFF;
    private static final int TIME_FOCUSED = 0xC7FFFFFF;
    private static final int TIME_ON_AIR = 0x80FFFFFF;
    private static final int TIME_OTHER = 0x57FFFFFF;
    private static final int LABEL = 0x6BFFFFFF;
    private static final int EYEBROW = 0x9EFFFFFF;
    private static final int TAB = 0x80FFFFFF;
    private static final int DESCRIPTION = 0xB8FFFFFF;
    private static final int META = 0x9EFFFFFF;
    private static final int NEXT_LABEL = 0x73FFFFFF;
    private static final int NEXT_TITLE = 0xD9FFFFFF;
    private static final int DIVIDER = 0x1FFFFFFF;
    private static final int TRACK = 0x2EFFFFFF;
    /** Opacidad de la fila que se asoma bajo la última completa. */
    private static final int PEEK_ALPHA = 89;

    private final TextPaint heroTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint descriptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface regular = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE d", Locale.forLanguageTag("es-CL"));
    private final SimpleDateFormat dayKeyFormat = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);

    private Source source;
    private EpgGuideNavigator navigator;
    private long nowMillis;
    private int firstVisibleRow;

    public EpgGuideView(Context context) { this(context, null); }

    public EpgGuideView(Context context, AttributeSet attrs) {
        super(context, attrs);
        heroTitlePaint.setTypeface(bold);
        heroTitlePaint.setColor(WHITE);
        heroTitlePaint.setLetterSpacing(-0.015f);
        descriptionPaint.setColor(DESCRIPTION);
        labelPaint.setTypeface(medium);
        labelPaint.setLetterSpacing(0.2f);
        // Sombra suave para leer el texto sobre video claro.
        int shadow = 0x73000000;
        for (TextPaint paint : new TextPaint[] {heroTitlePaint, textPaint, smallPaint, labelPaint, descriptionPaint}) {
            paint.setShadowLayer(dpRaw(context, 5), 0f, dpRaw(context, 0.5f), shadow);
        }
        setWillNotDraw(false);
    }

    private static float dpRaw(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    @Override public void bind(Source source, EpgGuideNavigator navigator, long nowMillis) {
        this.source = source;
        this.navigator = navigator;
        this.nowMillis = nowMillis;
        this.firstVisibleRow = Math.max(0, navigator.getRow() - 2);
        invalidate();
    }

    @Override public void refresh(long nowMillis) {
        this.nowMillis = nowMillis;
        invalidate();
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (source == null || navigator == null) return;
        float width = getWidth();
        float height = getHeight();
        float left = dp(32);
        float right = width - dp(36);
        if (right <= left) return;

        // Geometría: detalle arriba, eje y filas debajo.
        float axisBaseline = dp(262);
        float rowsTop = dp(278);
        float rowHeight = dp(30);
        float rowPitch = dp(39);
        float gridLeft = dp(150);
        float gridWidth = right - gridLeft;
        long windowStart = navigator.getWindowStart();
        long windowEnd = navigator.getWindowEnd();

        drawBackground(canvas, width, height);

        int visibleRows = Math.max(1, (int) ((height - rowsTop - dp(12)) / rowPitch));
        int focusedRow = navigator.getRow();
        if (focusedRow < firstVisibleRow) firstVisibleRow = focusedRow;
        if (focusedRow >= firstVisibleRow + visibleRows) firstVisibleRow = focusedRow - visibleRows + 1;
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow, Math.max(0, source.rowCount() - visibleRows)));

        drawHeader(canvas, left, right);
        drawHero(canvas, left, right, focusedRow);
        drawAxis(canvas, gridLeft, gridWidth, axisBaseline, windowStart, windowEnd);

        boolean filtersFocused = source.filtersFocused();
        int shownRows = 0;
        for (int index = 0; index <= visibleRows; index++) {
            int row = firstVisibleRow + index;
            if (row >= source.rowCount()) break;
            float rowTop = rowsTop + index * rowPitch;
            if (rowTop >= height) break;
            boolean peek = index == visibleRows;
            if (!peek) shownRows++;
            int saved = peek ? canvas.saveLayerAlpha(0, rowTop, width, height, PEEK_ALPHA) : -1;
            drawRow(canvas, row, rowTop, rowHeight, left, right, gridLeft, gridWidth,
                    windowStart, windowEnd, row == focusedRow && !filtersFocused);
            if (saved >= 0) canvas.restoreToCount(saved);
        }

        if (nowMillis >= windowStart && nowMillis < windowEnd && shownRows > 0) {
            // Hora actual: línea cian fina desde el eje hasta el final de las filas visibles.
            float nowX = xFor(nowMillis, gridLeft, gridWidth, windowStart);
            float lineTop = axisBaseline + dp(6);
            float lineBottom = rowsTop + shownRows * rowPitch;
            shaderPaint.setShader(new LinearGradient(0f, lineTop, 0f, lineBottom,
                    CYAN, 0x5900B8D9, Shader.TileMode.CLAMP));
            canvas.drawRect(nowX - dp(0.5f), lineTop, nowX + dp(0.5f), lineBottom, shaderPaint);
            shaderPaint.setShader(null);
        }

        // Pie: la última fila se funde con el fondo.
        float footerTop = height - dp(24);
        shaderPaint.setShader(new LinearGradient(0f, footerTop, 0f, height,
                new int[] {BASE & 0x00FFFFFF, 0xF5000000, 0xF5000000}, new float[] {0f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, footerTop, width, height, shaderPaint);
        shaderPaint.setShader(null);
    }

    /**
     * Una sola capa: el video se apaga hacia abajo (grilla) y hacia la izquierda (detalle).
     * 0.6.00: la parte de arriba (filtros, hora y detalle) más oscura; con video claro la
     * lectura no era buena (foto del usuario). Sin desenfoque: no se puede sobre el video.
     */
    private void drawBackground(Canvas canvas, float width, float height) {
        shaderPaint.setShader(new LinearGradient(0f, 0f, 0f, height,
                new int[] {0xE6000000, 0xD1000000, 0xB3000000, 0xE0000000, 0xF5000000, 0xF7000000},
                new float[] {0f, 0.12f, 0.30f, 0.46f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, height, shaderPaint);
        shaderPaint.setShader(new LinearGradient(0f, 0f, width, 0f,
                new int[] {0xB8000000, 0x73000000, 0x00000000},
                new float[] {0f, 0.45f, 0.72f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, height, shaderPaint);
        shaderPaint.setShader(null);
    }

    private void drawHeader(Canvas canvas, float left, float right) {
        float baseline = dp(36);

        // «GUÍA» como etiqueta.
        labelPaint.setTextSize(sp(7.5f));
        labelPaint.setColor(EYEBROW);
        String title = getContext().getString(R.string.guide_title).toUpperCase(Locale.forLanguageTag("es-CL"));
        canvas.drawText(title, left, baseline, labelPaint);
        float tabsLeft = left + labelPaint.measureText(title) + dp(23);

        // Fecha del foco y hora actual, arriba a la derecha, como texto.
        textPaint.setTextSize(sp(10.5f));
        textPaint.setTypeface(medium);
        String time = timeFormat.format(new Date(nowMillis));
        float timeWidth = textPaint.measureText(time);
        textPaint.setTypeface(regular);
        String day = capitalize(dayFormat.format(new Date(navigator.getFocusMillis()))) + " · ";
        float dayWidth = textPaint.measureText(day);
        textPaint.setColor(0xB8FFFFFF);
        canvas.drawText(day, right - timeWidth - dayWidth, baseline, textPaint);
        textPaint.setTypeface(medium);
        textPaint.setColor(WHITE);
        canvas.drawText(time, right - timeWidth, baseline, textPaint);
        textPaint.setTypeface(regular);
        float tabsRight = right - timeWidth - dayWidth - dp(24);

        // Filtros como texto: el elegido en negrita blanca con línea fina debajo; la línea es
        // plena con el foco en los filtros y tenue con el foco en la grilla.
        List<String> filters = source.filters();
        if (filters == null || filters.isEmpty()) return;
        int selected = Math.max(0, Math.min(source.selectedFilter(), filters.size() - 1));
        textPaint.setTextSize(sp(10.5f));
        float gap = dp(19);
        float[] widths = new float[filters.size()];
        for (int i = 0; i < filters.size(); i++) {
            textPaint.setTypeface(i == selected ? bold : regular);
            widths[i] = textPaint.measureText(filters.get(i));
        }
        int first = 0;
        while (first < selected && span(widths, first, selected, gap) > tabsRight - tabsLeft) first++;
        float x = tabsLeft;
        for (int i = first; i < filters.size(); i++) {
            if (x + widths[i] > tabsRight) break;
            boolean on = i == selected;
            textPaint.setTypeface(on ? bold : regular);
            textPaint.setColor(on ? WHITE : TAB);
            canvas.drawText(filters.get(i), x, baseline, textPaint);
            if (on) {
                fillPaint.setColor(source.filtersFocused() ? WHITE : 0x8CFFFFFF);
                canvas.drawRect(x, baseline + dp(5), x + widths[i], baseline + dp(6.5f), fillPaint);
            }
            x += widths[i] + gap;
        }
        textPaint.setTypeface(regular);
    }

    private void drawHero(Canvas canvas, float left, float right, int row) {
        if (row < 0 || row >= source.rowCount()) return;
        List<EpgProgramme> programmes = source.programmes(row,
                navigator.getFocusMillis() - EpgGuideNavigator.WINDOW_MILLIS,
                navigator.getFocusMillis() + 12L * 60L * 60L * 1000L);
        EpgProgramme programme = EpgGuideNavigator.programmeAt(programmes, navigator.getFocusMillis());
        float heroWidth = Math.min(dp(500), (right - left) * 0.56f);

        // «● TVN · 001 · NACIONALES»: el punto verde, si es el canal que se está viendo.
        float eyebrowBaseline = dp(89);
        float x = left;
        if (row == source.playingRow()) {
            fillPaint.setColor(GREEN);
            canvas.drawCircle(x + dp(2), eyebrowBaseline - sp(2.6f), dp(2), fillPaint);
            x += dp(10);
        }
        String meta = source.name(row) + " · " + source.number(row);
        String category = source.category(row);
        if (!TextUtils.isEmpty(category)) meta += " · " + category;
        labelPaint.setTextSize(sp(7.5f));
        labelPaint.setColor(EYEBROW);
        canvas.drawText(TextUtils.ellipsize(meta.toUpperCase(Locale.forLanguageTag("es-CL")), labelPaint,
                heroWidth, TextUtils.TruncateAt.END).toString(), x, eyebrowBaseline, labelPaint);

        // Título grande.
        heroTitlePaint.setTextSize(sp(30));
        float titleBaseline = eyebrowBaseline + dp(32);
        String title = programme != null ? programme.getTitle()
                : getContext().getString(R.string.epg_no_information);
        canvas.drawText(TextUtils.ellipsize(title, heroTitlePaint, heroWidth,
                TextUtils.TruncateAt.END).toString(), left, titleBaseline, heroTitlePaint);
        if (programme == null) return;

        // Descripción: hasta dos líneas.
        float y = titleBaseline + dp(10);
        String description = programme.getDescription();
        if (!description.isEmpty()) {
            descriptionPaint.setTextSize(sp(11));
            StaticLayout layout = StaticLayout.Builder
                    .obtain(description, 0, description.length(), descriptionPaint, (int) (heroWidth * 0.9f))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.35f)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            canvas.save();
            canvas.translate(left, y);
            layout.draw(canvas);
            canvas.restore();
            y += layout.getHeight();
        }

        // Avance (si está al aire), recordatorio y horario.
        float lineCenter = y + dp(13);
        float textLeft = left;
        boolean onAir = programme.getStartMillis() <= nowMillis && nowMillis < programme.getStopMillis();
        if (onAir) {
            float barWidth = dp(150);
            float barHeight = dp(1);
            fillPaint.setColor(TRACK);
            canvas.drawRect(left, lineCenter - barHeight / 2f, left + barWidth, lineCenter + barHeight / 2f, fillPaint);
            float fraction = (nowMillis - programme.getStartMillis())
                    / (float) Math.max(1L, programme.getStopMillis() - programme.getStartMillis());
            fillPaint.setColor(CYAN);
            canvas.drawRect(left, lineCenter - barHeight / 2f,
                    left + barWidth * Math.max(0f, Math.min(1f, fraction)), lineCenter + barHeight / 2f, fillPaint);
            textLeft = left + barWidth + dp(10);
        }
        smallPaint.setTypeface(regular);
        smallPaint.setTextSize(sp(9));
        float lineBaseline = lineCenter + sp(3.2f);
        boolean reminded = source.hasReminder(row, programme);
        if (reminded) {
            drawReminderRing(canvas, textLeft + dp(3), lineBaseline - sp(3.2f));
            textLeft += dp(11);
        }
        smallPaint.setColor(META);
        canvas.drawText(TextUtils.ellipsize(scheduleText(programme), smallPaint, right - textLeft,
                TextUtils.TruncateAt.END).toString(), textLeft, lineBaseline, smallPaint);

        float nextBaseline = lineBaseline + dp(21);
        if (programme.getStartMillis() > nowMillis) {
            // Programa futuro: lo que hacen OK y OK mantenido, como texto.
            drawKeyHints(canvas, left, nextBaseline, row, reminded);
            return;
        }

        // Después: el programa siguiente del mismo canal.
        EpgProgramme next = null;
        for (EpgProgramme candidate : programmes) {
            if (candidate.getStartMillis() >= programme.getStopMillis()) { next = candidate; break; }
        }
        if (next == null) return;
        float nextLeft = left;
        if (source.hasReminder(row, next)) {
            drawReminderRing(canvas, nextLeft + dp(3), nextBaseline - sp(2.6f));
            nextLeft += dp(11);
        }
        String prefix = getContext().getString(R.string.guide_next,
                timeFormat.format(new Date(next.getStartMillis()))).toUpperCase(Locale.forLanguageTag("es-CL"));
        labelPaint.setTextSize(sp(7.5f));
        labelPaint.setColor(NEXT_LABEL);
        canvas.drawText(prefix, nextLeft, nextBaseline, labelPaint);
        nextLeft += labelPaint.measureText(prefix) + dp(7);
        smallPaint.setColor(NEXT_TITLE);
        canvas.drawText(TextUtils.ellipsize(next.getTitle(), smallPaint, Math.max(0f, left + heroWidth - nextLeft),
                TextUtils.TruncateAt.END).toString(), nextLeft, nextBaseline, smallPaint);
    }

    /** «OK Ver ahora en TVN   Mantener OK Avisarme al comenzar». */
    private void drawKeyHints(Canvas canvas, float left, float baseline, int row, boolean reminded) {
        String[][] hints = {
                {getContext().getString(R.string.guide_key_ok),
                        getContext().getString(R.string.guide_hint_watch, source.name(row))},
                {getContext().getString(R.string.guide_key_hold),
                        getContext().getString(reminded ? R.string.guide_hint_unremind : R.string.guide_hint_remind)},
        };
        float x = left;
        smallPaint.setTextSize(sp(9.5f));
        for (String[] hint : hints) {
            smallPaint.setTypeface(medium);
            smallPaint.setColor(0xDBFFFFFF);
            canvas.drawText(hint[0], x, baseline, smallPaint);
            x += smallPaint.measureText(hint[0]) + dp(6);
            smallPaint.setTypeface(regular);
            smallPaint.setColor(META);
            canvas.drawText(hint[1], x, baseline, smallPaint);
            x += smallPaint.measureText(hint[1]) + dp(20);
        }
    }

    private void drawAxis(Canvas canvas, float gridLeft, float gridWidth, float baseline,
                          long windowStart, long windowEnd) {
        smallPaint.setTypeface(regular);
        smallPaint.setTextSize(sp(7.5f));
        smallPaint.setLetterSpacing(0.06f);
        int slots = (int) (EpgGuideNavigator.WINDOW_MILLIS / EpgGuideNavigator.SLOT_MILLIS);
        for (int slot = 0; slot < slots; slot++) {
            long time = windowStart + slot * EpgGuideNavigator.SLOT_MILLIS;
            smallPaint.setColor(LABEL);
            canvas.drawText(timeFormat.format(new Date(time)), xFor(time, gridLeft, gridWidth, windowStart),
                    baseline, smallPaint);
        }
        if (nowMillis >= windowStart && nowMillis < windowEnd) {
            // Hora actual: en cian sobre el eje, centrada en su línea.
            String nowLabel = timeFormat.format(new Date(nowMillis));
            float nowX = xFor(nowMillis, gridLeft, gridWidth, windowStart);
            smallPaint.setTypeface(medium);
            smallPaint.setColor(CYAN);
            canvas.drawText(nowLabel, nowX - smallPaint.measureText(nowLabel) / 2f, baseline - dp(13), smallPaint);
            smallPaint.setTypeface(regular);
        }
        smallPaint.setLetterSpacing(0f);
    }

    private void drawRow(Canvas canvas, int row, float rowTop, float rowHeight, float left, float right,
                         float gridLeft, float gridWidth, long windowStart, long windowEnd,
                         boolean selected) {
        // Canal: número (blanco si es el de la fila enfocada o el que se ve; este con punto
        // verde) y logo.
        boolean playing = row == source.playingRow();
        textPaint.setTypeface(regular);
        textPaint.setTextSize(sp(9));
        textPaint.setLetterSpacing(0.04f);
        textPaint.setColor(selected || playing ? WHITE : LABEL);
        float centerBaseline = rowTop + rowHeight / 2f + sp(3.2f);
        String number = source.number(row);
        canvas.drawText(number, left, centerBaseline, textPaint);
        if (playing) {
            fillPaint.setColor(GREEN);
            canvas.drawCircle(left + textPaint.measureText(number) + dp(6), centerBaseline - sp(3.1f),
                    dp(1.75f), fillPaint);
        }
        textPaint.setLetterSpacing(0f);
        float logoLeft = left + dp(33);
        float logoWidth = dp(60 * LogoFit.LOGO_SCALE);
        Bitmap logo = source.logo(row);
        if (logo != null) {
            drawBitmapFit(canvas, logo, logoLeft, rowTop, rowHeight,
                    dp(44 * LogoFit.LOGO_SCALE) * dp(15 * LogoFit.LOGO_SCALE), logoWidth,
                    dp(22 * LogoFit.LOGO_SCALE), true);
        } else {
            textPaint.setColor(selected ? WHITE : TEXT_FUTURE);
            canvas.drawText(TextUtils.ellipsize(source.name(row), textPaint, gridLeft - logoLeft - dp(8),
                    TextUtils.TruncateAt.END).toString(), logoLeft, centerBaseline, textPaint);
        }

        List<EpgProgramme> programmes = source.programmes(row, windowStart, windowEnd);
        EpgProgramme focusedProgramme = selected
                ? EpgGuideNavigator.programmeAt(programmes, navigator.getFocusMillis())
                : null;
        if (programmes.isEmpty()) {
            textPaint.setTextSize(sp(10));
            textPaint.setColor(TEXT_PAST);
            canvas.drawText(getContext().getString(R.string.epg_no_information),
                    gridLeft + dp(9), centerBaseline, textPaint);
            return;
        }
        for (EpgProgramme programme : programmes) {
            float startX = xFor(programme.getStartMillis(), gridLeft, gridWidth, windowStart);
            float blockLeft = Math.max(gridLeft, startX);
            float blockRight = Math.min(right, xFor(programme.getStopMillis(), gridLeft, gridWidth, windowStart));
            if (blockRight - blockLeft < dp(6)) continue;
            if (startX > gridLeft) {
                // Separador tenue al inicio de cada programa (no al borde de la ventana).
                fillPaint.setColor(DIVIDER);
                canvas.drawRect(blockLeft, rowTop + dp(1), blockLeft + Math.max(1f, dp(0.5f)),
                        rowTop + rowHeight, fillPaint);
            }
            boolean past = programme.getStopMillis() <= nowMillis;
            boolean onAir = programme.getStartMillis() <= nowMillis && !past;
            boolean focused = programme == focusedProgramme;

            float textLeft = blockLeft + dp(9);
            float textWidth = blockRight - textLeft - dp(7);
            if (textWidth < dp(14)) continue;
            String title = programme.getStartMillis() < windowStart
                    ? "‹ " + programme.getTitle() : programme.getTitle();
            boolean reminded = source.hasReminder(row, programme);
            textPaint.setTextSize(sp(10));
            textPaint.setTypeface(focused ? bold : regular);
            textPaint.setColor(focused ? WHITE : onAir ? TEXT_ON_AIR : past ? TEXT_PAST : TEXT_FUTURE);
            float titleRoom = reminded ? textWidth - dp(10) : textWidth;
            String shownTitle = TextUtils.ellipsize(title, textPaint, Math.max(0f, titleRoom),
                    TextUtils.TruncateAt.END).toString();
            float titleBaseline = rowTop + dp(12);
            canvas.drawText(shownTitle, textLeft, titleBaseline, textPaint);
            float titleWidth = textPaint.measureText(shownTitle);
            if (reminded && titleRoom > dp(14)) {
                drawReminderRing(canvas, textLeft + titleWidth + dp(6), titleBaseline - sp(3.3f));
            }
            textPaint.setTypeface(regular);
            smallPaint.setTypeface(regular);
            smallPaint.setTextSize(sp(7));
            smallPaint.setColor(focused ? TIME_FOCUSED : onAir ? TIME_ON_AIR : TIME_OTHER);
            String range = TextUtils.ellipsize(range(programme), smallPaint, textWidth,
                    TextUtils.TruncateAt.END).toString();
            canvas.drawText(range, textLeft, rowTop + dp(23), smallPaint);
            if (focused) {
                // Foco: línea blanca fina debajo, del ancho del texto.
                float lineWidth = Math.min(textWidth, Math.max(titleWidth, smallPaint.measureText(range)));
                fillPaint.setColor(WHITE);
                canvas.drawRect(textLeft, rowTop + rowHeight - dp(1), textLeft + lineWidth, rowTop + rowHeight, fillPaint);
            }
        }
    }

    /** Recordatorio: anillo cian sin relleno, centrado en ({@code cx}, {@code cy}). */
    private void drawReminderRing(Canvas canvas, float cx, float cy) {
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(dp(0.75f));
        fillPaint.setColor(CYAN);
        canvas.drawCircle(cx, cy, dp(2.4f), fillPaint);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Dibuja el logo con tamaño óptico ({@link LogoFit}): la misma superficie visual para
     * todos, sin pasar {@code maxWidth}×{@code maxHeight}. Queda centrado en vertical dentro
     * de la franja {@code y}..{@code y + bandHeight}; devuelve el ancho dibujado.
     */
    private float drawBitmapFit(Canvas canvas, Bitmap bitmap, float x, float y, float bandHeight,
                                float area, float maxWidth, float maxHeight, boolean center) {
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return 0f;
        float[] size = LogoFit.opticalSize(bitmap, area, maxWidth, maxHeight);
        float drawWidth = size[0];
        float drawHeight = size[1];
        // Un logo agrandado a mano (o por tinta) no puede salirse de su franja ni de su celda.
        float fit = Math.min(1f, Math.min(bandHeight / drawHeight, maxWidth * 1.2f / drawWidth));
        drawWidth *= fit;
        drawHeight *= fit;
        float drawLeft = center ? x + (maxWidth - drawWidth) / 2f : x;
        float drawTop = y + (bandHeight - drawHeight) / 2f;
        rect.set(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight);
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint);
        return drawWidth;
    }

    private String scheduleText(EpgProgramme programme) {
        String text = range(programme);
        if (!sameDay(programme.getStartMillis(), nowMillis)) {
            text = capitalize(dayFormat.format(new Date(programme.getStartMillis()))) + " · " + text;
        }
        if (programme.getStartMillis() <= nowMillis && nowMillis < programme.getStopMillis()) {
            return text + " · " + getContext().getString(R.string.guide_remaining,
                    formatDuration(programme.getStopMillis() - nowMillis));
        }
        if (programme.getStartMillis() > nowMillis) {
            return text + " · " + getContext().getString(R.string.guide_starts_in,
                    formatDuration(programme.getStartMillis() - nowMillis));
        }
        return text;
    }

    /** Duración legible: «37 min», «2 h», «1 h 20 min». */
    static String formatDuration(long millis) {
        long minutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }

    private boolean sameDay(long a, long b) {
        return dayKeyFormat.format(new Date(a)).equals(dayKeyFormat.format(new Date(b)));
    }

    private String range(EpgProgramme programme) {
        return timeFormat.format(new Date(programme.getStartMillis())) + " – "
                + timeFormat.format(new Date(programme.getStopMillis()));
    }

    private static float span(float[] widths, int from, int to, float gap) {
        float total = 0f;
        for (int i = from; i <= to; i++) total += widths[i] + (i > from ? gap : 0f);
        return total;
    }

    private static float xFor(long millis, float gridLeft, float gridWidth, long windowStart) {
        return gridLeft + gridWidth * (millis - windowStart) / (float) EpgGuideNavigator.WINDOW_MILLIS;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}

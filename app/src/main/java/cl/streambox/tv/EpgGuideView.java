package cl.streambox.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
 * Guía completa a pantalla completa, dibujada en un solo canvas. Arriba un «hero» con el
 * programa enfocado sobre el video (que se ve a la derecha), luego los filtros por categoría,
 * el eje de horas y las filas: número y logo del canal a la izquierda y bloques de programas
 * sobre {@link EpgGuideNavigator#WINDOW_MILLIS}. El foco solo marca el canal seleccionado; el
 * programa enfocado se lee en el hero.
 */
public final class EpgGuideView extends View {
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
        /** True si el programa tiene un recordatorio (se dibuja una campana). */
        boolean hasReminder(int row, EpgProgramme programme);
        /** Filtros de categoría; el primero es «Todos». */
        List<String> filters();
        int selectedFilter();
        /** True mientras el foco está en la fila de filtros. */
        boolean filtersFocused();
    }

    private static final int CYAN = 0xFF00B8E6;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT = 0xFFE9F1F4;
    private static final int MUTED = 0xFF9FB0B7;
    private static final int BASE = 0xFF05080A;
    private static final int CHIP = 0x0DFFFFFF;
    private static final int CHIP_ON_TEXT = 0xFF021015;
    private static final int BLOCK_ON_AIR = 0x16FFFFFF;
    private static final int BLOCK_PAST = 0x08FFFFFF;
    private static final int BLOCK_FUTURE = 0x0DFFFFFF;
    private static final int SELECTED_CHANNEL = 0x2900B8E6;
    private static final int TICK = 0x2EFFFFFF;
    private static final int TRACK = 0x24FFFFFF;
    /** Cyan al 45 %: la línea de la hora actual no compite con los títulos. */
    private static final int NOW_LINE = 0x7300B8E6;
    private static final int NOW_EDGE = 0x0000B8E6;
    /** Opacidad de la fila que se asoma bajo la última completa. */
    private static final int PEEK_ALPHA = 89;

    private final TextPaint heroTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint descriptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface regular = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE d", Locale.forLanguageTag("es-CL"));
    private final SimpleDateFormat dayKeyFormat = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);

    private Drawable reminderBell;
    private Source source;
    private EpgGuideNavigator navigator;
    private long nowMillis;
    private int firstVisibleRow;

    public EpgGuideView(Context context) { this(context, null); }

    public EpgGuideView(Context context, AttributeSet attrs) {
        super(context, attrs);
        heroTitlePaint.setTypeface(bold);
        heroTitlePaint.setColor(WHITE);
        descriptionPaint.setColor(MUTED);
        setWillNotDraw(false);
    }

    void bind(Source source, EpgGuideNavigator navigator, long nowMillis) {
        this.source = source;
        this.navigator = navigator;
        this.nowMillis = nowMillis;
        this.firstVisibleRow = Math.max(0, navigator.getRow() - 2);
        invalidate();
    }

    void refresh(long nowMillis) {
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
        float right = width - dp(32);
        if (right <= left) return;

        // Geometría: hero arriba, eje y filas debajo.
        float axisTop = dp(260);
        float rowsTop = dp(283);
        float rowHeight = dp(32);
        float rowPitch = dp(38);
        float gridLeft = dp(150);
        float gridWidth = right - gridLeft;
        long windowStart = navigator.getWindowStart();
        long windowEnd = navigator.getWindowEnd();

        drawBackground(canvas, width, height, rowsTop);

        int visibleRows = Math.max(1, (int) ((height - rowsTop - dp(24)) / rowPitch));
        int focusedRow = navigator.getRow();
        if (focusedRow < firstVisibleRow) firstVisibleRow = focusedRow;
        if (focusedRow >= firstVisibleRow + visibleRows) firstVisibleRow = focusedRow - visibleRows + 1;
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow, Math.max(0, source.rowCount() - visibleRows)));

        drawHeader(canvas, left, right);
        drawHero(canvas, left, right, focusedRow);
        drawAxis(canvas, gridLeft, gridWidth, axisTop, windowStart, windowEnd);

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
            // Hora actual: línea continua de cyan translúcido exactamente de borde a
            // borde de las filas visibles, con el mismo difuminado arriba y abajo.
            float nowX = xFor(nowMillis, gridLeft, gridWidth, windowStart);
            float lineTop = rowsTop;
            float lineBottom = rowsTop + shownRows * rowPitch - (rowPitch - rowHeight);
            float fade = Math.min(0.45f, dp(14) / Math.max(1f, lineBottom - lineTop));
            shaderPaint.setShader(new LinearGradient(0f, lineTop, 0f, lineBottom,
                    new int[] {NOW_EDGE, NOW_LINE, NOW_LINE, NOW_EDGE},
                    new float[] {0f, fade, 1f - fade, 1f},
                    Shader.TileMode.CLAMP));
            rect.set(nowX - dp(1), lineTop, nowX + dp(1), lineBottom);
            canvas.drawRect(rect, shaderPaint);
            shaderPaint.setShader(null);
        }

        // Pie: la última fila se funde con el fondo y los atajos quedan abajo a la derecha.
        float footerTop = height - dp(32);
        shaderPaint.setShader(new LinearGradient(0f, footerTop, 0f, height,
                new int[] {BASE & 0x00FFFFFF, BASE, BASE}, new float[] {0f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, footerTop, width, height, shaderPaint);
        shaderPaint.setShader(null);
        drawHints(canvas, right, height - dp(13), filtersFocused);
    }

    private void drawBackground(Canvas canvas, float width, float height, float rowsTop) {
        // Hero: oscuro a la izquierda y el video asomándose a la derecha.
        float heroBottom = rowsTop;
        shaderPaint.setShader(new LinearGradient(0f, 0f, width, 0f,
                new int[] {BASE, 0xF005080A, 0x7305080A, 0x4005080A},
                new float[] {0f, 0.34f, 0.64f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, heroBottom, shaderPaint);
        // ...que baja fundiéndose hacia la grilla.
        float fadeTop = heroBottom - dp(75);
        shaderPaint.setShader(new LinearGradient(0f, fadeTop, 0f, heroBottom,
                BASE & 0x00FFFFFF, BASE, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, fadeTop, width, heroBottom, shaderPaint);
        shaderPaint.setShader(null);
        fillPaint.setColor(BASE);
        canvas.drawRect(0f, heroBottom, width, height, fillPaint);
    }

    private void drawHeader(Canvas canvas, float left, float right) {
        float chipTop = dp(24);
        float chipHeight = dp(24);
        float baseline = chipTop + chipHeight / 2f + sp(5.5f);

        textPaint.setTypeface(bold);
        textPaint.setTextSize(sp(16));
        textPaint.setColor(WHITE);
        String title = getContext().getString(R.string.guide_title);
        canvas.drawText(title, left, baseline, textPaint);
        float chipsLeft = left + textPaint.measureText(title) + dp(16);

        // Fecha del foco y hora actual, arriba a la derecha.
        textPaint.setTypeface(regular);
        textPaint.setTextSize(sp(13));
        String time = timeFormat.format(new Date(nowMillis));
        String day = capitalize(dayFormat.format(new Date(navigator.getFocusMillis()))) + "  ·  ";
        float timeWidth = textPaint.measureText(time);
        float dayWidth = textPaint.measureText(day);
        textPaint.setColor(WHITE);
        canvas.drawText(time, right - timeWidth, baseline, textPaint);
        textPaint.setColor(MUTED);
        canvas.drawText(day, right - timeWidth - dayWidth, baseline, textPaint);
        float chipsRight = right - timeWidth - dayWidth - dp(24);

        // Filtros: el activo en cyan; con foco en ellos, además un anillo blanco.
        List<String> filters = source.filters();
        if (filters == null || filters.isEmpty()) return;
        int selected = Math.max(0, Math.min(source.selectedFilter(), filters.size() - 1));
        textPaint.setTextSize(sp(11));
        float gap = dp(11);
        float padding = dp(11);
        float[] widths = new float[filters.size()];
        for (int i = 0; i < filters.size(); i++) {
            textPaint.setTypeface(i == selected ? bold : regular);
            widths[i] = textPaint.measureText(filters.get(i)) + padding * 2f;
        }
        int first = 0;
        while (first < selected && span(widths, first, selected, gap) > chipsRight - chipsLeft) first++;
        float x = chipsLeft;
        float chipBaseline = chipTop + chipHeight / 2f + sp(4f);
        for (int i = first; i < filters.size(); i++) {
            if (x + widths[i] > chipsRight) break;
            boolean on = i == selected;
            rect.set(x, chipTop, x + widths[i], chipTop + chipHeight);
            fillPaint.setColor(on ? CYAN : CHIP);
            canvas.drawRoundRect(rect, chipHeight / 2f, chipHeight / 2f, fillPaint);
            if (on && source.filtersFocused()) {
                fillPaint.setStyle(Paint.Style.STROKE);
                fillPaint.setStrokeWidth(dp(2));
                fillPaint.setColor(WHITE);
                rect.inset(-dp(3), -dp(3));
                canvas.drawRoundRect(rect, chipHeight / 2f + dp(3), chipHeight / 2f + dp(3), fillPaint);
                fillPaint.setStyle(Paint.Style.FILL);
            }
            textPaint.setTypeface(on ? bold : regular);
            textPaint.setColor(on ? CHIP_ON_TEXT : MUTED);
            canvas.drawText(filters.get(i), x + padding, chipBaseline, textPaint);
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

        // Logo + número · categoría.
        float logoTop = dp(74);
        float logoHeight = dp(29);
        Bitmap logo = source.logo(row);
        float metaLeft = left;
        if (logo != null) {
            // Mismo tamaño óptico que el logo del OSD.
            float drawnWidth = drawBitmapFit(canvas, logo, left, logoTop, logoHeight,
                    dp(LogoFit.OSD_LOGO_AREA_WIDTH_DP) * dp(LogoFit.OSD_LOGO_AREA_HEIGHT_DP),
                    dp(LogoFit.OSD_LOGO_MAX_WIDTH_DP), dp(LogoFit.OSD_LOGO_MAX_HEIGHT_DP),
                    false);
            metaLeft = left + drawnWidth + dp(10);
        }
        smallPaint.setTypeface(regular);
        smallPaint.setTextSize(sp(10.5f));
        smallPaint.setColor(MUTED);
        String meta = source.number(row);
        String category = source.category(row);
        if (!TextUtils.isEmpty(category)) meta += "  ·  " + category;
        if (logo == null) meta += "  ·  " + source.name(row);
        canvas.drawText(TextUtils.ellipsize(meta, smallPaint, heroWidth, TextUtils.TruncateAt.END).toString(),
                metaLeft, logoTop + logoHeight / 2f + sp(3.8f), smallPaint);

        // Título grande.
        heroTitlePaint.setTextSize(sp(29));
        float titleBaseline = logoTop + logoHeight + dp(8) + sp(25);
        String title = programme != null ? programme.getTitle()
                : getContext().getString(R.string.epg_no_information);
        canvas.drawText(TextUtils.ellipsize(title, heroTitlePaint, heroWidth,
                TextUtils.TruncateAt.END).toString(), left, titleBaseline, heroTitlePaint);
        if (programme == null) return;

        // Descripción: hasta dos líneas.
        float y = titleBaseline + dp(12);
        String description = programme.getDescription();
        if (!description.isEmpty()) {
            descriptionPaint.setTextSize(sp(11));
            StaticLayout layout = StaticLayout.Builder
                    .obtain(description, 0, description.length(), descriptionPaint, (int) (heroWidth * 0.9f))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.3f)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            canvas.save();
            canvas.translate(left, y);
            layout.draw(canvas);
            canvas.restore();
            y += layout.getHeight();
        }

        // Progreso (si está al aire) y horario.
        float progressCenter = y + dp(14);
        float textLeft = left;
        boolean onAir = programme.getStartMillis() <= nowMillis && nowMillis < programme.getStopMillis();
        if (onAir) {
            float barWidth = dp(200);
            float barHeight = dp(3);
            rect.set(left, progressCenter - barHeight / 2f, left + barWidth, progressCenter + barHeight / 2f);
            fillPaint.setColor(TRACK);
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, fillPaint);
            float fraction = (nowMillis - programme.getStartMillis())
                    / (float) Math.max(1L, programme.getStopMillis() - programme.getStartMillis());
            rect.right = left + barWidth * Math.max(0f, Math.min(1f, fraction));
            fillPaint.setColor(CYAN);
            canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, fillPaint);
            textLeft = left + barWidth + dp(9);
        }
        smallPaint.setTextSize(sp(10));
        float lineBaseline = progressCenter + sp(3.5f);
        if (source.hasReminder(row, programme)) {
            textLeft += drawBell(canvas, textLeft, lineBaseline, dp(10)) + dp(5);
        }
        String schedule = scheduleText(programme);
        smallPaint.setColor(MUTED);
        canvas.drawText(TextUtils.ellipsize(schedule, smallPaint, right - textLeft,
                TextUtils.TruncateAt.END).toString(), textLeft, lineBaseline, smallPaint);

        // Después: el programa siguiente del mismo canal.
        EpgProgramme next = null;
        for (EpgProgramme candidate : programmes) {
            if (candidate.getStartMillis() >= programme.getStopMillis()) { next = candidate; break; }
        }
        if (next == null) return;
        float nextBaseline = lineBaseline + dp(20);
        float nextLeft = left;
        if (source.hasReminder(row, next)) {
            nextLeft += drawBell(canvas, nextLeft, nextBaseline, dp(10)) + dp(5);
        }
        String prefix = getContext().getString(R.string.guide_next,
                timeFormat.format(new Date(next.getStartMillis()))) + "  ";
        smallPaint.setColor(MUTED);
        canvas.drawText(prefix, nextLeft, nextBaseline, smallPaint);
        nextLeft += smallPaint.measureText(prefix);
        smallPaint.setColor(WHITE);
        canvas.drawText(TextUtils.ellipsize(next.getTitle(), smallPaint, Math.max(0f, left + heroWidth - nextLeft),
                TextUtils.TruncateAt.END).toString(), nextLeft, nextBaseline, smallPaint);
    }

    private void drawAxis(Canvas canvas, float gridLeft, float gridWidth, float axisTop,
                          long windowStart, long windowEnd) {
        smallPaint.setTypeface(regular);
        smallPaint.setTextSize(sp(9.5f));
        float baseline = axisTop + sp(9.5f);
        boolean nowVisible = nowMillis >= windowStart && nowMillis < windowEnd;
        float nowX = xFor(nowMillis, gridLeft, gridWidth, windowStart);
        String nowLabel = timeFormat.format(new Date(nowMillis));
        float nowLabelLeft = nowX - smallPaint.measureText(nowLabel) / 2f;
        float nowLabelRight = nowX + smallPaint.measureText(nowLabel) / 2f;
        boolean nowLabelClashes = false;
        int slots = (int) (EpgGuideNavigator.WINDOW_MILLIS / EpgGuideNavigator.SLOT_MILLIS);
        for (int slot = 0; slot < slots; slot++) {
            long time = windowStart + slot * EpgGuideNavigator.SLOT_MILLIS;
            String label = timeFormat.format(new Date(time));
            float labelLeft = xFor(time, gridLeft, gridWidth, windowStart);
            float labelRight = labelLeft + smallPaint.measureText(label);
            // Las horas del eje mandan: si la hora actual se les encima, esa no se dibuja.
            if (labelLeft - dp(6) < nowLabelRight && nowLabelLeft < labelRight + dp(6)) {
                nowLabelClashes = true;
            }
            smallPaint.setColor(MUTED);
            canvas.drawText(label, labelLeft, baseline, smallPaint);
            rect.set(labelLeft, axisTop + dp(14), labelLeft + dp(1), axisTop + dp(18));
            fillPaint.setColor(TICK);
            canvas.drawRoundRect(rect, dp(0.5f), dp(0.5f), fillPaint);
        }
        if (nowVisible && !nowLabelClashes) {
            // Hora actual: mismo tamaño que el eje, en cyan, centrada sobre la línea.
            smallPaint.setColor(CYAN);
            canvas.drawText(nowLabel, nowLabelLeft, baseline, smallPaint);
        }
    }

    private void drawRow(Canvas canvas, int row, float rowTop, float rowHeight, float left, float right,
                         float gridLeft, float gridWidth, long windowStart, long windowEnd,
                         boolean selected) {
        // Canal: número y logo; el seleccionado con un velo cyan.
        float cellWidth = dp(110);
        if (selected) {
            rect.set(left, rowTop, left + cellWidth, rowTop + rowHeight);
            fillPaint.setColor(SELECTED_CHANNEL);
            canvas.drawRoundRect(rect, dp(6), dp(6), fillPaint);
        }
        textPaint.setTypeface(regular);
        textPaint.setTextSize(sp(10));
        textPaint.setColor(selected ? WHITE : row == source.playingRow() ? CYAN : MUTED);
        float centerBaseline = rowTop + rowHeight / 2f + sp(3.5f);
        canvas.drawText(source.number(row), left + dp(8), centerBaseline, textPaint);
        float logoLeft = left + dp(38);
        float logoWidth = dp(60);
        Bitmap logo = source.logo(row);
        if (logo != null) {
            drawBitmapFit(canvas, logo, logoLeft, rowTop, rowHeight,
                    dp(44) * dp(15), logoWidth, dp(22), true);
        } else {
            textPaint.setColor(selected ? WHITE : MUTED);
            canvas.drawText(TextUtils.ellipsize(source.name(row), textPaint, cellWidth - dp(42),
                    TextUtils.TruncateAt.END).toString(), logoLeft, centerBaseline, textPaint);
        }

        List<EpgProgramme> programmes = source.programmes(row, windowStart, windowEnd);
        if (programmes.isEmpty()) {
            textPaint.setColor(MUTED);
            canvas.drawText(getContext().getString(R.string.epg_no_information),
                    gridLeft + dp(9), centerBaseline, textPaint);
            return;
        }
        for (EpgProgramme programme : programmes) {
            float blockLeft = Math.max(gridLeft, xFor(programme.getStartMillis(), gridLeft, gridWidth, windowStart));
            float blockRight = Math.min(right, xFor(programme.getStopMillis(), gridLeft, gridWidth, windowStart)) - dp(4);
            if (blockRight - blockLeft < dp(6)) continue;
            boolean past = programme.getStopMillis() <= nowMillis;
            boolean onAir = programme.getStartMillis() <= nowMillis && !past;
            rect.set(blockLeft, rowTop, blockRight, rowTop + rowHeight);
            fillPaint.setColor(onAir ? BLOCK_ON_AIR : past ? BLOCK_PAST : BLOCK_FUTURE);
            canvas.drawRoundRect(rect, dp(6), dp(6), fillPaint);

            float textLeft = blockLeft + dp(9);
            float textWidth = blockRight - textLeft - dp(6);
            if (textWidth < dp(14)) continue;
            float titleBaseline = rowTop + dp(15);
            if (source.hasReminder(row, programme) && textWidth > dp(24)) {
                // Campana cyan antes del título: el programa tiene recordatorio.
                float used = drawBell(canvas, textLeft, titleBaseline, dp(9)) + dp(4);
                textLeft += used;
                textWidth -= used;
            }
            String title = programme.getStartMillis() < windowStart
                    ? "‹ " + programme.getTitle() : programme.getTitle();
            textPaint.setTextSize(sp(11));
            textPaint.setColor(past ? MUTED : TEXT);
            canvas.drawText(TextUtils.ellipsize(title, textPaint, textWidth,
                    TextUtils.TruncateAt.END).toString(), textLeft, titleBaseline, textPaint);
            smallPaint.setTypeface(regular);
            smallPaint.setTextSize(sp(8.5f));
            smallPaint.setColor(MUTED);
            canvas.drawText(TextUtils.ellipsize(range(programme), smallPaint, textWidth,
                    TextUtils.TruncateAt.END).toString(), textLeft, rowTop + dp(27), smallPaint);
        }
    }

    /** Atajos «tecla acción» separados por «|», con la tecla en blanco. */
    private void drawHints(Canvas canvas, float right, float baseline, boolean filtersFocused) {
        String[] items = getContext().getString(filtersFocused
                ? R.string.guide_hints_filters : R.string.guide_hints).split("\\|");
        smallPaint.setTextSize(sp(9));
        float x = right;
        for (int i = items.length - 1; i >= 0; i--) {
            String[] parts = items[i].split(":", 2);
            String key = parts[0];
            String action = parts.length > 1 ? " " + parts[1] : "";
            smallPaint.setTypeface(regular);
            float actionWidth = smallPaint.measureText(action);
            smallPaint.setColor(MUTED);
            canvas.drawText(action, x - actionWidth, baseline, smallPaint);
            smallPaint.setTypeface(bold);
            float keyWidth = smallPaint.measureText(key);
            smallPaint.setColor(WHITE);
            canvas.drawText(key, x - actionWidth - keyWidth, baseline, smallPaint);
            x -= actionWidth + keyWidth + dp(17);
        }
        smallPaint.setTypeface(regular);
    }

    /** Dibuja la campana con la base en {@code baseline}; devuelve el ancho usado. */
    private float drawBell(Canvas canvas, float x, float baseline, float size) {
        if (reminderBell == null) reminderBell = getContext().getDrawable(R.drawable.ic_reminder_bell);
        if (reminderBell == null) return 0f;
        int top = Math.round(baseline - size + dp(1));
        reminderBell.setBounds(Math.round(x), top, Math.round(x + size), Math.round(top + size));
        reminderBell.draw(canvas);
        return size;
    }

    /**
     * Dibuja el logo con tamaño óptico ({@link LogoFit}): la misma superficie visual para
     * todos, sin pasar {@code maxWidth}×{@code maxHeight}. Queda centrado en vertical dentro
     * de la franja {@code y}..{@code y + bandHeight}; devuelve el ancho dibujado.
     */
    private float drawBitmapFit(Canvas canvas, Bitmap bitmap, float x, float y, float bandHeight,
                                float area, float maxWidth, float maxHeight, boolean center) {
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return 0f;
        float[] size = LogoFit.opticalSize(bitmap.getWidth(), bitmap.getHeight(), area,
                maxWidth, maxHeight);
        float drawWidth = size[0];
        float drawHeight = size[1];
        float drawLeft = center ? x + (maxWidth - drawWidth) / 2f : x;
        float drawTop = y + (bandHeight - drawHeight) / 2f;
        rect.set(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight);
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint);
        return drawWidth;
    }

    private String scheduleText(EpgProgramme programme) {
        String text = range(programme);
        if (!sameDay(programme.getStartMillis(), nowMillis)) {
            text = capitalize(dayFormat.format(new Date(programme.getStartMillis()))) + "  ·  " + text;
        }
        if (programme.getStartMillis() <= nowMillis && nowMillis < programme.getStopMillis()) {
            return text + "  ·  " + getContext().getString(R.string.guide_remaining,
                    formatDuration(programme.getStopMillis() - nowMillis));
        }
        if (programme.getStartMillis() > nowMillis) {
            return text + "  ·  " + getContext().getString(R.string.guide_starts_in,
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

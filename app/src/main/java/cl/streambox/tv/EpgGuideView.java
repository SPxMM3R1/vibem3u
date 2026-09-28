package cl.streambox.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
 * Full programme guide drawn on a single canvas: channel rows on the left and a
 * {@link EpgGuideNavigator#WINDOW_MILLIS} timeline of programme blocks. Same visual language
 * as the OSD: flat panel, no rules, focus as a soft veil with the title in cyan.
 */
public final class EpgGuideView extends View {
    /** Data the guide reads for each row (channel). */
    interface Source {
        int rowCount();
        String number(int row);
        String name(int row);
        List<EpgProgramme> programmes(int row, long fromMillis, long toMillis);
        int playingRow();
        /** True si el programa tiene un recordatorio (se dibuja una campana). */
        boolean hasReminder(int row, EpgProgramme programme);
    }

    private static final int CYAN = 0xFF00B8E6;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB8C4C9;
    private static final int DIM = 0xFF7F8C92;
    private static final int VEIL = 0x10FFFFFF;
    private static final int VEIL_FOCUS = 0x1FFFFFFF;
    private static final int NOW_GLOW = 0x8C00B8E6;

    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint descriptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Typeface regular = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE d", Locale.forLanguageTag("es-CL"));

    private Drawable reminderBell;
    private Source source;
    private EpgGuideNavigator navigator;
    private long nowMillis;
    private int firstVisibleRow;

    public EpgGuideView(Context context) { this(context, null); }

    public EpgGuideView(Context context, AttributeSet attrs) {
        super(context, attrs);
        titlePaint.setTypeface(bold);
        descriptionPaint.setColor(MUTED);
        nowPaint.setColor(CYAN);
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
        float left = getPaddingLeft() + dp(20);
        float right = getWidth() - getPaddingRight() - dp(20);
        float top = getPaddingTop() + dp(16);
        float bottom = getHeight() - getPaddingBottom() - dp(12);
        if (right <= left || bottom <= top) return;

        // Encabezado: título, día y atajos.
        titlePaint.setTextSize(sp(18));
        titlePaint.setColor(WHITE);
        float headerBaseline = top + sp(18);
        canvas.drawText(getContext().getString(R.string.guide_title), left, headerBaseline, titlePaint);
        float titleWidth = titlePaint.measureText(getContext().getString(R.string.guide_title));
        textPaint.setTextSize(sp(12));
        textPaint.setColor(MUTED);
        long windowStart = navigator.getWindowStart();
        String day = capitalize(dayFormat.format(new Date(navigator.getFocusMillis())));
        canvas.drawText(day, left + titleWidth + dp(14), headerBaseline, textPaint);
        smallPaint.setTextSize(sp(10));
        smallPaint.setColor(MUTED);
        String hints = getContext().getString(R.string.guide_hints);
        canvas.drawText(hints, right - smallPaint.measureText(hints), headerBaseline, smallPaint);

        // Detalle del programa enfocado (abajo).
        float detailHeight = dp(64);
        float gridBottom = bottom - detailHeight - dp(8);

        // Eje de horas.
        float channelWidth = dp(170);
        float gridLeft = left + channelWidth;
        float axisBaseline = headerBaseline + dp(26);
        float gridWidth = right - gridLeft;
        smallPaint.setColor(MUTED);
        smallPaint.setTextSize(sp(10));
        boolean nowVisible = nowMillis >= windowStart && nowMillis < navigator.getWindowEnd();
        float nowX = xFor(nowMillis, gridLeft, gridWidth, windowStart);
        String nowLabel = timeFormat.format(new Date(nowMillis));
        float nowLabelLeft = nowX - smallPaint.measureText(nowLabel) / 2f;
        float nowLabelRight = nowX + smallPaint.measureText(nowLabel) / 2f;
        boolean nowLabelClashes = false;
        for (int slot = 0; slot < 6; slot++) {
            long time = windowStart + slot * EpgGuideNavigator.SLOT_MILLIS;
            String label = timeFormat.format(new Date(time));
            float labelLeft = xFor(time, gridLeft, gridWidth, windowStart);
            float labelRight = labelLeft + smallPaint.measureText(label);
            // Las horas del eje mandan: si la hora actual se les encima, esa no se dibuja.
            if (labelLeft - dp(6) < nowLabelRight && nowLabelLeft < labelRight + dp(6)) {
                nowLabelClashes = true;
            }
            canvas.drawText(label, labelLeft, axisBaseline, smallPaint);
        }
        if (nowVisible && !nowLabelClashes) {
            // Hora actual: mismo tamaño que el eje, en cyan, centrada sobre la línea.
            smallPaint.setColor(CYAN);
            canvas.drawText(nowLabel, nowLabelLeft, axisBaseline, smallPaint);
            smallPaint.setColor(MUTED);
        }

        // Filas.
        float rowsTop = axisBaseline + dp(8);
        float rowHeight = dp(46);
        float rowGap = dp(4);
        int visibleRows = Math.max(1, (int) ((gridBottom - rowsTop + rowGap) / (rowHeight + rowGap)));
        int focusedRow = navigator.getRow();
        if (focusedRow < firstVisibleRow) firstVisibleRow = focusedRow;
        if (focusedRow >= firstVisibleRow + visibleRows) firstVisibleRow = focusedRow - visibleRows + 1;
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow, Math.max(0, source.rowCount() - visibleRows)));

        EpgProgramme focusedProgramme = null;
        for (int index = 0; index < visibleRows; index++) {
            int row = firstVisibleRow + index;
            if (row >= source.rowCount()) break;
            float rowTop = rowsTop + index * (rowHeight + rowGap);
            float rowBottom = rowTop + rowHeight;
            float centerBaseline = rowTop + rowHeight / 2f + sp(4.5f);
            boolean isFocusedRow = row == focusedRow;

            textPaint.setTextSize(sp(12));
            textPaint.setColor(row == source.playingRow() ? CYAN : MUTED);
            canvas.drawText(source.number(row), left, centerBaseline, textPaint);
            textPaint.setColor(isFocusedRow ? WHITE : MUTED);
            float nameLeft = left + dp(38);
            String name = TextUtils.ellipsize(source.name(row), textPaint,
                    channelWidth - dp(46), TextUtils.TruncateAt.END).toString();
            canvas.drawText(name, nameLeft, centerBaseline, textPaint);

            List<EpgProgramme> programmes = source.programmes(row, windowStart, navigator.getWindowEnd());
            EpgProgramme rowFocus = isFocusedRow
                    ? EpgGuideNavigator.programmeAt(programmes, navigator.getFocusMillis())
                    : null;
            if (isFocusedRow) focusedProgramme = rowFocus;
            if (programmes.isEmpty()) {
                if (isFocusedRow) {
                    rect.set(gridLeft, rowTop, right, rowBottom);
                    blockPaint.setColor(VEIL_FOCUS);
                    canvas.drawRoundRect(rect, dp(6), dp(6), blockPaint);
                }
                textPaint.setColor(isFocusedRow ? CYAN : DIM);
                canvas.drawText(getContext().getString(R.string.epg_no_information),
                        gridLeft + dp(10), centerBaseline, textPaint);
                continue;
            }
            for (EpgProgramme programme : programmes) {
                float blockLeft = Math.max(gridLeft, xFor(programme.getStartMillis(), gridLeft, gridWidth, windowStart));
                float blockRight = Math.min(right, xFor(programme.getStopMillis(), gridLeft, gridWidth, windowStart)) - dp(4);
                if (blockRight - blockLeft < dp(6)) continue;
                boolean focused = programme == rowFocus;
                rect.set(blockLeft, rowTop, blockRight, rowBottom);
                blockPaint.setColor(focused ? VEIL_FOCUS : VEIL);
                canvas.drawRoundRect(rect, dp(6), dp(6), blockPaint);

                float textLeft = blockLeft + dp(9);
                float textWidth = blockRight - textLeft - dp(6);
                if (textWidth < dp(14)) continue;
                if (source.hasReminder(row, programme)) {
                    // Campana cyan antes del título: el programa tiene recordatorio.
                    if (reminderBell == null) reminderBell = getContext().getDrawable(R.drawable.ic_reminder_bell);
                    if (reminderBell != null && textWidth > dp(24)) {
                        int size = Math.round(dp(11));
                        int bellTop = Math.round(rowTop + dp(19) - size + dp(1));
                        reminderBell.setBounds(Math.round(textLeft), bellTop,
                                Math.round(textLeft) + size, bellTop + size);
                        reminderBell.draw(canvas);
                        textLeft += size + dp(4);
                        textWidth -= size + dp(4);
                    }
                }
                boolean past = programme.getStopMillis() <= nowMillis;
                titlePaint.setTextSize(sp(12));
                titlePaint.setTypeface(focused ? bold : regular);
                titlePaint.setColor(focused ? CYAN : past ? DIM : WHITE);
                canvas.drawText(TextUtils.ellipsize(programme.getTitle(), titlePaint, textWidth,
                        TextUtils.TruncateAt.END).toString(), textLeft, rowTop + dp(19), titlePaint);
                smallPaint.setTextSize(sp(9));
                smallPaint.setColor(MUTED);
                canvas.drawText(TextUtils.ellipsize(range(programme), smallPaint, textWidth,
                        TextUtils.TruncateAt.END).toString(), textLeft, rowTop + dp(35), smallPaint);
            }
        }
        titlePaint.setTypeface(bold);

        if (nowVisible) {
            // Hora actual: una sola línea continua sobre todos los canales visibles,
            // con un punto donde parte y un brillo suave.
            int shownRows = Math.max(0, Math.min(visibleRows, source.rowCount() - firstVisibleRow));
            if (shownRows > 0) {
                float lineTop = rowsTop - dp(2);
                float lineBottom = rowsTop + shownRows * (rowHeight + rowGap) - rowGap;
                nowPaint.setShadowLayer(dp(3), 0f, 0f, NOW_GLOW);
                rect.set(nowX - dp(1), lineTop, nowX + dp(1), lineBottom);
                canvas.drawRoundRect(rect, dp(1), dp(1), nowPaint);
                canvas.drawCircle(nowX, lineTop, dp(3), nowPaint);
            }
        }

        drawDetail(canvas, focusedProgramme, left, right, bottom - detailHeight, focusedRow);
    }

    private void drawDetail(Canvas canvas, EpgProgramme programme, float left, float right,
                            float top, int row) {
        titlePaint.setTextSize(sp(15));
        titlePaint.setColor(WHITE);
        float titleBaseline = top + sp(15);
        if (programme == null) {
            titlePaint.setColor(MUTED);
            canvas.drawText(getContext().getString(R.string.epg_no_information), left, titleBaseline, titlePaint);
            return;
        }
        float columnWidth = (right - left) * 0.42f;
        canvas.drawText(TextUtils.ellipsize(programme.getTitle(), titlePaint, columnWidth,
                TextUtils.TruncateAt.END).toString(), left, titleBaseline, titlePaint);
        textPaint.setTextSize(sp(11));
        textPaint.setColor(MUTED);
        String meta = source.name(row) + " · " + range(programme);
        if (programme.getStartMillis() <= nowMillis && nowMillis < programme.getStopMillis()) {
            meta += " · " + getContext().getString(R.string.guide_live);
        }
        canvas.drawText(TextUtils.ellipsize(meta, textPaint, columnWidth,
                TextUtils.TruncateAt.END).toString(), left, titleBaseline + dp(20), textPaint);

        String description = programme.getDescription();
        if (description.isEmpty()) return;
        descriptionPaint.setTextSize(sp(11));
        float descriptionLeft = left + columnWidth + dp(24);
        int width = (int) (right - descriptionLeft);
        if (width <= 0) return;
        StaticLayout layout = StaticLayout.Builder
                .obtain(description, 0, description.length(), descriptionPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        canvas.save();
        canvas.translate(descriptionLeft, top);
        layout.draw(canvas);
        canvas.restore();
    }

    private String range(EpgProgramme programme) {
        return timeFormat.format(new Date(programme.getStartMillis())) + " – "
                + timeFormat.format(new Date(programme.getStopMillis()));
    }

    private static float xFor(long millis, float gridLeft, float gridWidth, long windowStart) {
        return gridLeft + gridWidth * (millis - windowStart) / (float) EpgGuideNavigator.WINDOW_MILLIS;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}

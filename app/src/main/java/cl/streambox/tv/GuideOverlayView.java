package cl.streambox.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen, remote-friendly electronic programme guide.
 *
 * <p>The guide deliberately owns its rendering and navigation so the activity
 * can keep the media player alive while the remote arrows are focused on the
 * programme grid.</p>
 */
public final class GuideOverlayView extends View {
    private static final long SLOT_MILLIS = 30L * 60L * 1_000L;
    private static final int VISIBLE_SLOTS = 5;
    private static final int MAX_VISIBLE_ROWS = 5;

    private static final int BACKGROUND = Color.rgb(7, 14, 20);
    private static final int PANEL = Color.rgb(13, 24, 34);
    private static final int PANEL_SELECTED = Color.rgb(25, 46, 56);
    private static final int WHITE = Color.rgb(246, 249, 250);
    private static final int MUTED = Color.rgb(164, 181, 188);
    private static final int DIVIDER = Color.rgb(38, 55, 64);
    private static final int CYAN = Color.rgb(54, 224, 213);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final float scaledDensity;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private List<Channel> channels = Collections.emptyList();
    private EpgData epgData = EpgData.empty();
    private int selectedChannelIndex;
    private long selectedTimeMillis;
    private long windowStartMillis;
    private long nowMillis;

    public GuideOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        configure();
    }

    public GuideOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        configure();
    }

    public GuideOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        configure();
    }

    private void configure() {
        setFocusable(false);
        setWillNotDraw(false);
    }

    public void setData(List<Channel> channels, EpgData epgData, int currentChannelIndex, long nowMillis) {
        this.channels = channels == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(channels));
        this.epgData = epgData == null ? EpgData.empty() : epgData;
        this.nowMillis = nowMillis;
        selectedChannelIndex = clampIndex(currentChannelIndex);
        selectedTimeMillis = nowMillis;
        windowStartMillis = floorToSlot(nowMillis) - SLOT_MILLIS;
        invalidate();
    }

    public void setEpgData(EpgData epgData) {
        this.epgData = epgData == null ? EpgData.empty() : epgData;
        invalidate();
    }

    public int getSelectedChannelIndex() {
        return selectedChannelIndex;
    }

    public void moveVertical(int direction) {
        if (channels.isEmpty() || direction == 0) return;
        selectedChannelIndex = (selectedChannelIndex + direction) % channels.size();
        if (selectedChannelIndex < 0) selectedChannelIndex += channels.size();
        invalidate();
    }

    public void moveHorizontal(int direction) {
        if (channels.isEmpty() || direction == 0) return;

        EpgProgramme current = programmeAt(selectedChannelIndex, selectedTimeMillis);
        EpgProgramme target = null;
        if (current != null) {
            List<EpgProgramme> programmes = programmesFor(selectedChannelIndex);
            if (direction > 0) {
                for (EpgProgramme programme : programmes) {
                    if (programme.getStartMillis() >= current.getStopMillis()) {
                        target = programme;
                        break;
                    }
                }
            } else {
                for (EpgProgramme programme : programmes) {
                    if (programme.getStopMillis() <= current.getStartMillis()) {
                        target = programme;
                    } else if (programme.getStartMillis() > current.getStartMillis()) {
                        break;
                    }
                }
            }
        }

        selectedTimeMillis = target == null
                ? selectedTimeMillis + direction * SLOT_MILLIS
                : target.getStartMillis() + 1L;
        ensureSelectedTimeVisible();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        nowMillis = System.currentTimeMillis();
        canvas.drawColor(BACKGROUND);

        drawPiPPlaceholder(canvas);
        drawHeader(canvas);
        drawGuideGrid(canvas);
        drawRemoteHints(canvas);
    }

    private void drawPiPPlaceholder(Canvas canvas) {
        float margin = dp(32);
        float pipWidth = getWidth() * 0.36f;
        float pipHeight = pipWidth * 9f / 16f;
        rect.set(margin, dp(32), margin + pipWidth, dp(32) + pipHeight);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PANEL);
        canvas.drawRoundRect(rect, dp(10), dp(10), paint);
    }

    private void drawHeader(Canvas canvas) {
        float margin = dp(32);
        float detailsLeft = Math.max(getWidth() * 0.43f, dp(420));
        Channel channel = selectedChannel();
        EpgProgramme programme = programmeAt(selectedChannelIndex, selectedTimeMillis);
        String title = programme == null
                ? (channel == null || channel.getGroup().isBlank()
                        ? "Sin programación disponible"
                        : channel.getGroup())
                : programme.getTitle();

        setText(18, CYAN, true);
        canvas.drawText("GUÍA TV", margin, dp(48), paint);

        setText(31, WHITE, true);
        canvas.drawText(fitText(title, getWidth() - detailsLeft - margin), detailsLeft, dp(94), paint);

        setText(15, MUTED, false);
        String channelLabel = channel == null ? "" : channel.getName();
        String timeRange = programme == null
                ? channelLabel
                : timeFormat.format(new Date(programme.getStartMillis()))
                        + " — "
                        + timeFormat.format(new Date(programme.getStopMillis()));
        canvas.drawText(fitText(channelLabel + (timeRange.isBlank() ? "" : "  ·  " + timeRange),
                getWidth() - detailsLeft - margin), detailsLeft, dp(124), paint);

        float progressLeft = detailsLeft;
        float progressRight = getWidth() - margin;
        float progressY = dp(151);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(3));
        paint.setColor(DIVIDER);
        canvas.drawLine(progressLeft, progressY, progressRight, progressY, paint);
        if (programme != null
                && programme.getStartMillis() <= nowMillis
                && nowMillis < programme.getStopMillis()) {
            float progress = (float) (nowMillis - programme.getStartMillis())
                    / Math.max(1L, programme.getStopMillis() - programme.getStartMillis());
            paint.setColor(CYAN);
            canvas.drawLine(progressLeft, progressY,
                    progressLeft + (progressRight - progressLeft) * Math.max(0f, Math.min(1f, progress)),
                    progressY, paint);
        }

        setText(14, WHITE, false);
        String group = channel == null || channel.getGroup().isBlank()
                ? "Emisión en directo"
                : channel.getGroup();
        canvas.drawText(fitText(group, getWidth() - detailsLeft - margin), detailsLeft, dp(188), paint);

        EpgProgramme next = nextProgramme(programme, selectedChannelIndex);
        if (next != null) {
            setText(14, MUTED, false);
            String nextText = "Después  ·  "
                    + timeFormat.format(new Date(next.getStartMillis()))
                    + "  "
                    + next.getTitle();
            canvas.drawText(fitText(nextText, getWidth() - detailsLeft - margin), detailsLeft, dp(224), paint);
        }
    }

    private void drawGuideGrid(Canvas canvas) {
        float margin = dp(32);
        float gridTop = getHeight() * 0.51f;
        float railWidth = Math.max(dp(180), getWidth() * 0.14f);
        float gridLeft = margin + railWidth;
        float gridRight = getWidth() - margin;
        float gridWidth = gridRight - gridLeft;
        int rowCount = Math.min(MAX_VISIBLE_ROWS, channels.size());
        if (rowCount == 0) {
            setText(16, MUTED, false);
            canvas.drawText("No hay canales disponibles", margin, gridTop + dp(52), paint);
            return;
        }

        float rowHeight = Math.max(dp(54),
                Math.min(dp(72), (getHeight() - gridTop - dp(52)) / rowCount));
        int firstRow = firstVisibleRow(rowCount);

        setText(14, MUTED, false);
        paint.setStrokeWidth(dp(1));
        paint.setColor(DIVIDER);
        canvas.drawLine(margin, gridTop, gridRight, gridTop, paint);
        for (int slot = 0; slot <= VISIBLE_SLOTS; slot++) {
            float x = gridLeft + gridWidth * slot / VISIBLE_SLOTS;
            long time = windowStartMillis + slot * SLOT_MILLIS;
            setText(13, MUTED, false);
            canvas.drawText(timeFormat.format(new Date(time)), x + dp(4), gridTop - dp(15), paint);
            canvas.drawLine(x, gridTop - dp(7), x, gridTop, paint);
        }

        float currentTimeX = gridLeft + gridWidth
                * (nowMillis - windowStartMillis) / (SLOT_MILLIS * (float) VISIBLE_SLOTS);
        if (currentTimeX >= gridLeft && currentTimeX <= gridRight) {
            paint.setColor(CYAN);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(currentTimeX, gridTop - dp(12), currentTimeX, gridTop + rowHeight * rowCount, paint);
            PathTriangle.draw(canvas, currentTimeX, gridTop - dp(12), dp(6), CYAN, paint);
        }

        for (int visibleRow = 0; visibleRow < rowCount; visibleRow++) {
            int channelPosition = firstRow + visibleRow;
            if (channelPosition >= channels.size()) break;
            Channel channel = channels.get(channelPosition);
            float rowTop = gridTop + visibleRow * rowHeight;
            float rowBottom = rowTop + rowHeight;
            boolean selectedRow = channelPosition == selectedChannelIndex;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(selectedRow ? Color.rgb(12, 27, 35) : BACKGROUND);
            rect.set(margin, rowTop + dp(1), gridRight, rowBottom);
            canvas.drawRect(rect, paint);
            paint.setColor(DIVIDER);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(margin, rowBottom, gridRight, rowBottom, paint);

            setText(15, selectedRow ? CYAN : WHITE, true);
            canvas.drawText(fitText(channel.getName(), railWidth - dp(68)),
                    margin + dp(48), rowTop + dp(32), paint);
            setText(11, MUTED, false);
            canvas.drawText(initials(channel.getName()), margin + dp(4), rowTop + dp(32), paint);

            List<EpgProgramme> programmes = programmesFor(channelPosition);
            boolean drewProgramme = false;
            for (EpgProgramme programme : programmes) {
                if (programme.getStopMillis() <= windowStartMillis
                        || programme.getStartMillis() >= windowEndMillis()) continue;
                drewProgramme = true;
                float start = Math.max(windowStartMillis, programme.getStartMillis());
                float stop = Math.min(windowEndMillis(), programme.getStopMillis());
                float left = gridLeft + gridWidth
                        * (start - windowStartMillis) / (SLOT_MILLIS * (float) VISIBLE_SLOTS);
                float right = gridLeft + gridWidth
                        * (stop - windowStartMillis) / (SLOT_MILLIS * (float) VISIBLE_SLOTS);
                left = Math.max(gridLeft + dp(2), left);
                right = Math.min(gridRight - dp(2), Math.max(left + dp(26), right));
                boolean selected = selectedRow && sameProgramme(programme,
                        programmeAt(selectedChannelIndex, selectedTimeMillis));

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(selected ? PANEL_SELECTED : PANEL);
                rect.set(left, rowTop + dp(3), right, rowBottom - dp(3));
                canvas.drawRoundRect(rect, dp(5), dp(5), paint);
                if (selected) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    paint.setColor(CYAN);
                    canvas.drawRoundRect(rect, dp(5), dp(5), paint);
                    paint.setStyle(Paint.Style.FILL);
                }

                float textWidth = right - left - dp(20);
                if (textWidth > dp(36)) {
                    setText(14, WHITE, selected);
                    canvas.drawText(fitText(programme.getTitle(), textWidth),
                            left + dp(10), rowTop + dp(28), paint);
                    if (textWidth > dp(128) && rowHeight >= dp(62)) {
                        setText(11, MUTED, false);
                        canvas.drawText(timeFormat.format(new Date(programme.getStartMillis()))
                                        + " — "
                                        + timeFormat.format(new Date(programme.getStopMillis())),
                                left + dp(10), rowTop + dp(49), paint);
                    }
                }
            }
            if (!drewProgramme) {
                setText(12, MUTED, false);
                canvas.drawText("Sin programación", gridLeft + dp(10), rowTop + dp(32), paint);
            }
        }
    }

    private void drawRemoteHints(Canvas canvas) {
        setText(11, MUTED, false);
        float y = getHeight() - dp(16);
        float center = getWidth() * 0.5f;
        canvas.drawText("OK  Seleccionar", center - dp(250), y, paint);
        canvas.drawText("INFO  Detalles", center - dp(62), y, paint);
        canvas.drawText("← →  Tiempo", center + dp(118), y, paint);
    }

    private EpgProgramme programmeAt(int channelPosition, long timeMillis) {
        for (EpgProgramme programme : programmesFor(channelPosition)) {
            if (programme.getStartMillis() > timeMillis) break;
            if (programme.getStartMillis() <= timeMillis && timeMillis < programme.getStopMillis()) {
                return programme;
            }
        }
        return null;
    }

    private EpgProgramme nextProgramme(EpgProgramme current, int channelPosition) {
        long threshold = current == null ? selectedTimeMillis : current.getStopMillis();
        for (EpgProgramme programme : programmesFor(channelPosition)) {
            if (programme.getStartMillis() >= threshold) return programme;
        }
        return null;
    }

    private List<EpgProgramme> programmesFor(int channelPosition) {
        Channel channel = channelPosition >= 0 && channelPosition < channels.size()
                ? channels.get(channelPosition)
                : null;
        return channel == null ? Collections.emptyList() : epgData.getProgrammes(channel.getTvgId());
    }

    private Channel selectedChannel() {
        return selectedChannelIndex >= 0 && selectedChannelIndex < channels.size()
                ? channels.get(selectedChannelIndex)
                : null;
    }

    private int firstVisibleRow(int rowCount) {
        if (channels.size() <= rowCount) return 0;
        int maxFirst = channels.size() - rowCount;
        return Math.max(0, Math.min(maxFirst, selectedChannelIndex - rowCount / 2));
    }

    private long windowEndMillis() {
        return windowStartMillis + SLOT_MILLIS * VISIBLE_SLOTS;
    }

    private void ensureSelectedTimeVisible() {
        long leftBoundary = windowStartMillis + SLOT_MILLIS;
        long rightBoundary = windowEndMillis() - SLOT_MILLIS;
        if (selectedTimeMillis < leftBoundary || selectedTimeMillis >= rightBoundary) {
            windowStartMillis = floorToSlot(selectedTimeMillis) - SLOT_MILLIS;
        }
    }

    private static boolean sameProgramme(EpgProgramme left, EpgProgramme right) {
        return left != null && right != null
                && left.getStartMillis() == right.getStartMillis()
                && left.getStopMillis() == right.getStopMillis()
                && left.getChannelId().equals(right.getChannelId());
    }

    private int clampIndex(int index) {
        if (channels.isEmpty()) return 0;
        return Math.max(0, Math.min(index, channels.size() - 1));
    }

    private static long floorToSlot(long millis) {
        return Math.floorDiv(millis, SLOT_MILLIS) * SLOT_MILLIS;
    }

    private String initials(String value) {
        if (value == null || value.isBlank()) return "TV";
        StringBuilder result = new StringBuilder(2);
        for (String word : value.trim().split("\\s+")) {
            if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0)));
            if (result.length() == 2) break;
        }
        return result.length() == 0 ? "TV" : result.toString();
    }

    private String fitText(String value, float maxWidth) {
        if (value == null) return "";
        if (maxWidth <= 0 || paint.measureText(value) <= maxWidth) return value;
        String ellipsis = "…";
        int end = value.length();
        while (end > 1 && paint.measureText(value, 0, end) + paint.measureText(ellipsis) > maxWidth) {
            end--;
        }
        return end <= 1 ? ellipsis : value.substring(0, end) + ellipsis;
    }

    private void setText(float sizeSp, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(sizeSp * scaledDensity);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    }

    private float dp(float value) {
        return value * density;
    }

    private static final class PathTriangle {
        private static void draw(Canvas canvas, float centerX, float top, float size, int color, Paint paint) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(centerX, top);
            path.lineTo(centerX - size, top - size);
            path.lineTo(centerX + size, top - size);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }
    }
}

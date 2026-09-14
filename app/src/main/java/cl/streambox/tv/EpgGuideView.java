package cl.streambox.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Lightweight TV guide drawn in one surface. The guide never owns a player;
 * MainActivity keeps the existing Media3 instance alive in the PiP window.
 */
public final class EpgGuideView extends View {
    public interface Listener {
        void onCloseGuide();
        void onPlayGuideChannel(int channelIndex);
    }

    private static final long HALF_HOUR_MS = EpgGuideTimeline.HALF_HOUR_MS;
    private static final long GUIDE_WINDOW_MS = EpgGuideTimeline.GUIDE_WINDOW_MS;
    private static final long DAY_NAVIGATION_MS = 24L * 60L * 60L * 1000L;
    private static final int VISIBLE_ROWS = 8;
    private static final int COLOR_PANEL = Color.rgb(14, 27, 42);
    private static final int COLOR_PANEL_ALT = Color.rgb(10, 21, 34);
    private static final int COLOR_SELECTED = Color.rgb(22, 224, 245);
    private static final int COLOR_TEXT = Color.WHITE;
    private static final int COLOR_MUTED = Color.rgb(178, 197, 222);
    private static final int COLOR_ACCENT = Color.rgb(0, 203, 238);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("EEE d", new Locale("es", "CL"));
    private final GuidePreferences preferences;
    private List<Channel> channels = Collections.emptyList();
    private EpgData epgData = EpgData.empty();
    private Map<String, Bitmap> logos = Collections.emptyMap();
    private List<Integer> filteredIndices = Collections.emptyList();
    private List<String> groups = Collections.emptyList();
    private Listener listener;
    private int playingChannelIndex;
    private int focusedChannelPosition;
    private int firstVisibleChannelPosition;
    private int sideIndex;
    private int sideScrollOffset;
    private long windowStartMillis;
    private long focusedTimeMillis;
    private long navigationAnchorMillis;
    private String selectedGroup = "Todos";
    private boolean sidePanelOpen;
    private boolean open;
    private boolean headerFocused;
    private int headerIndex;
    private static final String[] HEADER_ACTIONS = {"Grupos", "Hoy", "Mañana", "Pasado mañana", "Ahora", "Favorito"};

    public EpgGuideView(Context context, AttributeSet attrs) {
        super(context, attrs);
        preferences = new GuidePreferences(context);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setFocusable(true);
        setFocusableInTouchMode(true);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setChannels(List<Channel> channels) {
        this.channels = channels == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(channels));
        rebuildFilters();
        invalidate();
    }

    public void setEpgData(EpgData epgData) {
        this.epgData = epgData == null ? EpgData.empty() : epgData;
        invalidate();
    }

    public void setLogos(Map<String, Bitmap> logos) {
        this.logos = logos == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(logos));
        invalidate();
    }

    public void setPlayingChannelIndex(int index) {
        playingChannelIndex = Math.max(0, index);
        invalidate();
    }

    public boolean isGuideOpen() { return open; }

    public void openGuide(int currentChannelIndex) {
        open = true;
        sidePanelOpen = false;
        sideScrollOffset = 0;
        headerFocused = false;
        headerIndex = 4;
        selectedGroup = "Todos";
        rebuildFilters();
        playingChannelIndex = Math.max(0, Math.min(currentChannelIndex, Math.max(0, channels.size() - 1)));
        focusedChannelPosition = positionForChannel(playingChannelIndex);
        firstVisibleChannelPosition = Math.min(Math.max(0, filteredIndices.size() - VISIBLE_ROWS),
                Math.max(0, focusedChannelPosition - VISIBLE_ROWS / 2));
        long now = System.currentTimeMillis();
        windowStartMillis = floorHalfHour(now);
        focusedTimeMillis = now;
        navigationAnchorMillis = now;
        requestFocus();
        invalidate();
    }

    public void closeGuide() {
        if (!open) return;
        dismissGuide();
        if (listener != null) listener.onCloseGuide();
    }

    /** Hides the guide without notifying the Activity again. */
    public void dismissGuide() {
        open = false;
        sidePanelOpen = false;
        invalidate();
    }

    /** Handles only guide navigation; tuning is explicit via MEDIA_PLAY. */
    public boolean handleKey(KeyEvent event) {
        if (!open || event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int keyCode = event.getKeyCode();
        // The long OK that opens the guide must not immediately tune on its repeats.
        if (event.getRepeatCount() > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER)) return true;
        if (sidePanelOpen) return handleSideKey(keyCode);
        if (headerFocused) return handleHeaderKey(keyCode);
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            closeGuide();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            if (focusedChannelPosition == 0) headerFocused = true;
            else moveChannel(-1);
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            moveChannel(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            moveTime(-HALF_HOUR_MS);
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            moveTime(HALF_HOUR_MS);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            sidePanelOpen = true;
            sideIndex = 0;
            sideScrollOffset = 0;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            toggleFocusedFavorite();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            int channelIndex = focusedChannelIndex();
            if (channelIndex >= 0 && listener != null) listener.onPlayGuideChannel(channelIndex);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            int channelIndex = focusedChannelIndex();
            if (channelIndex >= 0 && listener != null) listener.onPlayGuideChannel(channelIndex);
            return true;
        }
        return false;
    }

    private boolean handleHeaderKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) headerIndex = Math.max(0, headerIndex - 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) headerIndex = Math.min(HEADER_ACTIONS.length - 1, headerIndex + 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_BACK) headerFocused = false;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (headerIndex == 0) {
                sidePanelOpen = true;
                sideIndex = 0;
                sideScrollOffset = 0;
            } else if (headerIndex == 5) toggleFocusedFavorite();
            else {
                long target = headerIndex == 4 ? System.currentTimeMillis()
                        : EpgGuideTimeline.dayOffset(navigationAnchorMillis, headerIndex - 1, TimeZone.getDefault());
                setFocusedTime(target);
            }
        }
        invalidate();
        return true;
    }

    private void setFocusedTime(long target) {
        long lower = navigationAnchorMillis - 2L * DAY_NAVIGATION_MS;
        long upper = EpgGuideTimeline.dayOffset(navigationAnchorMillis, 3, TimeZone.getDefault()) - 1L;
        focusedTimeMillis = Math.max(lower, Math.min(upper, target));
        windowStartMillis = EpgGuideTimeline.visibleWindow(windowStartMillis, focusedTimeMillis);
        invalidate();
    }

    private boolean handleSideKey(int keyCode) {
        int itemCount = groups.size() + 2;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            sideIndex = Math.max(0, sideIndex - 1);
            ensureSideItemVisible();
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            sideIndex = Math.min(itemCount - 1, sideIndex + 1);
            ensureSideItemVisible();
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            sidePanelOpen = false;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            sidePanelOpen = false;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (sideIndex == 0) selectedGroup = "Todos";
            else if (sideIndex == 1) selectedGroup = "Favoritos";
            else selectedGroup = groups.get(sideIndex - 2);
            rebuildFilters();
            focusedChannelPosition = Math.max(0, Math.min(
                    focusedChannelPosition,
                    Math.max(0, filteredIndices.size() - 1)
            ));
            firstVisibleChannelPosition = Math.min(Math.max(0, filteredIndices.size() - VISIBLE_ROWS),
                    Math.max(0, focusedChannelPosition - VISIBLE_ROWS / 2));
            sidePanelOpen = false;
            sideScrollOffset = 0;
            invalidate();
            return true;
        }
        return true;
    }

    private void moveChannel(int delta) {
        if (filteredIndices.isEmpty()) return;
        focusedChannelPosition = Math.max(0, Math.min(
                filteredIndices.size() - 1,
                focusedChannelPosition + delta
        ));
        if (focusedChannelPosition < firstVisibleChannelPosition) {
            firstVisibleChannelPosition = focusedChannelPosition;
        } else if (focusedChannelPosition >= firstVisibleChannelPosition + VISIBLE_ROWS) {
            firstVisibleChannelPosition = focusedChannelPosition - VISIBLE_ROWS + 1;
        }
        invalidate();
    }

    private void moveTime(long delta) {
        int direction = delta < 0L ? -1 : 1;
        Channel channel = focusedChannel();
        setFocusedTime(EpgGuideTimeline.adjacentTime(channel == null ? Collections.emptyList()
                : epgData.getProgrammes(channel.getTvgId()), focusedTimeMillis, direction));
    }

    private void toggleFocusedFavorite() {
        Channel channel = focusedChannel();
        if (channel != null) {
            preferences.toggleFavorite(channel);
            rebuildFilters();
            invalidate();
        }
    }

    private void ensureSideItemVisible() {
        int visibleItems = Math.max(1, (int) ((getHeight() / renderScale() - 210f) / 47f));
        if (sideIndex < sideScrollOffset) {
            sideScrollOffset = sideIndex;
        } else if (sideIndex >= sideScrollOffset + visibleItems) {
            sideScrollOffset = sideIndex - visibleItems + 1;
        }
        int maxOffset = Math.max(0, groups.size() + 2 - visibleItems);
        sideScrollOffset = Math.min(sideScrollOffset, maxOffset);
    }

    private Channel focusedChannel() {
        int index = focusedChannelIndex();
        return index >= 0 && index < channels.size() ? channels.get(index) : null;
    }

    private int focusedChannelIndex() {
        if (focusedChannelPosition < 0 || focusedChannelPosition >= filteredIndices.size()) return -1;
        return filteredIndices.get(focusedChannelPosition);
    }

    private int positionForChannel(int channelIndex) {
        int position = filteredIndices.indexOf(channelIndex);
        return position >= 0 ? position : 0;
    }

    private void rebuildFilters() {
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel channel : channels) {
            if (!AppStrings.isBlank(channel.getGroup())) groupSet.add(channel.getGroup());
        }
        groups = new ArrayList<>(groupSet);
        Collections.sort(groups, String.CASE_INSENSITIVE_ORDER);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            boolean included = "Todos".equals(selectedGroup)
                    || "Favoritos".equals(selectedGroup) && preferences.isFavorite(channel)
                    || selectedGroup.equals(channel.getGroup());
            if (included) indices.add(i);
        }
        filteredIndices = Collections.unmodifiableList(indices);
        focusedChannelPosition = Math.max(0, Math.min(focusedChannelPosition, indices.size() - 1));
        firstVisibleChannelPosition = Math.max(0, Math.min(firstVisibleChannelPosition,
                Math.max(0, indices.size() - VISIBLE_ROWS)));
    }

    private EpgProgramme focusedProgramme() {
        Channel channel = focusedChannel();
        if (channel == null) return null;
        return epgData.findCurrent(channel.getTvgId(), focusedTimeMillis);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!open || channels.isEmpty()) return;
        float scale = renderScale();
        float width = getWidth();
        float height = getHeight();
        RectF pip = pipRect(width, height, scale);

        canvas.drawColor(Color.argb(242, 5, 11, 19));
        // MainActivity resizes the existing PlayerView to this rectangle.
        // Clear only the PiP hole so that video remains visible while the
        // guide itself stays opaque everywhere else.
        canvas.drawRoundRect(pip, 8f * scale, 8f * scale, clearPaint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * scale);
        paint.setColor(Color.argb(235, 22, 224, 245));
        canvas.drawRoundRect(pip, 8f * scale, 8f * scale, paint);
        paint.setStyle(Paint.Style.FILL);

        drawHeader(canvas, scale, pip);
        drawGuideGrid(canvas, scale, width, height);
        drawBottomHints(canvas, scale, height);
        if (sidePanelOpen) drawSidePanel(canvas, scale, height);
    }

    private void drawHeader(Canvas canvas, float scale, RectF pip) {
        float x = contentOffset(scale) + 34f * scale;
        drawText(canvas, "GUÍA", x, 27f * scale, 14f * scale, true, COLOR_ACCENT);
        drawText(canvas, timeFormat.format(new Date(System.currentTimeMillis())),
                getWidth() - 34f * scale, 27f * scale, 14f * scale, false, COLOR_MUTED, true);

        // The live video remains in the existing Media3 PlayerView. The guide
        // only clears its PiP rectangle and draws a lightweight frame around it.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * scale);
        paint.setColor(Color.argb(170, 104, 144, 171));
        canvas.drawRoundRect(pip, 8f * scale, 8f * scale, paint);
        paint.setStyle(Paint.Style.FILL);

        float detailLeft = pip.right + 24f * scale;
        float detailRight = getWidth() - 34f * scale;
        RectF detailPanel = new RectF(detailLeft, pip.top, detailRight, pip.bottom);
        drawPanel(canvas, detailPanel, Color.argb(210, 12, 26, 41), 12f * scale, scale);

        EpgProgramme programme = focusedProgramme();
        Channel channel = focusedChannel();
        float detailWidth = Math.max(120f * scale, detailRight - detailLeft - 34f * scale);
        drawText(canvas, "PROGRAMA SELECCIONADO", detailLeft + 22f * scale,
                pip.top + 29f * scale, 12f * scale, true, COLOR_ACCENT);
        String title = programme == null
                ? (channel == null ? "Guía" : channel.getName())
                : programme.getTitle();
        drawText(canvas, ellipsizeForWidth(title, detailWidth, 30f * scale),
                detailLeft + 22f * scale, pip.top + 72f * scale, 30f * scale, true);
        String channelLabel = channel == null ? "" : channel.getName();
        String range = programme == null ? "" : formatRange(programme);
        drawText(canvas, ellipsizeForWidth(channelLabel + (range.isEmpty() ? "" : "  ·  " + range),
                        detailWidth, 15f * scale),
                detailLeft + 22f * scale, pip.top + 101f * scale, 15f * scale, false, COLOR_MUTED);
        drawText(canvas, "En vivo", detailRight - 22f * scale, pip.top + 29f * scale,
                13f * scale, true, COLOR_ACCENT, true);
        if (programme != null) {
            long duration = Math.max(1L, programme.getStopMillis() - programme.getStartMillis());
            long elapsed = Math.max(0L, Math.min(duration,
                    System.currentTimeMillis() - programme.getStartMillis()));
            float progress = elapsed / (float) duration;
            float barLeft = detailLeft + 22f * scale;
            float barRight = detailRight - 22f * scale;
            float barTop = pip.top + 121f * scale;
            paint.setColor(Color.rgb(35, 57, 76));
            canvas.drawRoundRect(barLeft, barTop, barRight, barTop + 5f * scale,
                    3f * scale, 3f * scale, paint);
            paint.setColor(COLOR_ACCENT);
            canvas.drawRoundRect(barLeft, barTop, barLeft + (barRight - barLeft) * progress,
                    barTop + 5f * scale, 3f * scale, 3f * scale, paint);
        }
        if (programme != null && !AppStrings.isBlank(programme.getDescription())) {
            String[] descriptionLines = descriptionLines(programme.getDescription(), detailWidth, 14f * scale);
            drawText(canvas, descriptionLines[0], detailLeft + 22f * scale,
                    pip.top + 158f * scale, 14f * scale, false, COLOR_MUTED);
            if (!AppStrings.isBlank(descriptionLines[1])) {
                drawText(canvas, descriptionLines[1], detailLeft + 22f * scale,
                        pip.top + 179f * scale, 14f * scale, false, COLOR_MUTED);
            }
        }

        float dateTop = pip.bottom + 48f * scale;
        drawText(canvas, "GUÍA", x, dateTop - 1f * scale, 18f * scale, true);
        for (int i = 0; i < HEADER_ACTIONS.length; i++) {
            drawDayChip(canvas, x + 82f * scale + i * 116f * scale, dateTop,
                    108f * scale,
                    HEADER_ACTIONS[i], headerFocused && !sidePanelOpen && headerIndex == i, scale);
        }
        drawText(canvas, dayFormat.format(new Date(focusedTimeMillis)),
                getWidth() - 34f * scale, dateTop - 1f * scale,
                13f * scale, false, COLOR_MUTED, true);
    }

    private void drawGuideGrid(Canvas canvas, float scale, float width, float height) {
        float gridTop = Math.max(370f * scale, pipBottom(scale) + 110f * scale);
        float channelLeft = contentOffset(scale) + 32f * scale;
        float channelWidth = 270f * scale;
        float timelineLeft = channelLeft + channelWidth;
        float timelineRight = width - 34f * scale;
        float timelineWidth = timelineRight - timelineLeft;
        float rowHeight = Math.min(66f * scale, (height - gridTop - 105f * scale) / VISIBLE_ROWS);
        rowHeight = Math.max(42f * scale, rowHeight);
        drawPanel(canvas, new RectF(channelLeft, gridTop - 44f * scale,
                timelineRight, Math.min(height - 82f * scale,
                        gridTop + rowHeight * VISIBLE_ROWS + 4f * scale)),
                Color.argb(174, 10, 22, 35), 12f * scale, scale);
        drawText(canvas, "CANALES", channelLeft + 12f * scale, gridTop - 18f * scale,
                12f * scale, true, COLOR_MUTED);
        for (int tick = 0; tick <= 6; tick++) {
            float x = timelineLeft + timelineWidth * tick / 6f;
            long time = windowStartMillis + tick * HALF_HOUR_MS;
            paint.setColor(Color.argb(170, 85, 111, 140));
            paint.setStrokeWidth(1f * scale);
            canvas.drawLine(x, gridTop - 28f * scale, x, gridTop + rowHeight * VISIBLE_ROWS, paint);
            drawText(canvas, timeFormat.format(new Date(time)), x + 8f * scale, gridTop - 12f * scale,
                    13f * scale, false, time == floorHalfHour(System.currentTimeMillis()) ? COLOR_SELECTED : COLOR_MUTED);
        }
        float nowX = timelineLeft + timelineWidth * (System.currentTimeMillis() - windowStartMillis) / GUIDE_WINDOW_MS;
        if (nowX >= timelineLeft && nowX <= timelineRight) {
            paint.setColor(COLOR_ACCENT);
            canvas.drawRect(nowX - 1f * scale, gridTop - 30f * scale, nowX + 1f * scale,
                    gridTop + rowHeight * VISIBLE_ROWS, paint);
            drawText(canvas, "AHORA", nowX + 7f * scale, gridTop - 31f * scale,
                    11f * scale, true, COLOR_ACCENT);
        }

        for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
            int position = firstVisibleChannelPosition + visible;
            if (position >= filteredIndices.size()) break;
            int index = filteredIndices.get(position);
            Channel channel = channels.get(index);
            float top = gridTop + visible * rowHeight;
            boolean focused = !headerFocused && !sidePanelOpen && position == focusedChannelPosition;
            paint.setColor(focused ? Color.rgb(24, 50, 66)
                    : (visible % 2 == 0 ? COLOR_PANEL : COLOR_PANEL_ALT));
            canvas.drawRect(channelLeft, top, timelineRight, top + rowHeight - 1f * scale, paint);
            if (index == playingChannelIndex) {
                paint.setColor(COLOR_ACCENT);
                canvas.drawRect(channelLeft, top, channelLeft + 4f * scale, top + rowHeight, paint);
            }
            drawText(canvas, String.format(Locale.ROOT, "%02d", index + 1), channelLeft + 12f * scale,
                    top + rowHeight * .58f, 15f * scale, false, COLOR_MUTED);
            drawLogoOrInitials(canvas, channel, channelLeft + 54f * scale, top + 10f * scale, 50f * scale, rowHeight - 20f * scale, scale);
            drawText(canvas, ellipsizeForWidth(channel.getName(), 126f * scale, 15f * scale), channelLeft + 116f * scale,
                    top + rowHeight * .58f, 15f * scale, focused, COLOR_TEXT);
            if (preferences.isFavorite(channel)) drawText(canvas, "★", channelLeft + 252f * scale,
                    top + rowHeight * .58f, 14f * scale, false, COLOR_ACCENT);

            drawProgrammes(canvas, channel, top, timelineLeft, timelineWidth, rowHeight, scale, focused);
            if (focused) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f * scale);
                paint.setColor(Color.argb(170, 22, 224, 245));
                canvas.drawRect(channelLeft + 1f * scale, top + 1f * scale,
                        timelineRight - 1f * scale, top + rowHeight - 2f * scale, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        if (filteredIndices.isEmpty()) {
            drawText(canvas, "No hay canales en este grupo", timelineLeft,
                    gridTop + rowHeight, 18f * scale, false, COLOR_MUTED);
        }
    }

    private void drawProgrammes(Canvas canvas, Channel channel, float top, float timelineLeft,
                                float timelineWidth, float rowHeight, float scale, boolean rowFocused) {
        for (EpgProgramme programme : epgData.getProgrammes(channel.getTvgId())) {
            if (!EpgGuideTimeline.intersects(
                    programme,
                    windowStartMillis,
                    windowStartMillis + GUIDE_WINDOW_MS
            )) continue;
            float progressStart = Math.max(0f, Math.min(1f,
                    (float) ((programme.getStartMillis() - windowStartMillis)
                            / (double) GUIDE_WINDOW_MS)));
            float progressEnd = Math.max(0f, Math.min(1f,
                    (float) ((programme.getStopMillis() - windowStartMillis)
                            / (double) GUIDE_WINDOW_MS)));
            float left = timelineLeft + timelineWidth * progressStart;
            float right = timelineLeft + timelineWidth * progressEnd;
            if (right - left < 18f * scale) {
                right = Math.min(timelineLeft + timelineWidth, left + 18f * scale);
            }
            boolean selected = rowFocused && programme == focusedProgramme();
            paint.setColor(selected ? COLOR_SELECTED : Color.rgb(27, 43, 63));
            canvas.drawRoundRect(left + 1f * scale, top + 1f * scale, right - 1f * scale,
                    top + rowHeight - 1f * scale, 5f * scale, 5f * scale, paint);
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f * scale);
                paint.setColor(Color.argb(230, 170, 250, 255));
                canvas.drawRoundRect(left + 1f * scale, top + 1f * scale, right - 1f * scale,
                        top + rowHeight - 1f * scale, 5f * scale, 5f * scale, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            int textColor = selected ? Color.rgb(8, 22, 38) : COLOR_TEXT;
            canvas.save();
            canvas.clipRect(left + 7f * scale, top + 1f * scale,
                    Math.max(left + 8f * scale, right - 5f * scale), top + rowHeight - 1f * scale);
            float availableWidth = Math.max(12f * scale, right - left - 18f * scale);
            drawText(canvas, ellipsizeForWidth(programme.getTitle(), availableWidth, 14f * scale),
                    left + 10f * scale, top + rowHeight * .48f, 14f * scale, selected, textColor);
            drawText(canvas, ellipsizeForWidth(formatRange(programme), availableWidth, 11f * scale),
                    left + 10f * scale, top + rowHeight * .77f,
                    11f * scale, false, selected ? Color.rgb(25, 53, 86) : COLOR_MUTED);
            canvas.restore();
        }
    }

    private void drawSidePanel(Canvas canvas, float scale, float height) {
        float panelWidth = 252f * scale;
        paint.setColor(Color.rgb(12, 25, 42));
        canvas.drawRect(0, 0, panelWidth, height, paint);
        drawText(canvas, "VibeM3U", 28f * scale, 70f * scale, 26f * scale, true);
        drawText(canvas, "Canales", 28f * scale, 123f * scale, 14f * scale, false, COLOR_MUTED);
        float top = 172f * scale;
        int visibleItems = Math.max(1, (int) ((height / scale - 210f) / 47f));
        int itemCount = groups.size() + 2;
        for (int item = sideScrollOffset;
             item < Math.min(itemCount, sideScrollOffset + visibleItems);
             item++) {
            float itemTop = top + (item - sideScrollOffset) * 47f * scale;
            if (item == 0) drawSideItem(canvas, item, "▦", "Todos", itemTop, scale);
            else if (item == 1) drawSideItem(canvas, item, "★", "Favoritos", itemTop, scale);
            else drawSideItem(canvas, item, "○", groups.get(item - 2), itemTop, scale);
        }
        drawText(canvas, "▲ / ▼ mover  ·  ◀ / ▶ cerrar", 28f * scale,
                height - 34f * scale, 13f * scale, false, COLOR_MUTED);
    }

    private void drawSideItem(Canvas canvas, int index, String icon, String label, float top, float scale) {
        boolean selected = index == sideIndex;
        if (selected) {
            paint.setColor(Color.rgb(27, 49, 76));
            canvas.drawRect(10f * scale, top - 29f * scale, 242f * scale, top + 14f * scale, paint);
            paint.setColor(COLOR_SELECTED);
            canvas.drawRect(10f * scale, top - 29f * scale, 14f * scale, top + 14f * scale, paint);
        }
        drawText(canvas, icon, 28f * scale, top, 18f * scale, false, selected ? COLOR_SELECTED : COLOR_MUTED);
        drawText(canvas, ellipsize(label, 22, 15f * scale), 64f * scale, top, 15f * scale, selected, COLOR_TEXT);
    }

    private void drawBottomHints(Canvas canvas, float scale, float height) {
        drawText(canvas, "24 h", 38f * scale, height - 66f * scale, 13f * scale, false, COLOR_MUTED);
        float left = 104f * scale;
        float right = getWidth() - 34f * scale;
        paint.setColor(Color.rgb(77, 103, 135));
        canvas.drawRect(left, height - 74f * scale, right, height - 73f * scale, paint);
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(windowStartMillis);
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        long rangeStart = day.getTimeInMillis();
        day.add(java.util.Calendar.DATE, 1);
        long rangeEnd = day.getTimeInMillis();
        float range = Math.max(1f, (float) (rangeEnd - rangeStart));
        float thumbStart = left + (right - left)
                * Math.max(0f, Math.min(1f, (windowStartMillis - rangeStart) / range));
        float thumbEnd = left + (right - left)
                * Math.max(0f, Math.min(1f, (windowStartMillis + GUIDE_WINDOW_MS - rangeStart) / range));
        paint.setColor(COLOR_SELECTED);
        canvas.drawRoundRect(thumbStart, height - 78f * scale,
                Math.max(thumbStart + 12f * scale, thumbEnd), height - 69f * scale,
                5f * scale, 5f * scale, paint);
        drawText(canvas, timeFormat.format(new Date(windowStartMillis)) + " – "
                        + timeFormat.format(new Date(windowStartMillis + GUIDE_WINDOW_MS)),
                left, height - 50f * scale, 11f * scale, false, COLOR_MUTED);
        drawText(canvas, "OK  Ver canal", 42f * scale, height - 16f * scale, 13f * scale, false, COLOR_MUTED);
        drawText(canvas, "▲  Fechas y grupos", 190f * scale, height - 16f * scale, 13f * scale, false, COLOR_MUTED);
        drawText(canvas, "◀ ▶  Cambiar hora", 324f * scale, height - 16f * scale, 13f * scale, false, COLOR_MUTED);
        drawText(canvas, "★  Favorito", 510f * scale, height - 16f * scale, 13f * scale, false, COLOR_MUTED);
    }

    private void drawLogoOrInitials(Canvas canvas, Channel channel, float left, float top,
                                    float width, float height, float scale) {
        Bitmap bitmap = logos.get(PlaybackPreferences.channelIdentity(channel));
        if (bitmap != null && !bitmap.isRecycled()) {
            RectF target = new RectF(left, top, left + width, top + height);
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap, null, target, paint);
            return;
        }
        paint.setColor(Color.rgb(36, 55, 82));
        canvas.drawRoundRect(left, top, left + width, top + height, 6f * scale, 6f * scale, paint);
        drawText(canvas, initials(channel.getName()), left + width / 2f,
                top + height * .62f, 13f * scale, true, COLOR_TEXT, true);
    }

    private void drawDayChip(Canvas canvas, float left, float top, float width, String text,
                             boolean selected, float scale) {
        paint.setColor(selected ? COLOR_SELECTED : Color.rgb(19, 38, 57));
        canvas.drawRoundRect(left, top - 25f * scale, left + width, top + 12f * scale,
                18f * scale, 18f * scale, paint);
        if (!selected) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f * scale);
            paint.setColor(Color.rgb(53, 81, 105));
            canvas.drawRoundRect(left, top - 25f * scale, left + width, top + 12f * scale,
                    18f * scale, 18f * scale, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        drawText(canvas, text, left + width / 2f, top - 1f * scale, 14f * scale, selected,
                selected ? Color.rgb(8, 22, 38) : COLOR_TEXT, true);
    }

    private void drawPanel(Canvas canvas, RectF rect, int color, float radius, float scale) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, scale));
        paint.setColor(Color.argb(110, 93, 135, 164));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawText(Canvas canvas, String text, float x, float baseline, float size,
                          boolean bold) {
        drawText(canvas, text, x, baseline, size, bold, COLOR_TEXT, false);
    }

    private void drawText(Canvas canvas, String text, float x, float baseline, float size,
                          boolean bold, int color) {
        drawText(canvas, text, x, baseline, size, bold, color, false);
    }

    private void drawText(Canvas canvas, String text, float x, float baseline, float size,
                          boolean bold, int color, boolean centered) {
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        paint.setStyle(Paint.Style.FILL);
        if (centered) x -= paint.measureText(text) / 2f;
        canvas.drawText(text == null ? "" : text, x, baseline, paint);
    }

    private String formatRange(EpgProgramme programme) {
        return timeFormat.format(new Date(programme.getStartMillis())) + " – "
                + timeFormat.format(new Date(programme.getStopMillis()));
    }

    private static String initials(String name) {
        if (AppStrings.isBlank(name)) return "TV";
        String trimmed = name.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) return trimmed.substring(0, Math.min(3, trimmed.length())).toUpperCase(Locale.ROOT);
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private static String ellipsize(String value, int maxChars, float textSize) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, Math.max(0, maxChars - 1)).trim() + "…";
    }

    private String ellipsizeForWidth(String value, float maxWidth, float textSize) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty() || maxWidth <= 0f) return "";
        paint.setTextSize(textSize);
        if (paint.measureText(normalized) <= maxWidth) return normalized;
        int count = paint.breakText(normalized, true, maxWidth, null);
        if (count <= 1) return "…";
        String prefix = normalized.substring(0, Math.max(1, count - 1)).trim();
        while (!prefix.isEmpty() && paint.measureText(prefix + "…") > maxWidth) {
            prefix = prefix.substring(0, prefix.length() - 1).trim();
        }
        return prefix + "…";
    }

    private String[] descriptionLines(String value, float width, float size) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(size);
        int maxCharsPerLine = paint.breakText(normalized, true, Math.max(1f, width), null);
        if (maxCharsPerLine == 0) return new String[]{"", ""};
        if (normalized.length() <= maxCharsPerLine) return new String[]{normalized, ""};
        int split = normalized.lastIndexOf(' ', maxCharsPerLine);
        if (split < 1) split = maxCharsPerLine;
        String first = normalized.substring(0, split).trim();
        String remaining = normalized.substring(split).trim();
        return new String[]{first, ellipsizeForWidth(remaining, width, size)};
    }

    private static long floorHalfHour(long time) {
        return EpgGuideTimeline.floorHalfHour(time);
    }

    public RectF pipRect() {
        float scale = renderScale();
        return pipRect(getWidth(), getHeight(), scale);
    }

    private float renderScale() {
        return Math.max(0.01f, Math.min(getWidth() / 1920f, getHeight() / 1080f));
    }

    private float contentOffset(float scale) { return sidePanelOpen ? 252f * scale : 0f; }

    private float pipBottom(float scale) {
        return pipRect(getWidth(), getHeight(), scale).bottom;
    }

    long focusedTimeForTest() { return focusedTimeMillis; }
    long windowStartForTest() { return windowStartMillis; }
    boolean headerFocusedForTest() { return headerFocused; }
    boolean sidePanelOpenForTest() { return sidePanelOpen; }
    int firstVisibleChannelForTest() { return firstVisibleChannelPosition; }

    private static RectF pipRect(float width, float height, float scale) {
        float left = 34f * scale;
        float right = left + 470f * scale;
        float top = 34f * scale;
        float bottom = Math.min(height * .30f, top + 264f * scale);
        return new RectF(left, top, right, bottom);
    }
}

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
    private static final int COLOR_PANEL = Color.rgb(18, 31, 48);
    private static final int COLOR_PANEL_ALT = Color.rgb(14, 26, 41);
    private static final int COLOR_SELECTED = Color.rgb(149, 193, 255);
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
        selectedGroup = "Todos";
        rebuildFilters();
        playingChannelIndex = Math.max(0, Math.min(currentChannelIndex, Math.max(0, channels.size() - 1)));
        focusedChannelPosition = positionForChannel(playingChannelIndex);
        firstVisibleChannelPosition = Math.max(0, focusedChannelPosition - VISIBLE_ROWS / 2);
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
        if (sidePanelOpen) return handleSideKey(keyCode);
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            closeGuide();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            moveChannel(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            moveChannel(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (focusedTimeMillis <= windowStartMillis + 5L * 60L * 1000L) {
                sidePanelOpen = true;
                sideIndex = 0;
            } else {
                moveTime(-HALF_HOUR_MS);
            }
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
            firstVisibleChannelPosition = Math.max(0, focusedChannelPosition - VISIBLE_ROWS / 2);
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
        long target = nextGuideBoundary(direction);
        if (target == focusedTimeMillis) target = focusedTimeMillis + delta;
        focusedTimeMillis = target;
        if (focusedTimeMillis < windowStartMillis + 10L * 60L * 1000L) {
            windowStartMillis -= HALF_HOUR_MS;
        } else if (focusedTimeMillis > windowStartMillis + GUIDE_WINDOW_MS - 10L * 60L * 1000L) {
            windowStartMillis += HALF_HOUR_MS;
        }
        // Keep navigation bounded to two days around the opening point while
        // still allowing the complete 24-hour programme range to be inspected.
        long distance = focusedTimeMillis - navigationAnchorMillis;
        if (Math.abs(distance) > DAY_NAVIGATION_MS * 2L) {
            focusedTimeMillis = navigationAnchorMillis + (direction < 0
                    ? -DAY_NAVIGATION_MS * 2L : DAY_NAVIGATION_MS * 2L);
            windowStartMillis = floorHalfHour(focusedTimeMillis);
        }
        invalidate();
    }

    private long nextGuideBoundary(int direction) {
        Channel channel = focusedChannel();
        if (channel == null) return focusedTimeMillis;
        long best = focusedTimeMillis;
        long distance = Long.MAX_VALUE;
        for (EpgProgramme programme : epgData.getProgrammes(channel.getTvgId())) {
            long[] boundaries = {programme.getStartMillis(), programme.getStopMillis()};
            for (long boundary : boundaries) {
                long difference = boundary - focusedTimeMillis;
                if ((direction > 0 && difference > 60_000L
                        || direction < 0 && difference < -60_000L)
                        && Math.abs(difference) < distance) {
                    distance = Math.abs(difference);
                    best = boundary;
                }
            }
        }
        return best;
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
        int visibleItems = Math.max(1, (getHeight() - 330) / 47);
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
    }

    private EpgProgramme focusedProgramme() {
        Channel channel = focusedChannel();
        if (channel == null) return null;
        EpgProgramme current = epgData.findCurrent(channel.getTvgId(), focusedTimeMillis);
        if (current != null) return current;
        EpgProgramme nearest = null;
        long distance = Long.MAX_VALUE;
        for (EpgProgramme programme : epgData.getProgrammes(channel.getTvgId())) {
            long value = focusedTimeMillis < programme.getStartMillis()
                    ? programme.getStartMillis() - focusedTimeMillis
                    : focusedTimeMillis - programme.getStopMillis();
            if (value >= 0 && value < distance) {
                distance = value;
                nearest = programme;
            }
        }
        return nearest;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!open || channels.isEmpty()) return;
        float scale = Math.max(0.75f, getWidth() / 1920f);
        float width = getWidth();
        float height = getHeight();
        RectF pip = pipRect(width, height, scale);

        canvas.drawColor(Color.argb(232, 7, 14, 24));
        // MainActivity resizes the existing PlayerView to this rectangle.
        // Clear only the PiP hole so that video remains visible while the
        // guide itself stays opaque everywhere else.
        canvas.drawRoundRect(pip, 8f * scale, 8f * scale, clearPaint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * scale);
        paint.setColor(Color.argb(220, 112, 177, 255));
        canvas.drawRoundRect(pip, 8f * scale, 8f * scale, paint);
        paint.setStyle(Paint.Style.FILL);

        drawHeader(canvas, scale, pip);
        drawGuideGrid(canvas, scale, width, height);
        drawBottomHints(canvas, scale, height);
        if (sidePanelOpen) drawSidePanel(canvas, scale, height);
    }

    private void drawHeader(Canvas canvas, float scale, RectF pip) {
        float x = 42f * scale;
        canvas.save();
        canvas.clipRect(0f, 0f, Math.max(0f, pip.left - 24f * scale), 270f * scale);
        paint.setColor(COLOR_MUTED);
        drawText(canvas, "VibeM3U / Guía", x, 40f * scale, 16f * scale, false);
        EpgProgramme programme = focusedProgramme();
        Channel channel = focusedChannel();
        String title = programme == null ? (channel == null ? "Guía" : channel.getName()) : programme.getTitle();
        drawText(canvas, ellipsizeForWidth(title, Math.max(80f, pip.left - x - 24f * scale), 40f * scale),
                x, 92f * scale, 40f * scale, true);
        String metadata = channel == null ? "" : channel.getName() + "  ·  "
                + (programme == null ? "" : formatRange(programme)) + "  ·  "
                + (AppStrings.isBlank(channel.getGroup()) ? "" : channel.getGroup());
        drawText(canvas, ellipsizeForWidth(metadata, Math.max(80f, pip.left - x - 24f * scale), 17f * scale),
                x, 130f * scale, 17f * scale, false, COLOR_MUTED);
        if (programme != null && !AppStrings.isBlank(programme.getDescription())) {
            String[] descriptionLines = descriptionLines(programme.getDescription(), 66);
            drawText(canvas, descriptionLines[0], x, 163f * scale, 17f * scale, false, COLOR_MUTED);
            if (!AppStrings.isBlank(descriptionLines[1])) {
                drawText(canvas, descriptionLines[1], x, 185f * scale, 17f * scale, false, COLOR_MUTED);
            }
        }
        drawDayChip(canvas, x, 225f * scale, 118f * scale, dayFormat.format(new Date(focusedTimeMillis)), true, scale);
        drawDayChip(canvas, x + 130f * scale, 225f * scale, 94f * scale, "Mañana", false, scale);
        drawDayChip(canvas, x + 236f * scale, 225f * scale, 94f * scale, "Pasado", false, scale);
        drawText(canvas, "Ahora", x + 360f * scale, 249f * scale, 16f * scale, false, COLOR_MUTED);
        canvas.restore();
        drawText(canvas, timeFormat.format(new Date(System.currentTimeMillis())), pip.right - 62f * scale, 31f * scale, 18f * scale, false);
        paint.setColor(Color.argb(200, 35, 53, 78));
        canvas.drawRoundRect(pip.left, pip.bottom + 2f * scale, pip.right, pip.bottom + 34f * scale, 0, 0, paint);
        drawText(canvas, "SIGUES VIENDO  ·  " + (playingChannelIndex < channels.size()
                ? channels.get(playingChannelIndex).getName() : ""), pip.left + 14f * scale,
                pip.bottom + 23f * scale, 13f * scale, true);
    }

    private void drawGuideGrid(Canvas canvas, float scale, float width, float height) {
        float gridTop = 310f * scale;
        float channelLeft = 32f * scale;
        float channelWidth = 286f * scale;
        float timelineLeft = channelLeft + channelWidth;
        float timelineRight = width - 34f * scale;
        float timelineWidth = timelineRight - timelineLeft;
        float rowHeight = Math.min(66f * scale, (height - gridTop - 105f * scale) / VISIBLE_ROWS);
        rowHeight = Math.max(42f * scale, rowHeight);
        drawText(canvas, "Canal", channelLeft + 10f * scale, gridTop - 18f * scale, 14f * scale, false, COLOR_MUTED);
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
        }

        for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
            int position = firstVisibleChannelPosition + visible;
            if (position >= filteredIndices.size()) break;
            int index = filteredIndices.get(position);
            Channel channel = channels.get(index);
            float top = gridTop + visible * rowHeight;
            boolean focused = position == focusedChannelPosition;
            paint.setColor(focused ? Color.rgb(32, 52, 78) : (visible % 2 == 0 ? COLOR_PANEL : COLOR_PANEL_ALT));
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
            canvas.drawRect(left + 1f * scale, top + 1f * scale, right - 1f * scale,
                    top + rowHeight - 1f * scale, paint);
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
        drawSideItem(canvas, 0, "▦", "Todos", 172f * scale, scale);
        drawSideItem(canvas, 1, "★", "Favoritos", 226f * scale, scale);
        float top = 280f * scale;
        int visibleItems = Math.max(1, (int) ((height / scale - 330f) / 47f));
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
        long rangeStart = navigationAnchorMillis - DAY_NAVIGATION_MS;
        long rangeEnd = navigationAnchorMillis + DAY_NAVIGATION_MS;
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
        drawText(canvas, "MENU  Grupos", 190f * scale, height - 16f * scale, 13f * scale, false, COLOR_MUTED);
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
        paint.setColor(selected ? COLOR_SELECTED : Color.rgb(29, 46, 68));
        canvas.drawRoundRect(left, top - 25f * scale, left + width, top + 12f * scale,
                18f * scale, 18f * scale, paint);
        drawText(canvas, text, left + width / 2f, top - 1f * scale, 14f * scale, selected,
                selected ? Color.rgb(8, 22, 38) : COLOR_TEXT, true);
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

    private static String[] descriptionLines(String value, int maxCharsPerLine) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxCharsPerLine) return new String[]{normalized, ""};
        int split = normalized.lastIndexOf(' ', maxCharsPerLine);
        if (split < 1) split = maxCharsPerLine;
        String first = normalized.substring(0, split).trim();
        String remaining = normalized.substring(split).trim();
        return new String[]{first, ellipsize(remaining, maxCharsPerLine, 0f)};
    }

    private static long floorHalfHour(long time) {
        return EpgGuideTimeline.floorHalfHour(time);
    }

    public RectF pipRect() {
        float scale = Math.max(0.75f, getWidth() / 1920f);
        return pipRect(getWidth(), getHeight(), scale);
    }

    private static RectF pipRect(float width, float height, float scale) {
        float right = width - 34f * scale;
        float left = right - 470f * scale;
        float top = 34f * scale;
        float bottom = Math.min(height * .30f, top + 264f * scale);
        return new RectF(left, top, right, bottom);
    }
}

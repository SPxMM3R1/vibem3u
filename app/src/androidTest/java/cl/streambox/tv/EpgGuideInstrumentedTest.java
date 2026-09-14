package cl.streambox.tv;

import android.content.Context;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class EpgGuideInstrumentedTest {
    private static void key(EpgGuideView view, int code) {
        assertTrue(view.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, code)));
    }

    @Test public void remoteNavigationAndRenderingAtTvResolutions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (int width : new int[]{1280, 1920}) {
                int height = width * 9 / 16;
                EpgGuideView view = new EpgGuideView(context, null);
                List<Channel> channels = new ArrayList<>();
                List<EpgProgramme> programmes = new ArrayList<>();
                long now = System.currentTimeMillis();
                String[] names = {"TVN", "Mega", "CHV", "Canal 13", "ESPN", "TNT", "Discovery", "24 Horas"};
                for (int i = 0; i < names.length; i++) {
                    String id = "epg-fixture-" + i;
                    channels.add(new Channel(names[i], URI.create("https://example.invalid/" + id),
                            null, "Pruebas", Collections.singletonMap("tvg-id", id)));
                    programmes.add(new EpgProgramme(id, "Programa de prueba " + (i + 1),
                            "Descripción de prueba con tildes: una historia sobre sus protagonistas y los lugares que recorren, con información suficiente para comprobar el recorte en dos líneas sin invadir el vídeo.",
                            now - 60_000L, now + 8L * 3_600_000L));
                }
                final int[] tuned = {0};
                view.setListener(new EpgGuideView.Listener() {
                    @Override public void onCloseGuide() { }
                    @Override public void onPlayGuideChannel(int index) { tuned[0]++; view.dismissGuide(); }
                });
                view.setChannels(channels);
                view.setEpgData(new EpgData(programmes));
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, width, height);
                view.openGuide(0);
                assertTrue(view.handleKey(new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER, 2)));
                assertEquals(0, tuned[0]);
                RectF pip = view.pipRect();
                assertTrue(pip.left > width / 2f);
                assertTrue(pip.right < width && pip.bottom < height / 3f);
                key(view, KeyEvent.KEYCODE_DPAD_RIGHT); // eight-hour programme
                assertVisible(view);
                key(view, KeyEvent.KEYCODE_DPAD_LEFT);
                assertVisible(view);
                assertEquals(0, tuned[0]);
                key(view, KeyEvent.KEYCODE_DPAD_UP);
                assertTrue(view.headerFocusedForTest()); // default action: Ahora
                key(view, KeyEvent.KEYCODE_DPAD_CENTER);
                assertTrue(Math.abs(view.focusedTimeForTest() - System.currentTimeMillis()) < 2000L);
                key(view, KeyEvent.KEYCODE_DPAD_LEFT); // Pasado
                key(view, KeyEvent.KEYCODE_DPAD_CENTER);
                assertTrue(view.focusedTimeForTest() > now + 24L * 3_600_000L);
                assertVisible(view);
                key(view, KeyEvent.KEYCODE_DPAD_RIGHT); // Ahora, then inspect populated header
                key(view, KeyEvent.KEYCODE_DPAD_CENTER);
                for (int i = 0; i < 4; i++) key(view, KeyEvent.KEYCODE_DPAD_LEFT);
                key(view, KeyEvent.KEYCODE_DPAD_CENTER); // Grupos
                assertTrue(view.sidePanelOpenForTest());
                key(view, KeyEvent.KEYCODE_BACK);
                assertFalse(view.sidePanelOpenForTest());
                assertTrue(view.isGuideOpen());
                key(view, KeyEvent.KEYCODE_DPAD_DOWN);
                assertEquals(0, tuned[0]);
                key(view, KeyEvent.KEYCODE_DPAD_CENTER);
                assertEquals(1, tuned[0]);
                assertFalse(view.isGuideOpen());
                view.openGuide(0);
                key(view, KeyEvent.KEYCODE_BACK);
                assertFalse(view.isGuideOpen());
                view.openGuide(7);
                assertEquals("Opening near the end must still display all eight rows", 0,
                        view.firstVisibleChannelForTest());
                view.dismissGuide();
            }
        });
    }

    private static void assertVisible(EpgGuideView view) {
        assertTrue(view.focusedTimeForTest() >= view.windowStartForTest());
        assertTrue(view.focusedTimeForTest() < view.windowStartForTest() + EpgGuideTimeline.GUIDE_WINDOW_MS);
    }

}

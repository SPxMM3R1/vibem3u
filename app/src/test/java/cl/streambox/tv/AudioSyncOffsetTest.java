package cl.streambox.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioSyncOffsetTest {
    @Test
    public void clampsAndLabels() {
        assertEquals(500, AudioSyncOffset.clampMs(900));
        assertEquals(-500, AudioSyncOffset.clampMs(-900));
        assertEquals("0 ms", AudioSyncOffset.label(0));
        assertEquals("+60 ms", AudioSyncOffset.label(60));
        assertEquals("\u221260 ms", AudioSyncOffset.label(-60));
    }

    @Test
    public void keepsLiveValue() {
        AudioSyncOffset.setMs(-40);
        assertEquals(-40, AudioSyncOffset.getMs());
        AudioSyncOffset.setMs(0);
        assertEquals(0, AudioSyncOffset.getMs());
    }
}

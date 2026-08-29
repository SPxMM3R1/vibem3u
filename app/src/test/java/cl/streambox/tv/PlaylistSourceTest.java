package cl.streambox.tv;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public final class PlaylistSourceTest {
    @Test
    public void signatureIsIndependentOfInputOrder() {
        PlaylistSource first = new PlaylistSource(1, "https://one.example/list.m3u");
        PlaylistSource second = new PlaylistSource(2, "https://two.example/list.m3u");

        assertEquals(
                "1=https://one.example/list.m3u|2=https://two.example/list.m3u",
                PlaylistSource.signature(Arrays.asList(second, first))
        );
    }

    @Test
    public void emptySourcesHaveAnEmptySignature() {
        assertEquals("", PlaylistSource.signature(Collections.emptyList()));
    }
}

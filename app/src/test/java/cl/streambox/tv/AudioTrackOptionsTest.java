package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class AudioTrackOptionsTest {
    @Test
    public void labelsUseSpanishLanguageNamesAndChannelLayout() {
        assertEquals("Español · estéreo", AudioTrackOptions.labelFor("es", "", 2, 1));
        assertEquals("Inglés · 5.1", AudioTrackOptions.labelFor("en", "", 6, 2));
        assertEquals("Portugués · mono", AudioTrackOptions.labelFor("pt", "", 1, 3));
        assertEquals("Español", AudioTrackOptions.labelFor("es", "", 0, 1));
    }

    @Test
    public void tracksWithoutLanguageUseTheirNameOrPosition() {
        assertEquals("Comentarios · estéreo", AudioTrackOptions.labelFor("", "Comentarios", 2, 1));
        assertEquals("Pista 2 · estéreo", AudioTrackOptions.labelFor("", "", 2, 2));
    }

    @Test
    public void satelliteAndUndeterminedLanguageCodesAreNormalized() {
        assertEquals("es", AudioTrackOptions.normalizeLanguage("esl"));
        assertEquals("es", AudioTrackOptions.normalizeLanguage("spa"));
        assertEquals("en", AudioTrackOptions.normalizeLanguage("en-US"));
        assertEquals("", AudioTrackOptions.normalizeLanguage("und"));
        assertEquals("", AudioTrackOptions.normalizeLanguage(null));
    }

    @Test
    public void savedTrackIsFoundExactlyOrBySameLanguage() {
        AudioTrackOptions.Option spanish = option(0, "es", "Español · estéreo", 2);
        AudioTrackOptions.Option english = option(1, "en", "Inglés · estéreo", 2);
        List<AudioTrackOptions.Option> options = Arrays.asList(spanish, english);

        assertSame(english, AudioTrackOptions.find(options, AudioTrackOptions.keyFor("en", 2, "")));
        // La transmisión cambió a 5.1: se mantiene el idioma elegido.
        assertSame(english, AudioTrackOptions.find(options, AudioTrackOptions.keyFor("en", 6, "")));
        assertNull(AudioTrackOptions.find(options, AudioTrackOptions.keyFor("fr", 2, "")));
        assertNull(AudioTrackOptions.find(options, ""));
    }

    @Test
    public void repeatedVisibleNamesAreNumbered() {
        List<AudioTrackOptions.Option> options = AudioTrackOptions.withDistinctLabels(Arrays.asList(
                option(0, "es", "Español · estéreo", 2),
                new AudioTrackOptions.Option(1, 0, "es", "Español · estéreo", "es|2|SAP", false),
                option(2, "en", "Inglés · estéreo", 2)));

        assertEquals("Español · estéreo", options.get(0).label);
        assertEquals("Español · estéreo 2", options.get(1).label);
        assertEquals("Inglés · estéreo", options.get(2).label);
    }

    private static AudioTrackOptions.Option option(int group, String language, String label,
            int channels) {
        return new AudioTrackOptions.Option(group, 0, language, label,
                AudioTrackOptions.keyFor(language, channels, ""), group == 0);
    }
}

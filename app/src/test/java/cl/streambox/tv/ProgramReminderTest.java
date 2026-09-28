package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class ProgramReminderTest {
    private static final long NOW = 1_800_000_000_000L;

    private static ProgramReminder reminder(String channel, String title, long startOffsetMinutes) {
        long start = NOW + startOffsetMinutes * 60_000L;
        return new ProgramReminder("tvg:" + channel, channel, title, start, start + 3_600_000L);
    }

    @Test
    public void markingTheSameProgrammeTwiceRemovesIt() {
        List<ProgramReminder> reminders = new ArrayList<>();
        ProgramReminder f1 = reminder("SkySportsF1.uk", "Clasificación", 60);

        assertTrue(ProgramReminder.toggle(reminders, f1));
        assertFalse(ProgramReminder.toggle(reminders, reminder("SkySportsF1.uk", "Otro título", 60)));
        assertTrue(reminders.isEmpty());
    }

    @Test
    public void encodingSurvivesTabsNewlinesAndAccents() {
        List<ProgramReminder> reminders = Arrays.asList(
                new ProgramReminder("unitedkingdom|vavoo_SKY%20F1@TvVoo", "Sky\tSports", "Clasificación\nGP",
                        NOW, NOW + 1_000L),
                reminder("0102", "Mañana · Ñandú", 30)
        );

        assertEquals(reminders, ProgramReminder.decode(ProgramReminder.encode(reminders)));
    }

    @Test
    public void damagedLinesAreIgnored() {
        String text = ProgramReminder.encode(Arrays.asList(reminder("0104", "Noticias", 10)))
                + "\nbasura\n\t\t\tno-numero\t1";

        List<ProgramReminder> decoded = ProgramReminder.decode(text);
        assertEquals(1, decoded.size());
        assertEquals("Noticias", decoded.get(0).title);
    }

    @Test
    public void upcomingDropsExpiredAndSortsByStart() {
        List<ProgramReminder> reminders = Arrays.asList(
                reminder("B", "Tarde", 120),
                reminder("A", "Vencido", -5),
                reminder("C", "Pronto", 15)
        );

        List<ProgramReminder> upcoming = ProgramReminder.upcoming(reminders, NOW);
        assertEquals(2, upcoming.size());
        assertEquals("Pronto", upcoming.get(0).title);
        assertEquals("Tarde", upcoming.get(1).title);
    }

    @Test
    public void findsByIdAndKeepsStopAfterStart() {
        ProgramReminder odd = new ProgramReminder("tvg:x", "X", "T", NOW, NOW - 5L);
        assertEquals(NOW, odd.stopMillis);
        List<ProgramReminder> reminders = Arrays.asList(odd);
        assertEquals(odd, ProgramReminder.find(reminders, odd.id()));
        assertNull(ProgramReminder.find(reminders, "tvg:y@1"));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** 0.6.26: el lector rápido de fechas XMLTV da lo mismo que SimpleDateFormat. */
public class EpgFastTimeTest {
    private static long slow(String pattern, String value) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setLenient(false);
        return format.parse(value, new ParsePosition(0)).getTime();
    }

    @Test
    public void fastPathMatchesSimpleDateFormat() {
        String[] values = {
                "20261021200500 +0000", "20261021172000 -0400", "20260719183000 -0300",
                "20240229235959 +0530", "19991231230000 -1200", "20261021000000 +1400",
        };
        for (String value : values) {
            assertEquals(value, slow("yyyyMMddHHmmss Z", value), EpgParser.parseXmlTvTime(value));
        }
        assertEquals(slow("yyyyMMddHHmm Z", "202610211720 -0400"), EpgParser.parseXmlTvTime("202610211720 -0400"));
        assertEquals(EpgParser.parseXmlTvTime("20260719180000 -0400"),
                EpgParser.parseXmlTvTime("20260719180000 -04:00"));
    }

    @Test
    public void unusualFormatsStillUseTheSlowPath() {
        assertEquals(Long.MIN_VALUE, EpgParser.parseFast("20261021200500"));
        assertEquals(slow("yyyyMMddHHmmss", "20261021200500"), EpgParser.parseXmlTvTime("20261021200500"));
        assertEquals(-1, EpgParser.parseXmlTvTime("no es fecha"));
    }
}

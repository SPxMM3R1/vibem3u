package cl.streambox.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppStringsTest {
    @Test
    public void isBlankMatchesAndroidStringSemantics() {
        assertTrue(AppStrings.isBlank(null));
        assertTrue(AppStrings.isBlank(""));
        assertTrue(AppStrings.isBlank(" \t\n\u2003"));
        assertFalse(AppStrings.isBlank(" canal "));
        assertFalse(AppStrings.isBlank("\u00a0"));
    }
}

package cl.streambox.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateRepositoryTest {
    @Test
    public void detectsNewerSemanticVersions() {
        assertTrue(UpdateRepository.isNewerVersion("0.3.1", "0.3.0"));
        assertTrue(UpdateRepository.isNewerVersion("v1.0.0", "0.9.9"));
        assertTrue(UpdateRepository.isNewerVersion("0.10.0", "0.9.9"));
    }

    @Test
    public void rejectsEqualOrOlderVersions() {
        assertFalse(UpdateRepository.isNewerVersion("v0.3.0", "0.3.0"));
        assertFalse(UpdateRepository.isNewerVersion("0.2.9", "0.3.0"));
        assertFalse(UpdateRepository.isNewerVersion("0.3", "0.3.0"));
    }
}

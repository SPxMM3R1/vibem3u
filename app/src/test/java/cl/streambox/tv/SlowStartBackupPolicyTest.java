package cl.streambox.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SlowStartBackupPolicyTest {
    private static final long START = 10_000L;
    private static final long LATE = START + SlowStartBackupPolicy.WAIT_MS;

    @Test
    public void triesTheBackupWhenThePlaylistHasNotArrivedInTime() {
        assertTrue(SlowStartBackupPolicy.shouldTryBackup(false, false, true, false, START, LATE, -1L));
    }

    @Test
    public void waitsWhileThePlaylistMayStillArrive() {
        assertFalse(SlowStartBackupPolicy.shouldTryBackup(false, false, true, false, START, LATE - 1L, -1L));
        assertFalse(SlowStartBackupPolicy.shouldTryBackup(false, false, true, false, START, LATE, 4_800L));
    }

    @Test
    public void neverForChannelsWithoutBackupSlowRelaysOrRepeatedTries() {
        assertFalse(SlowStartBackupPolicy.shouldTryBackup(false, false, false, false, START, LATE, -1L));
        assertFalse(SlowStartBackupPolicy.shouldTryBackup(false, false, true, true, START, LATE, -1L));
        assertFalse(SlowStartBackupPolicy.shouldTryBackup(true, false, true, false, START, LATE, -1L));
        assertFalse(SlowStartBackupPolicy.shouldTryBackup(false, true, true, false, START, LATE, -1L));
        assertFalse(SlowStartBackupPolicy.shouldTryBackup(false, false, true, false, 0L, LATE, -1L));
    }
}

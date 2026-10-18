package cl.streambox.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ProtocolException;

import org.junit.Test;

public class TsTruncationTest {
    @Test
    public void onlyAlmostCompleteSegmentsAreAccepted() {
        assertTrue(TsAudioFilterDataSource.shouldAcceptTruncation(974_780, 1_011_064));
        assertFalse(TsAudioFilterDataSource.shouldAcceptTruncation(500_000, 1_011_064));
        assertFalse(TsAudioFilterDataSource.shouldAcceptTruncation(974_780, -1));
        assertFalse(TsAudioFilterDataSource.shouldAcceptTruncation(0, 1_000));
    }

    @Test
    public void detectsUnexpectedEndOfStreamInsideWrappedErrors() {
        IOException wrapped = new IOException("lectura",
                new ProtocolException("unexpected end of stream"));
        assertTrue(TsAudioFilterDataSource.isUnexpectedEndOfStream(wrapped));
        assertFalse(TsAudioFilterDataSource.isUnexpectedEndOfStream(
                new IOException("Connection reset")));
    }
}

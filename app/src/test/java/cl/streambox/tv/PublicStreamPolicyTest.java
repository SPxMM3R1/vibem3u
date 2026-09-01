package cl.streambox.tv;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PublicStreamPolicyTest {
    @Test
    public void rejectsPrivateLoopbackLinkLocalAndMetadataAddresses() throws Exception {
        assertFalse(PublicStreamPolicy.isPublic(InetAddress.getByName("127.0.0.1")));
        assertFalse(PublicStreamPolicy.isPublic(InetAddress.getByName("10.0.0.8")));
        assertFalse(PublicStreamPolicy.isPublic(InetAddress.getByName("192.168.1.20")));
        assertFalse(PublicStreamPolicy.isPublic(InetAddress.getByName("169.254.169.254")));
        assertFalse(PublicStreamPolicy.isPublic(InetAddress.getByName("fc00::1")));
    }

    @Test
    public void acceptsPublicIpv4AndIpv6Addresses() throws Exception {
        assertTrue(PublicStreamPolicy.isPublic(InetAddress.getByName("1.1.1.1")));
        assertTrue(PublicStreamPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }
}

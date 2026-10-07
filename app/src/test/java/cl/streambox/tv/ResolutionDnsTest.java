package cl.streambox.tv;

import org.junit.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class ResolutionDnsTest {
    @Test public void parentChildrenAndActualHttpClientShareOnlyAttemptDns() throws Exception {
        int[] calls={0}; okhttp3.Dns dns=host->{calls[0]++;return Collections.singletonList(InetAddress.getByName("1.1.1.1"));};
        ResolutionContext root=new ResolutionContext(2000);
        assertEquals(root.lookupDns("public.invalid",dns),root.child(1000).lookupDns("public.invalid",dns));
        assertEquals(1,calls[0]);
        assertEquals(root.lookupDns("public.invalid",dns),SharedHttpClient.forResolution(root,1000,1000,1000,false).dns().lookup("public.invalid"));
        new ResolutionContext(2000).lookupDns("public.invalid",dns); assertEquals(2,calls[0]);
    }
    @Test public void cachedPrivateAddressesStillFailPublicPolicy() throws Exception {
        ResolutionContext context=new ResolutionContext(2000);
        context.lookupDns("public.invalid",host->Collections.singletonList(InetAddress.getByName("127.0.0.1")));
        try(ResolutionContext.Scope ignored=context.activate()) {
            assertThrows(IOException.class,()->PublicStreamPolicy.requirePublicHttp(URI.create("https://public.invalid/a")));
        }
    }
    @Test public void cancellationAfterNativeDnsReturnsCannotPublishItsResult() throws Exception {
        ResolutionContext context=new ResolutionContext(1000);
        assertThrows(IOException.class,()->context.lookupDns("public.invalid",host->{
            context.cancel(); return Collections.singletonList(InetAddress.getByName("1.1.1.1"));
        }));
    }
    @Test public void stalledNativeDnsCannotExceedParentDeadlineOrBlockNextRequest() throws Exception {
        CountDownLatch release=new CountDownLatch(1); long start=System.nanoTime();
        try {
            assertThrows(IOException.class,()->new ResolutionContext(120).lookupDns("public.invalid",host->{
                try {release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                return Collections.singletonList(InetAddress.getByName("1.1.1.1"));
            }));
            assertTrue((System.nanoTime()-start)/1_000_000L < 1000);
            assertEquals(1,new ResolutionContext(1000).lookupDns("other.invalid",host->Collections.singletonList(InetAddress.getByName("1.1.1.1"))).size());
        } finally {release.countDown();}
    }
    @Test public void emptyAndFailedDnsAreNotCachedAndExceptionsDoNotLeakHostDetails() throws Exception {
        ResolutionContext context=new ResolutionContext(2000); int[] calls={0};
        okhttp3.Dns dns=host->{if(++calls[0]==1)throw new java.net.UnknownHostException("sensitive-host-detail");return Collections.singletonList(InetAddress.getByName("1.1.1.1"));};
        IOException error=assertThrows(IOException.class,()->context.lookupDns("public.invalid",dns));
        assertFalse(error.toString().contains("sensitive"));assertNull(error.getCause());
        assertEquals(1,context.lookupDns("public.invalid",dns).size());assertEquals(2,calls[0]);
        assertThrows(IOException.class,()->context.lookupDns("empty.invalid",host->Collections.emptyList()));
    }
    @Test public void dnsBudgetsDoNotDisableConnectionPoolWithinOrAcrossAttempts() throws Exception {
        try(okhttp3.mockwebserver.MockWebServer server=new okhttp3.mockwebserver.MockWebServer()) {
            for(int i=0;i<3;i++)server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("OK"));
            server.start(); TokenHttpClient client=new TokenHttpClient();
            for(int i=0;i<3;i++) {
                ResolutionContext context=new ResolutionContext(2000);
                try(ResolutionContext.Scope ignored=context.activate()) {
                    assertEquals("OK",client.getText(server.url("/probe").toString(),Collections.emptyMap()));
                }
                assertEquals(i,server.takeRequest(1,TimeUnit.SECONDS).getSequenceNumber());
            }
        }
        assertEquals(SharedHttpClient.get().dns(),SharedHttpClient.forResolution(new ResolutionContext(1000),1000,1000,1000,false).dns());
    }
}

package io.muserver.murp;

import io.muserver.Method;
import io.muserver.MuHandler;
import io.muserver.MuServer;
import okhttp3.Response;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scaffolding.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.MuAssert.assertNotTimedOut;

public class ConnectionPoolTest {

    private static final HttpClient client = new HttpClient(new SslContextFactory.Client(true));
    private MuServer targetServer;
    private MuServer reverseProxyServer;
    private ReverseProxy reverseProxy;

    @BeforeClass
    public static void start() throws Exception {
        client.start();
    }

    @Test
    public void poolSizeDoesNotIncreaseIfNoConcurrentCallsMade() {
        targetServer = httpServer()
            .addHandler(Method.GET, "/",
                (request, response, pathParams) -> Thread.sleep(200))
            .start();

        reverseProxyServer = httpsServer()
            .addHandler(rp(reverseProxy()
                .withIdleConnectionTimeout(1000, TimeUnit.MILLISECONDS)
                .withUriMapper(UriMapper.toDomain(targetServer.uri())))
            )
            .start();

        assertEventually(() -> reverseProxy.poolSize(targetServer.uri()), equalTo(0));
        for (int i = 0; i < 10; i++) {
            try (Response resp = call(request(reverseProxyServer.uri()))) {
                assertThat(resp.code(), is(200));
            }
            assertThat(reverseProxy.poolSize(targetServer.uri()), lessThanOrEqualTo(1));
            assertEventually(() -> reverseProxy.poolSize(targetServer.uri()), equalTo(1));
        }
        assertEventually(() -> reverseProxy.poolSize(targetServer.uri()), equalTo(0));
    }


    @Test
    public void extraConnectionsAreUsedWhenNeeded() {
        targetServer = httpServer()
            .addHandler(Method.GET, "/",
                (request, response, pathParams) -> response.write(StringUtils.randomAsciiStringOfLength(64000)))
            .start();

        reverseProxyServer = httpsServer()
            .addHandler(rp(reverseProxy()
                .withMaxConnectionsPerDestination(2) // TODO: add a third concurrent connection to test that timeout
                .withUriMapper(UriMapper.toDomain(targetServer.uri())))
            )
            .start();

        List<Throwable> errors = new ArrayList<>();
        CountDownLatch request1 = new CountDownLatch(1);
        CountDownLatch request2 = new CountDownLatch(1);
        CountDownLatch responsesComplete = new CountDownLatch(1);
        createConsumingRequest(errors, request1, responsesComplete);
        createConsumingRequest(errors, request2, responsesComplete);
        request2.countDown();
        request1.countDown();
        assertEventually(() -> reverseProxy.poolSize(targetServer.uri()), equalTo(2));

        try (Response resp = call(request(reverseProxyServer.uri()))) {
            assertThat(resp.code(), is(200));
        }
        assertEventually(() -> reverseProxy.poolSize(targetServer.uri()), equalTo(2));

        assertNotTimedOut("Waiting for responses to complete", responsesComplete);
        assertThat(errors, empty());
    }

    private void createConsumingRequest(List<Throwable> errors, CountDownLatch request1, CountDownLatch responsesComplete) {
        new Thread(() -> {
            try (Response resp = call(request(reverseProxyServer.uri()))) {
                assertThat(resp.code(), is(200));
                assertNotTimedOut("Waiting for request1 latch", request1);
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                responsesComplete.countDown();
            }
        }).start();
    }


    private MuHandler rp(ReverseProxyBuilder reverseProxy) {
        this.reverseProxy = reverseProxy.build();
        return this.reverseProxy;
    }


    @After
    public void stopServers() throws IOException {
        if (reverseProxy != null) {
            reverseProxy.close();
        }
        if (targetServer != null) {
            targetServer.stop();
        }
        if (reverseProxyServer != null) {
            reverseProxyServer.stop();
        }
    }

    @AfterClass
    public static void stop() throws Exception {
        client.stop();
    }

}

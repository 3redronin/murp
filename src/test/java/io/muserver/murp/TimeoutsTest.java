package io.muserver.murp;

import io.muserver.Method;
import io.muserver.MuServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.*;

import java.io.EOFException;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class TimeoutsTest {

    private static final HttpClient client = new HttpClient(new SslContextFactory.Client(true));
    private MuServer targetServer;
    private MuServer reverseProxyServer;

    @BeforeClass
    public static void start() throws Exception {
        client.start();
    }

    @Test
    public void totalTimeoutCauses504() throws Exception {
        targetServer = httpServer()
            .addHandler(Method.GET, "/",
                (request, response, pathParams) -> Thread.sleep(200))
            .start();

        reverseProxyServer = httpsServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withTotalTimeout(10, TimeUnit.MILLISECONDS)
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri());
        assertThat(resp.getStatus(), is(504));
        assertThat(resp.getContentAsString(), containsString("504 Gateway Timeout"));
        assertThat(resp.getContentAsString(), containsString("The target service took too long to respond"));
    }

    @Test
    public void idleTimeoutCauses504IfResponseNotStarted() throws Exception {
        targetServer = httpServer()
            .addHandler(Method.GET, "/",
                (request, response, pathParams) -> {
                    Thread.sleep(2000);
                    try {
                        response.sendChunk("Goodbye");
                    } catch (Exception ignored) {
                    }
                })
            .start();

        reverseProxyServer = httpsServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withIdleTimeout(50, TimeUnit.MILLISECONDS)
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri());
        assertThat(resp.getStatus(), is(504));
        assertThat(resp.getContentAsString(), containsString("504 Gateway Timeout"));
        assertThat(resp.getContentAsString(), containsString("The target service took too long to respond"));
        assertThat(resp.getContentAsString(), not(containsString("Goodbye")));
    }


    @Test
    public void idleTimeoutCausesDisconnectionIfResponseAlreadyStarted() throws Exception {
        targetServer = httpServer()
            .addHandler(Method.GET, "/",
                (request, response, pathParams) -> {
                    response.sendChunk("Hello");
                    Thread.sleep(2000);
                    try {
                        response.sendChunk("Goodbye");
                    } catch (Exception ignored) {
                    }
                })
            .start();

        reverseProxyServer = httpsServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withIdleTimeout(50, TimeUnit.MILLISECONDS)
            )
            .start();

        try {
            client.GET(reverseProxyServer.uri());
            Assert.fail("This shouldn't work!");
        } catch (Throwable e) {
            assertThat(e.getCause(), instanceOf(EOFException.class));
        }
    }



    @After
    public void stopServers() {
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

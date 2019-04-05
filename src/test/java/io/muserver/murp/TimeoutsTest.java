package io.muserver.murp;

import io.muserver.Method;
import io.muserver.MuServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class TimeoutsTest {

    private static final HttpClient client = new HttpClient(new SslContextFactory(true));
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
                .withTotalTimeout(1)
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri());
        assertThat(resp.getStatus(), is(504));
        assertThat(resp.getContentAsString(), containsString("504 Gateway Timeout"));
    }

    @Test
    public void idleTimeoutCausesDisconnection() throws Exception {
        targetServer = httpServer()
            .addHandler(Method.GET, "/",
                (request, response, pathParams) -> {
                    response.sendChunk("Hello");
                    Thread.sleep(200);
                    response.sendChunk("Goodbye");
                })
            .start();

        reverseProxyServer = httpsServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withHttpClient(HttpClientBuilder.httpClient().withIdleTimeoutMillis(50))
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri());
        assertThat(resp.getStatus(), isOneOf(504, 200));
        assertThat(resp.getContentAsString(), not(containsString("Goodbye")));
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

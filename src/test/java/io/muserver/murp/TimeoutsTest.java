package io.muserver.murp;

import io.muserver.Method;
import io.muserver.MuServer;
import org.junit.After;
import org.junit.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class TimeoutsTest {

    private static final java.net.http.HttpClient client = HttpClientUtils.createHttpClientBuilder(true)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private MuServer targetServer;
    private MuServer reverseProxyServer;

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

        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(reverseProxyServer.uri())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode(), is(504));
        assertThat(resp.body(), containsString("504 Gateway Timeout"));
    }

    @Test
    public void idleTimeoutCausesDisconnection() throws Exception {
        targetServer = httpServer()
                .addHandler(Method.GET, "/",
                        (request, response, pathParams) -> {
                            response.sendChunk("Hello");
                            Thread.sleep(200);
                            try {
                                response.sendChunk("Goodbye");
                            } catch (Exception ignored) {
                            }
                        })
                .start();

        reverseProxyServer = httpsServer()
                .addHandler(reverseProxy()
                        .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                        .withTotalTimeout(50)
                        .withHttpClient(HttpClientUtils
                                .createHttpClientBuilder(true)
                                .build())
                )
                .start();

        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(reverseProxyServer.uri())
                .build(), HttpResponse.BodyHandlers.ofString());


        assertThat(resp.statusCode(), isOneOf(504, 200));
        assertThat(resp.body(), not(containsString("Goodbye")));
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

}

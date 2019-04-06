package io.muserver.murp;

import io.muserver.*;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ReverseProxyTest {

    private static final HttpClient client = new HttpClient(new SslContextFactory(true));

    @BeforeClass
    public static void start() throws Exception {
        client.start();
    }

    @Test
    public void itCanProxyEverythingToATargetDomain() throws Exception {

        MuServer targetServer = httpServer()
            .addHandler(Method.POST, "/some-text",
                (request, response, pathParams) -> {
                    response.status(201);
                    response.headers().set("X-Something", "a header value");
                    response.headers().set("X-Received", "Foo: " + request.headers().getAll("Foo"));
                    response.write("Hello: " + request.readBodyAsString());
                })
            .start();

        List<String> notifications = new ArrayList<>();
        CountDownLatch notificationAddedLatch = new CountDownLatch(1);

        MuServer reverseProxyServer = httpsServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .addProxyCompleteListener((clientRequest, clientResponse, target, durationMillis) -> {
                    notifications.add("Did " + clientRequest.method() + " " + clientRequest.uri().getPath() + " and returned a " + clientResponse.status() + " from " + target);
                    notificationAddedLatch.countDown();
                })
            )
            .start();

        String requestBody = largeRandomString();

        ContentResponse someText = client.POST(reverseProxyServer.uri().resolve("/some-text"))
            .header("Connection", "Keep-Alive, Foo, Bar")
            .header("foo", "abc")
            .header("Foo", "def")
            .header("Keep-Alive", "timeout=30")
            .content(new StringContentProvider(requestBody))
            .send();
        assertThat(someText.getStatus(), is(201));
        HttpFields headers = someText.getHeaders();
        assertThat(headers.get("X-Something"), is("a header value"));
        assertThat(headers.get("X-Received"), is("Foo: []"));
        assertThat(headers.get("Content-Length"), is(notNullValue()));
        assertThat(headers.get("Via"), is("HTTP/1.1 private"));
        assertThat(headers.get("Forwarded"), is(nullValue()));
        assertThat(someText.getContentAsString(), is("Hello: " + requestBody));
        assertThat("Timed out waiting for notification",
            notificationAddedLatch.await(10, TimeUnit.SECONDS), is(true));
        assertThat("Actual: " + notifications, notifications, contains("Did POST /some-text and returned a 201 from " + targetServer.uri().resolve("/some-text")));
    }

    @Test
    public void viaHeadersCanBeSet() throws Exception {
        MuServer targetServer = httpsServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write(
                "The host header is " + req.headers().get("Host") +
                    " and the Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + ForwardedHeader.toString(req.headers().forwarded())))
            .start();

        MuServer reverseProxyServer = httpServer()
            .addHandler(reverseProxy()
                .withViaName("blardorph")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri().resolve("/"));
        assertThat(resp.getHeaders().getCSV("Via", true), contains("HTTP/1.1 blardorph"));
        String body = resp.getContentAsString();
        assertThat(body, startsWith("The host header is " + reverseProxyServer.uri().getAuthority() +
            " and the Via header is [HTTP/1.1 blardorph] and forwarded is by="));
        assertThat(body, endsWith(";host=\"" + reverseProxyServer.uri().getAuthority() + "\";proto=http"));
    }

    @Test
    public void theHostNameProxyingCanBeTurnedOff() throws InterruptedException, ExecutionException, TimeoutException {
        MuServer targetServer = httpsServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write(
                "The host header is " + req.headers().get("Host") +
                    " and the Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + ForwardedHeader.toString(req.headers().forwarded())))
            .start();

        MuServer reverseProxyServer = httpServer()
            .addHandler(reverseProxy()
                .withViaName("blardorph")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .proxyHostHeader(false)
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri().resolve("/"));
        assertThat(resp.getHeaders().getCSV("Via", true), contains("HTTP/1.1 blardorph"));
        String body = resp.getContentAsString();
        assertThat(body, startsWith("The host header is " + targetServer.uri().getAuthority() +
            " and the Via header is [HTTP/1.1 blardorph] and forwarded is by="));
        assertThat(body, endsWith(";host=\"" + reverseProxyServer.uri().getAuthority() + "\";proto=http"));
    }

    @Test
    public void multipleHopsCanBeMade() throws Exception {
        MuServer targetServer = httpServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                String forwarded = req.headers().forwarded().stream().map(f -> f.proto() + " with host " + f.host()).collect(Collectors.joining(", "));
                resp.write("The Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + forwarded);
            })
            .start();

        MuServer internalRP = httpServer()
            .addHandler(reverseProxy()
                .withViaName("internalrp")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
            )
            .start();

        MuServer externalRP = httpsServer()
            .addHandler(reverseProxy()
                .withViaName("externalrp")
                .withUriMapper(UriMapper.toDomain(internalRP.uri()))
            )
            .start();

        ContentResponse resp = client.GET(externalRP.uri().resolve("/"));
        assertThat(resp.getHeaders().getValuesList("Date"), hasSize(1));
        assertThat(resp.getHeaders().getValuesList("Via"), contains("HTTP/1.1 internalrp, HTTP/1.1 externalrp"));
        assertThat(resp.getContentAsString(), is("The Via header is [HTTP/1.1 externalrp, HTTP/1.1 internalrp]" +
            " and forwarded is https with host " + externalRP.uri().getAuthority() + ", http with host "
            + externalRP.uri().getAuthority()));
    }

    @Test
    public void proxyingCanBeIntercepted() throws Exception {
        MuServer targetServer = httpServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                resp.headers().add("X-Added-By-Target", "Boo");
                resp.write(
                    "X-Blocked = " + req.headers().get("X-Blocked") + ", X-Added = " + req.headers().get("X-Added")
                );
            })
            .start();

        MuServer reverseProxyServer = httpServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withRequestInterceptor(new RequestInterceptor() {
                    @Override
                    public void intercept(MuRequest clientRequest, Request targetRequest) {
                        clientRequest.attribute("blah", "blah-blah-blah");
                        targetRequest.header("X-Blocked", null);
                        targetRequest.header("X-Added", "I was added");
                    }
                })
                .withResponseInterceptor(new ResponseInterceptor() {
                    @Override
                    public void intercept(MuRequest clientRequest, Request targetRequest, Response targetResponse, MuResponse clientResponse) {
                        clientResponse.status(400);
                        Headers headers = clientResponse.headers();
                        headers.set("X-Blah", clientRequest.attribute("blah"));
                        headers.set("X-Added-By-Resp", "Added-by-resp");
                        headers.remove("X-Added-By-Target");
                    }
                })
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri().resolve("/"));
        assertThat(resp.getStatus(), is(400));
        assertThat(resp.getHeaders().getValuesList("X-Added-By-Resp"), contains("Added-by-resp"));
        assertThat(resp.getHeaders().getValuesList("X-Added-By-Target"), empty());
        String body = resp.getContentAsString();
        assertThat(body, equalTo("X-Blocked = null, X-Added = I was added"));
    }


    private String largeRandomString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append(UUID.randomUUID()).append(" ");
        }
        return sb.toString();
    }

    @AfterClass
    public static void stop() throws Exception {
        client.stop();
    }

}

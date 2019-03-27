package io.muserver.murp;

import io.muserver.ForwardedHeader;
import io.muserver.Method;
import io.muserver.MuServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;
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

        MuServer targetServer = httpsServer()
            .addHandler(Method.POST, "/some-text",
                (request, response, pathParams) -> {
                    response.status(201);
                    response.headers().set("X-Something", "a header value");
                    response.headers().set("X-Received", "Foo: " + request.headers().getAll("Foo"));
                    response.write("Hello: " + request.readBodyAsString());
                })
            .start();


        MuServer reverseProxyServer = httpsServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
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
    }

    @Test
    public void viaHeadersCanBeSet() throws Exception {
        MuServer targetServer = httpsServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("The Via header is "
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
        assertThat(body, startsWith("The Via header is [HTTP/1.1 blardorph] and forwarded is by="));
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
        assertThat(resp.getHeaders().getValuesList("Via"), contains("HTTP/1.1 internalrp", "HTTP/1.1 externalrp"));
        assertThat(resp.getContentAsString(), is("The Via header is [HTTP/1.1 externalrp, HTTP/1.1 internalrp]" +
            " and forwarded is https with host " + externalRP.uri().getAuthority() + ", http with host "
            + externalRP.uri().getAuthority()));
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

import io.muserver.MuServer;
import io.muserver.murp.Murp;
import io.muserver.murp.ReverseProxyBuilder;

import java.net.URI;

import static io.muserver.MuServerBuilder.muServer;

public class Example {

    public static void main(String[] args) {

        // This creates a proxy of the MDN docs

        MuServer server = muServer()
            .withHttpPort(12080)
            .withHttpsPort(12443)
            .addHandler(
                ReverseProxyBuilder.reverseProxy()
                    .withUriMapper(request -> {
                        String pathAndQuery = Murp.pathAndQuery(request.uri());
                        return URI.create("https://developer.mozilla.org").resolve(pathAndQuery);
                    })
                    .proxyHostHeader(false)
            )
            .start();

        System.out.println("Load " + server.httpUri() + " or " + server.httpsUri() + " to proxy to the target");

    }
}

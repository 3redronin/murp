import io.muserver.MuServer;
import io.muserver.murp.Murp;
import io.muserver.murp.ReverseProxyBuilder;

import java.net.URI;

import static io.muserver.MuServerBuilder.muServer;

public class Example {

    public static void main(String[] args) {
        MuServer server = muServer()
            .withHttpPort(12000)
            .addHandler(
                ReverseProxyBuilder.reverseProxy()
                    .withUriMapper(request -> {
                        String pathAndQuery = Murp.pathAndQuery(request.uri());
                        return URI.create("https://developer.mozilla.org").resolve(pathAndQuery);
                    })
            )
            .start();

        System.out.println("Load " + server.uri() + " to proxy to the target");

    }
}

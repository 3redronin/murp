import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.SsePublisher;
import io.muserver.handlers.ResourceHandlerBuilder;
import io.muserver.murp.Murp;
import io.muserver.murp.ReverseProxyBuilder;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.muServer;

public class ManualTest {

    public static void main(String[] args) {

        MuServer target = httpServer()
                .withHttpPort(14080)
                .withHttpsPort(14443)
                .addHandler(Method.GET, "/sse", (request, response, pathParams) -> {
                    SsePublisher sse = SsePublisher.start(request, response);
                    for (int i = 0; i < 10000; i++) {
                        sse.send("This is message " + i + "\n");
                        Thread.sleep(5000);
                    }
                    sse.close();
                })
                .addHandler(Method.GET, "/hi", (request, response, pathParams) -> response.write("Hi"))
                .addHandler(ResourceHandlerBuilder.fileHandler(".").withDirectoryListing(true))
                .start();

        System.out.println("target started at " + target.uri());

        MuServer server = muServer()
                .withHttpPort(13080)
                .withHttpsPort(13443)
                .addHandler(
                        ReverseProxyBuilder.reverseProxy()
                                .addProxyCompleteListener((clientRequest, clientResponse, targetUri, durationMillis) -> {
                                    System.out.println("Completed " + clientRequest + " in " + durationMillis + "ms");
                                })
                                .withUriMapper(request -> {
                                    String pathAndQuery = Murp.pathAndQuery(request.uri());
                                    return target.uri().resolve(pathAndQuery);
                                })
                                .proxyHostHeader(false)
                )
                .start();

        System.out.println("Load " + server.httpUri() + " or " + server.httpsUri() + " to proxy to the target");

    }
}

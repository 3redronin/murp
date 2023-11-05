package io.muserver.murp;

import io.muserver.MuRequest;

import java.net.URI;

/**
 * A function that maps an incoming request to a target URI.
 *
 * @author Daniel Flower
 * @version 1.0
 */
public interface UriMapper {

    /**
     * Gets a URI to proxy to based on the given request.
     *
     * @param request The client request to potentially proxy.
     * @return A URI if this request should be proxied; otherwise null.
     * @throws java.lang.Exception Unhandled exceptions will result in an HTTP 500 error being sent to the client
     */
    URI mapFrom(MuRequest request) throws Exception;

    /**
     * Creates a mapper that directs all requests to a new target domain.
     *
     * @param targetDomain The target URI to send proxied requests to. Any path or query strings will be ignored.
     * @return Returns a URI mapper that can be passed to {@link io.muserver.murp.ReverseProxyBuilder#withUriMapper(UriMapper)}
     */
    static UriMapper toDomain(URI targetDomain) {
        return request -> {
            String pathAndQuery = Murp.pathAndQuery(request.uri());
            return targetDomain.resolve(pathAndQuery);
        };
    }

}

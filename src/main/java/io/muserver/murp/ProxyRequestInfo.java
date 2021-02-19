package io.muserver.murp;

import io.muserver.Headers;
import io.muserver.Method;
import io.muserver.MuRequest;

import java.net.URI;

/**
 * Data that is passed to {@link ProxyRequestInterceptor#intercept(ProxyRequestInfo)}
 */
public interface ProxyRequestInfo {

    /**
     * <p>Gets the request that was sent to the client that is to be proxied to the {@link #targetUri()}.</p>
     * <p>Note that changing anything here has no effect, however if you want to pass data from here to a
     * {@link ProxyResponseInterceptor} you can use {@link MuRequest#attribute(String, Object)} to store state.</p>
     * @return The original request from the client.
     */
    MuRequest clientRequest();

    /**
     * @return The headers that will be sent in the request to the target. These can be modified by the interceptor.
     */
    Headers targetRequestHeaders();

    /**
     * @return The HTTP method that will be used when requesting the target
     */
    Method method();

    /**
     * @return The URI that the request will be sent to
     */
    URI targetUri();

}

class ProxyRequestInfoImpl implements ProxyRequestInfo {

    private final MuRequest request;
    private final Headers headers;
    private final URI target;

    ProxyRequestInfoImpl(MuRequest request, Headers headers, URI target) {
        this.request = request;
        this.headers = headers;
        this.target = target;
    }

    @Override
    public MuRequest clientRequest() {
        return request;
    }

    @Override
    public Headers targetRequestHeaders() {
        return headers;
    }

    @Override
    public Method method() {
        return request.method();
    }

    @Override
    public URI targetUri() {
        return target;
    }
}

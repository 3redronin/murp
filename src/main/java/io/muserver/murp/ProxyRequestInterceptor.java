package io.muserver.murp;

/**
 * A hook for intercepting requests before they are sent to the target server.
 */
public interface ProxyRequestInterceptor {

    /**
     * This function is called after the reverse proxy receives a request to proxy, but before it is sent to the target,
     * allowing you to modifying the target request headers.
     * <p>Note that the request body of the client request is not available for inspection and cannot be changed as the raw bytes
     * will be streamed asynchronously to the target.</p>
     *
     * @param info Contains information about the request being proxied
     * @throws Exception Any unhandled exceptions will cause 500 errors
     */
    void intercept(ProxyRequestInfo info) throws Exception;

}

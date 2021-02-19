package io.muserver.murp;

/**
 * A hook for intercepting responses before they are sent back to the client.
 */
public interface ProxyResponseInterceptor {

    /**
     * This function is called after the response from the target is received, and the response headers for the
     * client are set. One use case is to add or remove headers or even response codes sent to the client.
     * <p>Note that the response body is not available for inspection and cannot be changed as the raw bytes
     * will be streamed asynchronously to the client.</p>
     * @param info Information about the response
     * @throws Exception Any unhandled exceptions will be logged but will not stop the response being sent.
     */
    void intercept(ProxyResponseInfo info) throws Exception;

}

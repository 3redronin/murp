package io.muserver.murp;

import io.muserver.MuRequest;

import java.net.http.HttpRequest;

/**
 * A hook for intercepting requests before they are sent to the target server.
 *
 * @author Daniel Flower
 * @version 1.0
 */
public interface RequestInterceptor {

    /**
     * This function is called after Murp prepared the request object to the target server, but before it is sent,
     * allowing you to modify request headers.
     * <p>Note that the request body is not available for inspection and cannot be changed as the raw bytes
     * will be streamed asynchronously to the target.</p>
     *
     * @param clientRequest The original request from the client. Note that changing anything here has no effect,
     *                      however if you want to pass data from here to a {@link io.muserver.murp.ResponseInterceptor} you can
     *                      use {@link io.muserver.MuRequest#attribute(String, Object)} to store state.
     * @param targetRequestBuilder the request builder for intercepting the request which sending to target server.
     * @throws java.lang.Exception Any unhandled exceptions will cause 500 errors
     */
    void intercept(MuRequest clientRequest, HttpRequest.Builder targetRequestBuilder) throws Exception;

}

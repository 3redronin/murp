package io.muserver.murp;

import io.muserver.MuRequest;
import org.eclipse.jetty.client.api.Request;

/**
 * A hook for intercepting requests before they are sent to the target server.
 */
public interface RequestInterceptor {

    /**
     * This function is called after Murp prepared the request object to the target server, but before it is sent,
     * allowing you to modifying request headers.
     * <p>Note that the request body is not available for inspection and cannot be changed as the raw bytes
     * will be streamed asynchronously to the target.</p>
     * @param clientRequest The original request from the client. Note that changing anything here has no effect,
     *                      however if you want to pass data from here to a {@link ResponseInterceptor} you can
     *                      use {@link MuRequest#attribute(String, Object)} to store state.
     * @param targetRequest The as-yet unsent Jetty request that will be sent to the target server.
     * @throws Exception Any unhandled exceptions will cause 500 errors
     */
    void intercept(MuRequest clientRequest, Request targetRequest) throws Exception;

}

package io.muserver.murp;

import io.muserver.MuRequest;
import io.muserver.MuResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

/**
 * A hook for intercepting responses before they are sent back to the client.
 */
public interface ResponseInterceptor {

    /**
     * This function is called after the response from the target is received, and the response headers for the
     * client are called. One use case is to add or remove headers or even response codes sent to the client.
     * <p>Note that the response body is not available for inspection and cannot be changed as the raw bytes
     * will be streamed asynchronously to the client.</p>
     *
     * @param clientRequest  The original request from the client.
     * @param targetRequest  The Jetty request that was sent to the target.
     * @param targetResponse The response received from the target server.
     * @param clientResponse The as-yet unsent response to the client. You can modify the response code, content
     *                       type and other headers, however you cannot alter the response body.
     * @throws Exception Any unhandled exceptions will be logged but will not stop the response being sent.
     */
    void intercept(MuRequest clientRequest, Request targetRequest, Response targetResponse, MuResponse clientResponse) throws Exception;

}

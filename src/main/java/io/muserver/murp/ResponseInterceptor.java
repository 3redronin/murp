package io.muserver.murp;

import io.muserver.MuRequest;
import io.muserver.MuResponse;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * A hook for intercepting responses before they are sent back to the client.
 *
 * @author Daniel Flower
 * @version 1.0
 */
public interface ResponseInterceptor {

    /**
     * This function is called after the response from the target is received, and the response headers for the
     * client are called. One use case is to add or remove headers or even response codes sent to the client.
     * <p>Note that the response body is not available for inspection and cannot be changed as the raw bytes
     * will be streamed asynchronously to the client.</p>
     *
     * @param clientRequest  The original request from the client.
     * @param targetRequest  The request that was sent to the target.
     * @param targetResponseInfo The responseInfo received from the target server, headers info is available.
     * @param clientResponse The as-yet unsent response to the client. You can modify the response code, content
     *                       type and other headers, however you cannot alter the response body.
     * @throws java.lang.Exception Any unhandled exceptions will be logged but will not stop the response being sent.
     */
    void intercept(MuRequest clientRequest, HttpRequest targetRequest, HttpResponse.ResponseInfo targetResponseInfo, MuResponse clientResponse) throws Exception;

}

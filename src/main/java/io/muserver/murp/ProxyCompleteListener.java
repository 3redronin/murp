package io.muserver.murp;

import io.muserver.MuRequest;
import io.muserver.MuResponse;

import java.net.URI;

/**
 * A listener for when proxied requests complete
 *
 * @author Daniel Flower
 * @version 1.0
 */
public interface ProxyCompleteListener {

    /**
     * Called after the response has been sent to the client, whether successful or not
     *
     * @param clientRequest The original request from the client
     * @param clientResponse The response sent to the client
     * @param targetUri The URI that this request was proxied to
     * @param durationMillis The time in millis from when the reverse proxy received the request until the client request was sent.
     * @throws java.lang.Exception Any exceptions will be logged and ignored
     */
    void onComplete(MuRequest clientRequest, MuResponse clientResponse, URI targetUri, long durationMillis) throws Exception;

}

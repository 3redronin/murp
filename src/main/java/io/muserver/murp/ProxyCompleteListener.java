package io.muserver.murp;

/**
 * A listener for when proxied requests complete
 */
public interface ProxyCompleteListener {

    /**
     * Called after the response has been sent to the client, whether successful or not
     * @param info Information about the completed exchange
     * @throws Exception Any exceptions will be logged and ignored
     */
    void onComplete(ProxyCompleteInfo info) throws Exception;

}

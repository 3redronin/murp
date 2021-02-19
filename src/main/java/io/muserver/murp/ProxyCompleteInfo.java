package io.muserver.murp;

import io.muserver.MuRequest;
import io.muserver.MuResponse;

import java.net.URI;

/**
 * Information passed to {@link ProxyCompleteListener#onComplete(ProxyCompleteInfo)}
 */
public interface ProxyCompleteInfo {

    /**
     * @return The original request from the client
     */
    MuRequest clientRequest();

    /**
     * @return The response sent to the client
     */
    MuResponse clientResponse();

    /**
     * @return The URI that this request was proxied to
     */
    URI targetUri();

    /**
     * @return The time in millis from when the reverse proxy received the request until the client request was sent.
     */
    long durationMillis();

}

class ProxyCompleteInfoImpl implements ProxyCompleteInfo {

    private final ProxyExchange exchange;
    private final long duration;

    public ProxyCompleteInfoImpl(ProxyExchange exchange, long duration) {
        this.exchange = exchange;
        this.duration = duration;
    }

    @Override
    public MuRequest clientRequest() {
        return exchange.clientReq;
    }

    @Override
    public MuResponse clientResponse() {
        return exchange.clientResp;
    }

    @Override
    public URI targetUri() {
        return exchange.target;
    }

    @Override
    public long durationMillis() {
        return duration;
    }
}
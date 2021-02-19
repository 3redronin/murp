package io.muserver.murp;

import io.muserver.MuRequest;
import io.muserver.MuResponse;

/**
 * Data that is passed to {@link ProxyResponseInterceptor#intercept(ProxyResponseInfo)}
 */
public interface ProxyResponseInfo {

    /**
     * @return The original request from the client.
     */
    MuRequest clientRequest();

    /**
     * @return The as-yet unsent response to the client. You can modify the response code, content
     * type and other headers, however you cannot alter the response body.
     */
    MuResponse clientResponse();

}

class ProxyResponseInfoImpl implements ProxyResponseInfo {

    private final MuRequest req;
    private final MuResponse resp;

    ProxyResponseInfoImpl(MuRequest req, MuResponse resp) {
        this.req = req;
        this.resp = resp;
    }

    @Override
    public MuRequest clientRequest() {
        return req;
    }

    @Override
    public MuResponse clientResponse() {
        return resp;
    }
}

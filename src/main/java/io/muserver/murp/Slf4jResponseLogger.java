package io.muserver.murp;

import io.muserver.MuRequest;
import io.muserver.MuResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * A listener that logs the results of proxying to slf4j which can be added with {@link ReverseProxyBuilder#addProxyCompleteListener(ProxyCompleteListener)}
 */
public class Slf4jResponseLogger implements ProxyCompleteListener {
    private static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);

    @Override
    public void onComplete(MuRequest clientRequest, MuResponse clientResponse, URI targetUri, long durationMillis) {
        log.info("Proxied " + clientRequest + " to " + targetUri + " and returned " + clientResponse.status() + " in " + durationMillis + "ms");
    }
}

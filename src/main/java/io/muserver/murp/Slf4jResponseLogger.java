package io.muserver.murp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A listener that logs the results of proxying to slf4j which can be added with {@link ReverseProxyBuilder#addProxyCompleteListener(ProxyCompleteListener)}
 */
public class Slf4jResponseLogger implements ProxyCompleteListener {
    private static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);

    @Override
    public void onComplete(ProxyCompleteInfo info) {
        log.info("Proxied " + info.clientRequest() + " to " + info.targetUri() + " and returned " + info.clientResponse().status() + " in " + info.durationMillis() + "ms");
    }
}

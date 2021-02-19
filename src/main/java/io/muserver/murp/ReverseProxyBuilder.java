package io.muserver.murp;

import io.muserver.MuException;
import io.muserver.MuHandler;
import io.muserver.MuHandlerBuilder;
import io.muserver.Mutils;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyList;

/**
 * A builder for creating a reverse proxy, which is a {@link MuHandler} that can be added to a Mu Server.
 */
public class ReverseProxyBuilder implements MuHandlerBuilder<ReverseProxy>, Cloneable {

    private String viaName = "private";
    private UriMapper uriMapper;
    private boolean sendLegacyForwardedHeaders;
    private boolean discardClientForwardedHeaders;
    private long totalTimeoutInMillis = TimeUnit.MINUTES.toMillis(5);
    private long idleTimeoutInMillis = TimeUnit.MINUTES.toMillis(2);
    private long idleConnectionTimeoutInMillis = TimeUnit.MINUTES.toMillis(5);
    private List<ProxyCompleteListener> proxyCompleteListeners;
    private final Set<String> doNotProxyHeaders = new HashSet<>();
    private ProxyRequestInterceptor requestInterceptor;
    private ProxyResponseInterceptor responseInterceptor;

    /**
     * The name to add as the <code>Via</code> header, which defaults to <code>private</code>.
     * @param viaName The name to add to the <code>Via</code> header.
     * @return This builder
     */
    public ReverseProxyBuilder withViaName(String viaName) {
        Mutils.notNull("viaName", viaName);
        this.viaName = viaName;
        return this;
    }

    /**
     * Required value. Sets the mapper to use for creating target URIs.
     * <p>If you want to proxy all requests to a single destination, consider using {@link UriMapper#toDomain(URI)}</p>
     * <p>If the mapper function returns null, then the handler will not proxy the request and the next handler in the
     * chain will be invoked (or a 404 will be returned if there are no further handlers that can handle the request).</p>
     * @param uriMapper A mapper that creates a target URI based on a client request.
     * @return This builder
     */
    public ReverseProxyBuilder withUriMapper(UriMapper uriMapper) {
        this.uriMapper = uriMapper;
        return this;
    }

    /**
     * Murp always sends <code>Forwarded</code> headers, however by default does not send the
     * non-standard <code>X-Forwarded-*</code> headers. Set this to <code>true</code> to enable
     * these legacy headers for older clients that rely on them.
     * @param sendLegacyForwardedHeaders <code>true</code> to forward headers such as <code>X-Forwarded-Host</code>; otherwise <code>false</code>
     * @return This builder
     */
    public ReverseProxyBuilder sendLegacyForwardedHeaders(boolean sendLegacyForwardedHeaders) {
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        return this;
    }

    /**
     * <p>Specifies whether or not to send the original <code>Host</code> header to the target server.</p>
     * <p>Reverse proxies are generally supposed to forward the original <code>Host</code> header to target
     * servers, however there are cases (particularly where you are proxying to HTTPS servers) that the
     * Host needs to match the Host of the SSL certificate (in which case you may see SNI-related errors).</p>
     * @param sendHostToTarget If <code>true</code> (which is the default) the <code>Host</code> request
     *                         header will be sent to the target; if <code>false</code> then the host header
     *                         will be based on the target's URL.
     * @return This builder
     */
    public ReverseProxyBuilder proxyHostHeader(boolean sendHostToTarget) {
        if (sendHostToTarget) {
            doNotProxyHeaders.remove("host");
        } else {
            doNotProxyHeaders.add("host");
        }
        return this;
    }

    /**
     * If true, then any <code>Forwarded</code> or <code>X-Forwarded-*</code> headers that are sent
     * from the client to this reverse proxy will be dropped (defaults to false). Set this to <code>true</code>
     * if you do not trust the client.
     * @param discardClientForwardedHeaders <code>true</code> to ignore Forwarded headers from the client; otherwise <code>false</code>
     * @return This builder
     */
    public ReverseProxyBuilder discardClientForwardedHeaders(boolean discardClientForwardedHeaders) {
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        return this;
    }

    /**
     * Sets the total request timeout in millis for a proxied request.
     * <p>If the full proxy request and response is not completed in this time, then a <code>504 Gateway Timeout</code>
     * will be sent to the client (if the response hasn't already started) and the connection will be closed.</p>
     * <p>Defaults to 5 minutes.</p>
     * @param totalTimeout The allowed time for a request.
     * @param unit The timeout unit.
     * @return This builder
     */
    public ReverseProxyBuilder withTotalTimeout(long totalTimeout, TimeUnit unit) {
        this.totalTimeoutInMillis = unit.toMillis(totalTimeout);
        return this;
    }

    /**
     * Sets the idle request timeout in millis for a proxied request.
     * <p>If no data is sent over the connection in this time, then a <code>504 Gateway Timeout</code>
     * will be sent to the client (if the response hasn't already started)
     * and the connection will be closed.</p>
     * <p>Defaults to 2 minutes.</p>
     * @param idleTimeout The allowed time for a request.
     * @param unit The timeout unit.
     * @return This builder
     */
    public ReverseProxyBuilder withIdleTimeout(long idleTimeout, TimeUnit unit) {
        this.idleTimeoutInMillis = unit.toMillis(idleTimeout);
        return this;
    }

    /**
     * For an HTTP connection in the connection pool that does not have an in-progress request, this controls the
     * amount of time the connection will stay open for.
     * <p>A larger number will mean it is more likely that there is an already-open connection available when a new
     * request comes in, however this means more TCP connections (and so system resources) are potentially used.</p>
     * <p>Defaults to 5 minutes.</p>
     * @param idleTimeout The allowed time for a request.
     * @param unit The timeout unit.
     * @return This builder
     */
    public ReverseProxyBuilder withIdleConnectionTimeout(long idleTimeout, TimeUnit unit) {
        this.idleConnectionTimeoutInMillis = unit.toMillis(idleTimeout);
        return this;
    }
    /**
     * Registers a proxy completion listener.
     * @param proxyCompleteListener A listener to be called when a proxy request is complete
     * @return This builder
     */
    public ReverseProxyBuilder addProxyCompleteListener(ProxyCompleteListener proxyCompleteListener) {
        if (proxyCompleteListeners == null) {
            proxyCompleteListeners = new ArrayList<>(1);
        }
        proxyCompleteListeners.add(proxyCompleteListener);
        return this;
    }

    /**
     * Adds an interceptor to the point where a request to the target server has been prepared, but not sent. This
     * allows you to change the headers being proxied to the target server.
     * @param requestInterceptor An interceptor that may change the target request, or null to not have an interceptor.
     * @return This builder.
     */
    public ReverseProxyBuilder withRequestInterceptor(ProxyRequestInterceptor requestInterceptor) {
        this.requestInterceptor = requestInterceptor;
        return this;
    }

    /**
     * Adds an interceptor to the point where a response to the client has been prepared, but not sent. This
     * allows you to change the response code or headers being returned to the client.
     * @param responseInterceptor An interceptor that may change the client response, or null to not have an interceptor.
     * @return This builder.
     */
    public ReverseProxyBuilder withResponseInterceptor(ProxyResponseInterceptor responseInterceptor) {
        this.responseInterceptor = responseInterceptor;
        return this;
    }

    /**
     * Creates and returns a new instance of a reverse proxy builder.
     * @return A builder
     */
    public static ReverseProxyBuilder reverseProxy() {
        return new ReverseProxyBuilder();
    }

    /**
     * Creates a new ReverseProxy which is a MuHandler. You can pass the resulting handler directly
     * to {@link io.muserver.MuServerBuilder#addHandler(MuHandler)}
     * @return A MuHandler that acts as a reverse proxy
     */
    @Override
    public ReverseProxy build() {
        if (uriMapper == null) {
            throw new IllegalStateException("A URI mapper must be specified");
        }

        List<ProxyCompleteListener> proxyCompleteListeners = this.proxyCompleteListeners;
        if (proxyCompleteListeners == null) {
            proxyCompleteListeners = emptyList();
        }

        SslContext sslCtx;
        try {
            sslCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        } catch (SSLException e) {
            throw new MuException("Error setting up SSL Context", e);
        }

        Set<String> dnp = new HashSet<>();
        for (String header : this.doNotProxyHeaders) {
            dnp.add(header.toLowerCase());
        }
        dnp.addAll(ReverseProxy.REPRESSED);

        ProxySettings settings = new ProxySettings(viaName, uriMapper, sendLegacyForwardedHeaders, discardClientForwardedHeaders,
            totalTimeoutInMillis, idleTimeoutInMillis, idleConnectionTimeoutInMillis, proxyCompleteListeners, dnp, requestInterceptor, responseInterceptor, sslCtx);
        return new ReverseProxy(settings);
    }

}

class ProxySettings {
    final String viaName;
    final UriMapper uriMapper;
    final boolean sendLegacyForwardedHeaders;
    final boolean discardClientForwardedHeaders;
    final long totalTimeoutInMillis;
    final long idleTimeoutInMillis;
    final long idleConnectionTimeoutInMillis;
    final List<ProxyCompleteListener> proxyCompleteListeners;
    final Set<String> doNotProxyHeaders;
    final ProxyRequestInterceptor requestInterceptor;
    final ProxyResponseInterceptor responseInterceptor;
    final SslContext sslCtx;

    ProxySettings(String viaName, UriMapper uriMapper, boolean sendLegacyForwardedHeaders, boolean discardClientForwardedHeaders, long totalTimeoutInMillis, long idleTimeoutInMillis, long idleConnectionTimeoutInMillis, List<ProxyCompleteListener> proxyCompleteListeners, Set<String> doNotProxyHeaders, ProxyRequestInterceptor requestInterceptor, ProxyResponseInterceptor responseInterceptor, SslContext sslCtx) {
        this.viaName = viaName;
        this.uriMapper = uriMapper;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.totalTimeoutInMillis = totalTimeoutInMillis;
        this.idleTimeoutInMillis = idleTimeoutInMillis;
        this.idleConnectionTimeoutInMillis = idleConnectionTimeoutInMillis;
        this.proxyCompleteListeners = proxyCompleteListeners;
        this.doNotProxyHeaders = doNotProxyHeaders;
        this.requestInterceptor = requestInterceptor;
        this.responseInterceptor = responseInterceptor;
        this.sslCtx = sslCtx;
    }
}

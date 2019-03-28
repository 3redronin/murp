package io.muserver.murp;

import io.muserver.MuHandler;
import io.muserver.MuHandlerBuilder;
import io.muserver.Mutils;
import org.eclipse.jetty.client.HttpClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyList;

/**
 * A builder for creating a reverse proxy, which is a {@link MuHandler} that can be added to a Mu Server.
 */
public class ReverseProxyBuilder implements MuHandlerBuilder<ReverseProxy> {

    private String viaName = "private";
    private HttpClient httpClient;
    private UriMapper uriMapper;
    private boolean sendLegacyForwardedHeaders;
    private boolean discardClientForwardedHeaders;
    private long totalTimeoutInMillis = TimeUnit.MINUTES.toMillis(5);
    private List<ProxyCompleteListener> proxyCompleteListeners;

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
     * Specifies the Jetty HTTP client to use to make the request to the target server. It's recommended
     * you do not set this in order to use the default client that is optimised for reverse proxy usage.
     * @param httpClient The HTTP client to use, or null to use the default client.
     * @return This builder
     */
    public ReverseProxyBuilder withHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        return this;
    }

    /**
     * Required value. Sets the mapper to use for creating target URIs.
     * <p>If you want to proxy all requests to a single destination, consider using {@link UriMapper#toDomain(URI)}</p>
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
     * Sets the total request timeout in millis for a proxied request. Defaults to 5 minutes.
     * @param totalTimeoutInMillis The allowed time in milliseconds for a request.
     * @return This builder
     */
    public ReverseProxyBuilder withTotalTimeout(long totalTimeoutInMillis) {
        this.totalTimeoutInMillis = totalTimeoutInMillis;
        return this;
    }

    /**
     * Sets the total request timeout in millis for a proxied request. Defaults to 5 minutes.
     * @param totalTimeout The allowed time for a request.
     * @param unit The timeout unit.
     * @return This builder
     */
    public ReverseProxyBuilder withTotalTimeout(long totalTimeout, TimeUnit unit) {
        return withTotalTimeout(unit.toMillis(totalTimeout));
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
        HttpClient client = httpClient;
        if (client == null) {
            client = HttpClientBuilder.httpClient().build();
        }
        List<ProxyCompleteListener> proxyCompleteListeners = this.proxyCompleteListeners;
        if (proxyCompleteListeners == null) {
            proxyCompleteListeners = emptyList();
        }
        return new ReverseProxy(client, uriMapper, totalTimeoutInMillis, proxyCompleteListeners, viaName, discardClientForwardedHeaders, sendLegacyForwardedHeaders);
    }
}

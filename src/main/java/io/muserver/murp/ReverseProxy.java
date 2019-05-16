package io.muserver.murp;

import io.muserver.*;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Arrays.asList;

public class ReverseProxy implements MuHandler {
    private static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);

    /**
     * An unmodifiable set of the Hop By Hop headers. All are in lowercase.
     */
    public static final Set<String> HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(new HashSet<>(asList(
       "keep-alive", "transfer-encoding", "te", "connection", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate")));

    private static final Set<String> REPRESSED;

    static {
        REPRESSED = new HashSet<>(HOP_BY_HOP_HEADERS);
        REPRESSED.addAll(new HashSet<>(asList(
            "forwarded", "x-forwarded-by", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-forwarded-server", "via", "expect"
        )));

        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            ip = "unknown";
            log.info("Could not fine local address so using " + ip);
        }
        ipAddress = ip;
    }


    private final AtomicLong counter = new AtomicLong();
    private final HttpClient httpClient;
    private final UriMapper uriMapper;
    private final long totalTimeoutInMillis;
    private final List<ProxyCompleteListener> proxyCompleteListeners;

    private final Set<String> doNotProxyToTarget = new HashSet<>();

    private static final String ipAddress;

    private final String viaName;
    private final boolean discardClientForwardedHeaders;
    private final boolean sendLegacyForwardedHeaders;
    private final RequestInterceptor requestInterceptor;
    private final ResponseInterceptor responseInterceptor;

    ReverseProxy(HttpClient httpClient, UriMapper uriMapper, long totalTimeoutInMillis, List<ProxyCompleteListener> proxyCompleteListeners,
                 String viaName, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders,
                 Set<String> additionalDoNotProxyHeaders, RequestInterceptor requestInterceptor, ResponseInterceptor responseInterceptor) {
        this.httpClient = httpClient;
        this.uriMapper = uriMapper;
        this.totalTimeoutInMillis = totalTimeoutInMillis;
        this.proxyCompleteListeners = proxyCompleteListeners;
        this.viaName = viaName;
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        this.requestInterceptor = requestInterceptor;
        this.responseInterceptor = responseInterceptor;
        this.doNotProxyToTarget.addAll(REPRESSED);
        additionalDoNotProxyHeaders.forEach(h -> this.doNotProxyToTarget.add(h.toLowerCase()));
    }

    @Override
    public boolean handle(MuRequest clientReq, MuResponse clientResp) throws Exception {
        URI target = uriMapper.mapFrom(clientReq);
        if (target == null) {
            return false;
        }

        final long start = System.currentTimeMillis();

        clientResp.headers().remove(HeaderNames.DATE); // so that the target's date can be used

        final AsyncHandle asyncHandle = clientReq.handleAsync();
        final long id = counter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("[" + id + "] Proxying from " + clientReq.uri() + " to " + target);
        }

        Request targetReq = httpClient.newRequest(target);
        targetReq.method(clientReq.method().name());
        String viaValue = clientReq.protocol() + " " + viaName;
        boolean hasRequestBody = setTargetRequestHeaders(clientReq, targetReq, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxyToTarget);

        if (hasRequestBody) {
            DeferredContentProvider targetReqBody = new DeferredContentProvider();
            asyncHandle.setReadListener(new RequestBodyListener() {
                @Override
                public void onDataReceived(ByteBuffer buffer) {
                    targetReqBody.offer(buffer);
                }

                @Override
                public void onComplete() {
                    targetReqBody.close();
                }

                @Override
                public void onError(Throwable t) {
                    targetReqBody.failed(t);
                }
            });
            targetReq.content(targetReqBody);
        }

        targetReq.onResponseHeaders(response -> {
            clientResp.status(response.getStatus());
            HttpFields targetRespHeaders = response.getHeaders();
            List<String> customHopByHopHeaders = getCustomHopByHopHeaders(targetRespHeaders.get(HttpHeader.CONNECTION));
            for (HttpField targetRespHeader : targetRespHeaders) {
                String lowerName = targetRespHeader.getName().toLowerCase();
                if (HOP_BY_HOP_HEADERS.contains(lowerName) || customHopByHopHeaders.contains(lowerName)) {
                    continue;
                }
                String value = targetRespHeader.getValue();
                clientResp.headers().add(targetRespHeader.getName(), value);
            }
            String newVia = getNewViaValue(viaValue, targetRespHeaders.getValuesList(HttpHeader.VIA));
            clientResp.headers().set(HeaderNames.VIA, newVia);
            if (responseInterceptor != null) {
                try {
                    responseInterceptor.intercept(clientReq, targetReq, response, clientResp);
                } catch (Exception e) {
                    log.error("Error while intercepting the response. The response will still be proxied.", e);
                }
            }
        });
        targetReq.onResponseContentAsync((response, content, callback) -> asyncHandle.write(content,
            new WriteCallback() {
                @Override
                public void onFailure(Throwable reason) {
                    callback.failed(reason);
                }

                @Override
                public void onSuccess() {
                    callback.succeeded();
                }
            }));
        targetReq.timeout(totalTimeoutInMillis, TimeUnit.MILLISECONDS);

        if (requestInterceptor != null) {
            requestInterceptor.intercept(clientReq, targetReq);
        }
        targetReq.send(result -> {
            long duration = System.currentTimeMillis() - start;
            try {
                if (result.isFailed()) {
                    String errorID = UUID.randomUUID().toString();
                    if (log.isDebugEnabled()) {
                        log.debug("Failed to proxy response. ErrorID=" + errorID + " for " + result, result.getFailure());
                    }
                    if (!clientResp.hasStartedSendingData()) {
                        clientResp.contentType(ContentTypes.TEXT_HTML);
                        if (result.getFailure() instanceof TimeoutException) {
                            clientResp.status(504);
                            clientResp.write("<h1>504 Gateway Timeout</h1><p>The target did not respond in a timely manner. ErrorID=" + errorID + "</p>");
                        } else {
                            clientResp.status(502);
                            clientResp.write("<h1>502 Bad Gateway</h1><p>ErrorID=" + errorID + "</p>");
                        }
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.info("[" + id + "] completed in " + duration + "ms: " + result);
                    }
                }
            } finally {
                asyncHandle.complete();
                for (ProxyCompleteListener proxyCompleteListener : proxyCompleteListeners) {
                    try {
                        proxyCompleteListener.onComplete(clientReq, clientResp, target, duration);
                    } catch (Exception e) {
                        log.warn(proxyCompleteListener + " threw an error while processing onComplete", e);
                    }
                }
            }
        });

        return true;
    }

    /**
     * Copies headers from the clientRequest to the targetRequest, removing any Hop-By-Hop headers and adding Forwarded headers.
     * @param clientRequest The original Mu request to copy headers from.
     * @param targetRequest A Jetty request to copy the headers to.
     * @param discardClientForwardedHeaders Set true to ignore Forwarded headers from the client request
     * @param sendLegacyForwardedHeaders Set true to send X-Forwarded-* headers along with Forwarded headers
     * @param viaValue The value to set on the Via header, for example <code>HTTP/1.1 myserver</code>
     * @return Returns true if the client request has a body; otherwise false.
     */
    public static boolean setRequestHeaders(MuRequest clientRequest, Request targetRequest, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders, String viaValue) {
        Mutils.notNull("clientRequest", clientRequest);
        Mutils.notNull("targetRequest", targetRequest);
        return setTargetRequestHeaders(clientRequest, targetRequest, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, REPRESSED);
    }

    private static boolean setTargetRequestHeaders(MuRequest clientRequest, Request targetRequest, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders, String viaValue, Set<String> excludedHeaders) {
        Headers reqHeaders = clientRequest.headers();
        List<String> customHopByHop = getCustomHopByHopHeaders(reqHeaders.get(HeaderNames.CONNECTION));

        boolean hasContentLengthOrTransferEncoding = false;
        for (Map.Entry<String, String> clientHeader : reqHeaders) {
            String key = clientHeader.getKey();
            String lowKey = key.toLowerCase();
            if (excludedHeaders.contains(lowKey) || customHopByHop.contains(lowKey)) {
                continue;
            }
            hasContentLengthOrTransferEncoding |= lowKey.equals("content-length") || lowKey.equals("transfer-encoding");
            targetRequest.header(key, clientHeader.getValue());
        }

        String newViaValue = getNewViaValue(viaValue, clientRequest.headers().getAll(HeaderNames.VIA));
        targetRequest.header(HttpHeader.VIA, newViaValue);

        setForwardedHeaders(clientRequest, targetRequest, discardClientForwardedHeaders, sendLegacyForwardedHeaders);

        return hasContentLengthOrTransferEncoding;
    }

    private static String getNewViaValue(String viaValue, List<String> previousViasList) {
        String previousVias = String.join(", ", previousViasList);
        if (!previousVias.isEmpty()) previousVias += ", ";
        return previousVias + viaValue;
    }

    /**
     * Sets Forwarded and optionally X-Forwarded-* headers to the target request, based on the client request
     * @param clientRequest the received client request
     * @param targetRequest the target request to write the headers to
     * @param discardClientForwardedHeaders if <code>true</code> then existing Forwarded headers on the client request will be discarded (normally false, unless you do not trust the upstream system)
     * @param sendLegacyForwardedHeaders if <code>true</code> then X-Forwarded-Proto/Host/For headers will also be added
     */
    public static void setForwardedHeaders(MuRequest clientRequest, Request targetRequest, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders) {
        Mutils.notNull("clientRequest", clientRequest);
        Mutils.notNull("targetRequest", targetRequest);
        List<ForwardedHeader> forwardHeaders;
        if (discardClientForwardedHeaders) {
            forwardHeaders = Collections.emptyList();
        } else {
            forwardHeaders = clientRequest.headers().forwarded();
            for (ForwardedHeader existing : forwardHeaders) {
                targetRequest.header(HttpHeader.FORWARDED, existing.toString());
            }
        }

        ForwardedHeader newForwarded = createForwardedHeader(clientRequest);
        targetRequest.header(HttpHeader.FORWARDED, newForwarded.toString());

        if (sendLegacyForwardedHeaders) {
            ForwardedHeader first = forwardHeaders.isEmpty() ? newForwarded : forwardHeaders.get(0);
            setXForwardedHeaders(targetRequest, first);
        }
    }

    /**
     * Sets X-Forwarded-Proto, X-Forwarded-Host and X-Forwarded-For on the request given the forwarded header.
     * @param targetRequest The request to add the headers to
     * @param forwardedHeader The forwarded header that has the original client information on it.
     */
    private static void setXForwardedHeaders(Request targetRequest, ForwardedHeader forwardedHeader) {
        targetRequest.header(HttpHeader.X_FORWARDED_PROTO, forwardedHeader.proto());
        targetRequest.header(HttpHeader.X_FORWARDED_HOST, forwardedHeader.host());
        targetRequest.header(HttpHeader.X_FORWARDED_FOR, forwardedHeader.forValue());
    }

    /**
     * Creates a Forwarded header for the based on the current request which can be used when
     * proxying the request to a target.
     * @param clientRequest The request from the client
     * @return A ForwardedHeader that can be added to a new request
     */
    private static ForwardedHeader createForwardedHeader(MuRequest clientRequest) {
        String forwardedFor = clientRequest.remoteAddress();
        String proto = clientRequest.serverURI().getScheme();
        String host = clientRequest.headers().get(HeaderNames.HOST);
        return new ForwardedHeader(ipAddress, forwardedFor, host, proto, null);
    }

    private static List<String> getCustomHopByHopHeaders(String connectionHeaderValue) {
        if (connectionHeaderValue == null) {
            return Collections.emptyList();
        }
        List<String> customHopByHop = new ArrayList<>();
        String[] split = connectionHeaderValue.split("\\s*,\\s*");
        for (String s : split) {
            customHopByHop.add(s.toLowerCase());
        }
        return customHopByHop;
    }
}

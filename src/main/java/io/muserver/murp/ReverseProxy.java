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
    private static final Set<String> HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(new HashSet<>(asList(
        "keep-alive", "transfer-encoding", "te", "connection", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate")));
    private static final Set<String> FORWARDED_HEADERS = Collections.unmodifiableSet(new HashSet<>(asList(
        "forwarded", "x-forwarded-by", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-forwarded-server"
    )));

    private final AtomicLong counter = new AtomicLong();
    private final HttpClient httpClient;
    private final UriMapper uriMapper;
    private final long totalTimeoutInMillis;

    private static final String ipAddress;

    static {
        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            ip = "unknown";
            log.info("Could not fine local address so using " + ip);
        }
        ipAddress = ip;
    }

    private final String viaValue;
    private final boolean discardClientForwardedHeaders;
    private final boolean sendLegacyForwardedHeaders;

    ReverseProxy(HttpClient httpClient, UriMapper uriMapper, long totalTimeoutInMillis, String viaName, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders) {
        this.httpClient = httpClient;
        this.uriMapper = uriMapper;
        this.totalTimeoutInMillis = totalTimeoutInMillis;
        this.viaValue = "HTTP/1.1 " + viaName;
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
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
        log.info("[" + id + "] Proxying from " + clientReq.uri() + " to " + target);

        Request targetReq = httpClient.newRequest(target);
        targetReq.method(clientReq.method().name());
        boolean hasRequestBody = setRequestHeaders(clientReq, targetReq);

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
            clientResp.headers().add(HeaderNames.VIA, viaValue);
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
        targetReq.send(result -> {
            try {
                long duration = System.currentTimeMillis() - start;
                if (result.isFailed()) {
                    String errorID = UUID.randomUUID().toString();
                    log.error("Failed to proxy response. ErrorID=" + errorID + " for " + result, result.getFailure());
                    if (!clientResp.hasStartedSendingData()) {
                        clientResp.contentType(ContentTypes.TEXT_HTML);
                        if (result.getFailure() instanceof TimeoutException) {
                            clientResp.status(504);
                            clientResp.write("<h1>504 Gateway Timeout</h1><p>ErrorID=" + errorID + "</p>");
                        } else {
                            clientResp.status(502);
                            clientResp.write("<h1>502 Bad Gateway</h1><p>ErrorID=" + errorID + "</p>");
                        }
                    }
                } else {
                    log.info("[" + id + "] completed in " + duration + "ms: " + result);
                }
            } finally {
                asyncHandle.complete();
            }
        });

        return true;
    }

    private boolean setRequestHeaders(MuRequest clientReq, Request targetReq) {
        Headers reqHeaders = clientReq.headers();
        List<String> customHopByHop = getCustomHopByHopHeaders(reqHeaders.get(HeaderNames.CONNECTION));

        boolean hasContentLengthOrTransferEncoding = false;
        for (Map.Entry<String, String> clientHeader : reqHeaders) {
            String key = clientHeader.getKey();
            String lowKey = key.toLowerCase();
            if (HOP_BY_HOP_HEADERS.contains(lowKey) || FORWARDED_HEADERS.contains(lowKey) || customHopByHop.contains(lowKey)) {
                continue;
            }
            hasContentLengthOrTransferEncoding |= lowKey.equals("content-length") || lowKey.equals("transfer-encoding");
            targetReq.header(key, clientHeader.getValue());
        }

        targetReq.header(HttpHeader.VIA, viaValue);

        List<ForwardedHeader> forwardHeaders;
        if (discardClientForwardedHeaders) {
            forwardHeaders = Collections.emptyList();
        } else {
            forwardHeaders = clientReq.headers().forwarded();
            for (ForwardedHeader existing : forwardHeaders) {
                targetReq.header(HttpHeader.FORWARDED, existing.toString());
            }
        }

        ForwardedHeader newForwarded = createFowardedHeader(clientReq);
        targetReq.header(HttpHeader.FORWARDED, newForwarded.toString());

        if (sendLegacyForwardedHeaders) {
            ForwardedHeader first = forwardHeaders.isEmpty() ? newForwarded : forwardHeaders.get(0);
            targetReq.header(HttpHeader.X_FORWARDED_PROTO, first.proto());
            targetReq.header(HttpHeader.X_FORWARDED_HOST, first.host());
            targetReq.header(HttpHeader.X_FORWARDED_FOR, first.forValue());
        }

        return hasContentLengthOrTransferEncoding;
    }

    private static ForwardedHeader createFowardedHeader(MuRequest clientReq) {
        String forwardedFor = clientReq.remoteAddress();
        String proto = clientReq.serverURI().getScheme();
        String host = clientReq.headers().get(HeaderNames.HOST);
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

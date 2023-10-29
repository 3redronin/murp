package io.muserver.murp;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Arrays.asList;

/**
 * The core implementation for ReverseProxy
 */
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
    public boolean handle(MuRequest clientRequest, MuResponse clientResponse) throws Exception {
        URI target = uriMapper.mapFrom(clientRequest);
        if (target == null) {
            return false;
        }

        log.info("Proxying request {} to {}", clientRequest, target);

        final long start = System.currentTimeMillis();
        final AsyncHandle asyncHandle = clientRequest.handleAsync();

        clientResponse.headers().remove(HeaderNames.DATE); // so that the target's date can be used

        final long id = counter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("[" + id + "] Proxying from " + clientRequest.uri() + " to " + target);
        }

        AtomicReference<CompletableFuture<HttpResponse<Void>>> targetResponseFutureRef = new AtomicReference<>();
        AtomicReference<HttpRequest> targetRequestRef = new AtomicReference<>();
        AtomicReference<HttpResponse<Void>> targetResponseRef = new AtomicReference<>();

        Consumer<Throwable> closeClientRequest = (error) -> {
            if (error != null) {
                log.warn("something wrong while handling client request " + clientRequest, error);
            }
            if (!clientResponse.responseState().endState()) {
                asyncHandle.complete();
            }
            CompletableFuture<HttpResponse<Void>> targetResponse = targetResponseFutureRef.get();
            if (targetResponse != null) {
                targetResponse.cancel(true);
            }
        };

        HttpRequest.BodyPublisher bodyPublisher;
        boolean hasRequestBody = hasRequestBody(clientRequest);
        if (hasRequestBody) {
            bodyPublisher = new HttpRequest.BodyPublisher() {
                @Override
                public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {

                    ConcurrentLinkedDeque<DoneCallback> doneCallbacks = new ConcurrentLinkedDeque<>();

                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            DoneCallback doneCallback = doneCallbacks.poll();
                            if (doneCallback != null) {
                                try {
                                    doneCallback.onComplete(null);
                                } catch (Exception e) {
                                    log.warn("onComplete failed", e);
                                    this.cancel();
                                }
                            }
                        }

                        @Override
                        public void cancel() {
                            closeClientRequest.accept(new RuntimeException("request body send cancel"));
                        }
                    });

                    // start to read body
                    asyncHandle.setReadListener(new RequestBodyListener() {
                        @Override
                        public void onDataReceived(ByteBuffer byteBuffer, DoneCallback doneCallback) throws Exception {
                            subscriber.onNext(byteBuffer);
                            doneCallbacks.add(doneCallback);
                        }

                        @Override
                        public void onComplete() {
                            subscriber.onComplete();
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            // cancel the target request
                            subscriber.onError(throwable);
                            closeClientRequest.accept(new RuntimeException("request body read error"));
                        }
                    });
                }

                @Override
                public long contentLength() {
                    String contentLength = clientRequest.headers().get(HeaderNames.CONTENT_LENGTH);
                    if (contentLength != null) {
                        return Long.parseLong(contentLength);
                    } else {
                        return -1;
                    }
                }
            };
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }

        HttpRequest.Builder targetReq = HttpRequest.newBuilder()
                .uri(target)
                .method(clientRequest.method().toString(), bodyPublisher);

        String viaValue = clientRequest.protocol() + " " + viaName;
        setTargetRequestHeaders(clientRequest, targetReq, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxyToTarget);


        HttpResponse.BodyHandler<Void> bh = new HttpResponse.BodyHandler<>() {
            @Override
            public HttpResponse.BodySubscriber<Void> apply(HttpResponse.ResponseInfo responseInfo) {

                clientResponse.status(responseInfo.statusCode());

                // set response headers
                for (Map.Entry<String, List<String>> headerEntry : responseInfo.headers().map().entrySet()) {
                    for (String value : headerEntry.getValue()) {
                        String header = headerEntry.getKey();
                        String lowerName = header.toLowerCase();
                        if (HOP_BY_HOP_HEADERS.contains(lowerName)) {
                            continue;
                        }
                        clientResponse.headers().set(header, value);
                    }
                }

                String newVia = getNewViaValue(viaValue, clientResponse.headers().getAll(HeaderNames.VIA));
                clientResponse.headers().set(HeaderNames.VIA, newVia);

                if (responseInterceptor != null) {
                    try {
                        responseInterceptor.intercept(clientRequest, targetRequestRef.get(), targetResponseRef.get(), clientResponse);
                    } catch (Exception e) {
                        log.info("responseInterceptor error", e);
                    }
                }

                // response body
                return HttpResponse.BodySubscribers.fromSubscriber(new Flow.Subscriber<>() {

                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(List<ByteBuffer> item) {
                        for (ByteBuffer byteBuffer : item) {
                            if (clientResponse.responseState().endState()) {
                                subscription.cancel();
                                return;
                            }
                            asyncHandle.write(byteBuffer, throwable -> {
                                if (throwable != null) {
                                    onError(throwable);
                                    return;
                                }
                                subscription.request(1);
                            });
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        closeClientRequest.accept(throwable);
                    }

                    @Override
                    public void onComplete() {
                        asyncHandle.complete();
                        targetResponseFutureRef.set(null);
                    }
                });
            }
        };

        if (requestInterceptor != null) {
            try {
                requestInterceptor.intercept(clientRequest, targetReq);
            } catch (Throwable throwable) {
                log.info("requestInterceptor error", throwable);
                clientResponse.status(500);
                asyncHandle.complete();
                return true;
            }
        }

        HttpRequest targetRequest = targetReq.build();
        targetRequestRef.set(targetRequest);
        targetResponseFutureRef.set(httpClient.sendAsync(targetRequest, bh));

        targetResponseFutureRef.get()
                .orTimeout(totalTimeoutInMillis, TimeUnit.MILLISECONDS)
                .whenComplete((voidHttpResponse, throwable) -> {

                    targetResponseRef.set(voidHttpResponse);

                    long duration = System.currentTimeMillis() - start;

                    if (throwable != null) {
                        log.info("target request " + clientRequest + " failed", throwable);
                    }

                    closeClientRequest.accept(throwable);

                    for (ProxyCompleteListener proxyCompleteListener : proxyCompleteListeners) {
                        try {
                            proxyCompleteListener.onComplete(clientRequest, clientResponse, target, duration);
                        } catch (Exception e) {
                            log.warn("proxyCompleteListener error", e);
                        }
                    }
                });

        return true;
    }

    private static boolean hasRequestBody(MuRequest request) {
        for (Map.Entry<String, String> header : request.headers()) {
            String headerName = header.getKey().toLowerCase();
            if (headerName.equals("content-length") || headerName.equals("transfer-encoding")) {
                return true;
            }
        }
        return false;
    }

    private static boolean setTargetRequestHeaders(MuRequest clientRequest, HttpRequest.Builder targetRequest, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders, String viaValue, Set<String> excludedHeaders) {
        Headers reqHeaders = clientRequest.headers();
        List<String> customHopByHop = getCustomHopByHopHeaders(reqHeaders.get(HeaderNames.CONNECTION));

        boolean hasContentLengthOrTransferEncoding = false;
        for (Map.Entry<String, String> clientHeader : reqHeaders) {
            String key = clientHeader.getKey();
            String lowKey = key.toLowerCase();
            hasContentLengthOrTransferEncoding |= lowKey.equals("content-length") || lowKey.equals("transfer-encoding");
            if (excludedHeaders.contains(lowKey) || customHopByHop.contains(lowKey) || HttpClientUtils.DISALLOWED_REQUEST_HEADERS.contains(lowKey)) {
                continue;
            }
            targetRequest.header(key, clientHeader.getValue());
        }

        String newViaValue = getNewViaValue(viaValue, clientRequest.headers().getAll(HeaderNames.VIA));
        targetRequest.header(HeaderNames.VIA.toString(), newViaValue);

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
     *
     * @param clientRequest                 the received client request
     * @param targetRequestBuilder          the target request builder to write the headers to
     * @param discardClientForwardedHeaders if <code>true</code> then existing Forwarded headers on the client request will be discarded (normally false, unless you do not trust the upstream system)
     * @param sendLegacyForwardedHeaders    if <code>true</code> then X-Forwarded-Proto/Host/For headers will also be added
     */
    public static void setForwardedHeaders(MuRequest clientRequest, HttpRequest.Builder targetRequestBuilder, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders) {
        Mutils.notNull("clientRequest", clientRequest);
        Mutils.notNull("targetRequest", targetRequestBuilder);
        List<ForwardedHeader> forwardHeaders;
        if (discardClientForwardedHeaders) {
            forwardHeaders = Collections.emptyList();
        } else {
            forwardHeaders = clientRequest.headers().forwarded();
            for (ForwardedHeader existing : forwardHeaders) {
                targetRequestBuilder.header(HeaderNames.FORWARDED.toString(), existing.toString());
            }
        }

        ForwardedHeader newForwarded = createForwardedHeader(clientRequest);
        targetRequestBuilder.header(HeaderNames.FORWARDED.toString(), newForwarded.toString());

        if (sendLegacyForwardedHeaders) {
            ForwardedHeader first = forwardHeaders.isEmpty() ? newForwarded : forwardHeaders.get(0);
            setXForwardedHeaders(targetRequestBuilder, first);
        }
    }

    /**
     * Sets X-Forwarded-Proto, X-Forwarded-Host and X-Forwarded-For on the request given the forwarded header.
     *
     * @param targetRequest   The request to add the headers to
     * @param forwardedHeader The forwarded header that has the original client information on it.
     */
    private static void setXForwardedHeaders(HttpRequest.Builder targetRequest, ForwardedHeader forwardedHeader) {
        targetRequest.header(HeaderNames.X_FORWARDED_PROTO.toString(), forwardedHeader.proto());
        targetRequest.header(HeaderNames.X_FORWARDED_HOST.toString(), forwardedHeader.host());
        targetRequest.header(HeaderNames.X_FORWARDED_FOR.toString(), forwardedHeader.forValue());
    }

    /**
     * Creates a Forwarded header for the based on the current request which can be used when
     * proxying the request to a target.
     *
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

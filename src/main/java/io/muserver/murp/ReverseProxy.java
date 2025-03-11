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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Arrays.asList;

/**
 * The core implementation for ReverseProxy
 *
 * @author Daniel Flower
 * @version 1.0
 */
public class ReverseProxy implements MuHandler {
    private static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);

    /**
     * An unmodifiable set of the Hop By Hop headers. All are in lowercase.
     */
    public static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "keep-alive", "transfer-encoding", "te", "connection", "trailer", "upgrade",
        "proxy-authorization", "proxy-authenticate");

    private static final Set<String> HTTP_2_PSEUDO_HEADERS = Set.of(
        ":method", ":path", ":authority", ":scheme", ":status"
    );

    private static final Set<String> REPRESSED;

    static {
        REPRESSED = new HashSet<>(HOP_BY_HOP_HEADERS);
        REPRESSED.addAll(new HashSet<>(asList(
            "forwarded", "x-forwarded-by", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
            "x-forwarded-port", "x-forwarded-server", "via", "expect"
        )));

        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            ip = "unknown";
            log.info("Could not fine local address so using {}", ip);
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
    private final ProxyListener proxyListener;

    ReverseProxy(HttpClient httpClient, UriMapper uriMapper, long totalTimeoutInMillis, List<ProxyCompleteListener> proxyCompleteListeners,
                 String viaName, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders,
                 Set<String> additionalDoNotProxyHeaders, RequestInterceptor requestInterceptor, ResponseInterceptor responseInterceptor, ProxyListener proxyListener) {
        this.httpClient = httpClient;
        this.uriMapper = uriMapper;
        this.totalTimeoutInMillis = totalTimeoutInMillis;
        this.proxyCompleteListeners = proxyCompleteListeners;
        this.viaName = viaName;
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        this.requestInterceptor = requestInterceptor;
        this.responseInterceptor = responseInterceptor;
        this.proxyListener = proxyListener;
        this.doNotProxyToTarget.addAll(REPRESSED);
        additionalDoNotProxyHeaders.forEach(h -> this.doNotProxyToTarget.add(h.toLowerCase()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean handle(MuRequest clientRequest, MuResponse clientResponse) throws Exception {
        URI target = uriMapper.mapFrom(clientRequest);
        if (target == null) {
            return false;
        }

        final long start = System.currentTimeMillis();
        final AsyncHandle asyncHandle = clientRequest.handleAsync();
        final String clientRequestProtocol = clientRequest.protocol();

        clientResponse.headers().remove(HeaderNames.DATE); // so that the target's date can be used

        final long id = counter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("[{}] Proxying from {} to {}", id, clientRequest.uri(), target);
        }

        AtomicReference<CompletableFuture<HttpResponse<Void>>> targetResponseFutureRef = new AtomicReference<>();
        AtomicReference<HttpRequest> targetRequestRef = new AtomicReference<>();
        AtomicInteger responseBodyTotalByteCount = new AtomicInteger(0);


        asyncHandle.addResponseCompleteHandler((info) -> {

            long duration = System.currentTimeMillis() - start;

            if (proxyListener != null) {
                if (info.completedSuccessfully()) {
                    try {
                        proxyListener.onResponseBodyChunkFullSentToClient(clientRequest, clientResponse, responseBodyTotalByteCount.get());
                    } catch (Exception e) {
                        log.warn("proxyListener.onResponseBodyChunkFullSentToClient failed", e);
                    }
                } else {
                    try {
                        proxyListener.onErrorDetectedFromClient(clientRequest, clientResponse, targetRequestRef.get(), new RuntimeException("client not completed successfully."));
                    } catch (Exception e) {
                        log.warn("proxyListener.onErrorDetectedFromClient failed", e);
                    }
                }
            }

            for (ProxyCompleteListener proxyCompleteListener : proxyCompleteListeners) {
                try {
                    proxyCompleteListener.onComplete(clientRequest, clientResponse, target, duration);
                } catch (Exception e) {
                    log.warn("proxyCompleteListener error", e);
                }
            }

            CompletableFuture<HttpResponse<Void>> targetResponse = targetResponseFutureRef.get();
            if (targetResponse != null && !targetResponse.isDone()) {
                log.info("cancelling target request as client close detected. target_request={}, client_request={}", targetRequestRef.get(), clientRequest);
                targetResponse.cancel(true);
            }

            targetRequestRef.set(null);
            targetResponseFutureRef.set(null);
        });


        HttpRequest.BodyPublisher bodyPublisher;
        boolean hasRequestBody = hasRequestBody(clientRequest);
        if (hasRequestBody) {
            bodyPublisher = new HttpRequest.BodyPublisher() {
                @Override
                public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {

                    try {
                        ConcurrentLinkedDeque<DoneCallback> doneCallbacks = new ConcurrentLinkedDeque<>();
                        AtomicBoolean isFirst = new AtomicBoolean(true);

                        subscriber.onSubscribe(new Flow.Subscription() {
                            @Override
                            public void request(long n) {

                                long[] totalBytesCount = new long[]{0L};
                                DoneCallback doneCallback = doneCallbacks.poll();
                                if (doneCallback != null) {
                                    try {
                                        doneCallback.onComplete(null);
                                    } catch (Exception e) {
                                        log.warn("onComplete failed", e);
                                        this.cancel();
                                    }
                                }

                                if (isFirst.compareAndSet(true, false)) {

                                    // start reading client body only after target subscription established
                                    // otherwise calling `subscriber.onNext(byteBuffer)` will sometimes cause JDK http client
                                    // throw NullPointerException and cancel the subscription
                                    asyncHandle.setReadListener(new RequestBodyListener() {
                                        @Override
                                        public void onDataReceived(ByteBuffer byteBuffer, DoneCallback doneCallback) {

                                            doneCallbacks.add(doneCallback);
                                            ByteBuffer copy = cloneByteBuffer(byteBuffer);

                                            int position = copy.position();
                                            int remaining = copy.remaining();

                                            if (proxyListener != null) {
                                                try {
                                                    proxyListener.onBeforeRequestBodyChunkSentToTarget(clientRequest, clientResponse, copy.position(position));
                                                } catch (Exception e) {
                                                    log.warn("proxyListener.onBeforeRequestBodyChunkSentToTarget failed", e);
                                                }
                                            }

                                            subscriber.onNext(copy.position(position));
                                            totalBytesCount[0] += remaining;

                                            if (proxyListener != null) {
                                                try {
                                                    proxyListener.onRequestBodyChunkSentToTarget(clientRequest, clientResponse, copy.position(position));
                                                } catch (Exception e) {
                                                    log.warn("proxyListener.onBeforeRequestBodyChunkSentToTarget failed", e);
                                                }
                                            }
                                        }

                                        private ByteBuffer cloneByteBuffer(ByteBuffer byteBuffer) {
                                            // bug fix : upload file random broken - (some of the bytes disordered)
                                            // clone the byteBuffer to avoid it's being modified after passing into subscriber.onNext()
                                            int capacity = byteBuffer.remaining();
                                            ByteBuffer copy = byteBuffer.isDirect() ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
                                            copy.put(byteBuffer);
                                            copy.rewind();
                                            return copy;
                                        }

                                        @Override
                                        public void onComplete() {
                                            subscriber.onComplete();

                                            if (proxyListener != null) {
                                                try {
                                                    proxyListener.onRequestBodyFullSentToTarget(clientRequest, clientResponse, totalBytesCount[0]);
                                                } catch (Exception e) {
                                                    log.warn("proxyListener.onRequestBodyFullSentToTarget failed", e);
                                                }
                                            }
                                        }

                                        @Override
                                        public void onError(Throwable throwable) {
                                            // do nothing as asyncHandle response complete listener will trigger cancellation
                                        }
                                    });
                                }
                            }

                            @Override
                            public void cancel() {
                                log.info("cancel request body pumping");
                            }
                        });


                    } catch (Throwable throwable) {
                        log.info("body subscribe error", throwable);
                        throw throwable;
                    }

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

        String viaValue = clientRequestProtocol + " " + viaName;
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
                        if (!"HTTP/2.0".equals(clientRequestProtocol) && HTTP_2_PSEUDO_HEADERS.contains(lowerName)) {
                            continue;
                        }
                        clientResponse.headers().add(header, value);
                    }
                }

                String newVia = getNewViaValue(viaValue, clientResponse.headers().getAll(HeaderNames.VIA));
                clientResponse.headers().set(HeaderNames.VIA, newVia);

                if (responseInterceptor != null) {
                    try {
                        responseInterceptor.intercept(clientRequest, targetRequestRef.get(), responseInfo, clientResponse);
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
                    public void onNext(List<ByteBuffer> buffers) {

                        if (clientResponse.responseState().endState()) {
                            subscription.cancel();
                            onError(new RuntimeException("Error sending response data, client close early."));
                            return;
                        }

                        if (buffers.isEmpty()) {
                            log.warn("onNext called with empty buffers");
                            subscription.request(1);
                            return;
                        }

                        final int[] counter = new int[]{0};
                        final int total = buffers.size();

                        for (ByteBuffer buffer : buffers) {

                            int position = buffer.position();
                            int remaining = buffer.remaining();

                            if (clientResponse.responseState().endState()) {
                                subscription.cancel();
                                onError(new RuntimeException("Error sending response data, client close early."));
                                return;
                            }

                            if (proxyListener != null) {
                                try {
                                    proxyListener.onResponseBodyChunkReceivedFromTarget(clientRequest, clientResponse, buffer.position(position));
                                } catch (Exception e) {
                                    log.warn("proxyListener.onResponseBodyChunkReceivedFromTarget failed", e);
                                }
                            }

                            asyncHandle.write(buffer.position(position), throwable -> {

                                if (throwable != null) {
                                    subscription.cancel();
                                    onError(throwable);
                                    return;
                                }

                                responseBodyTotalByteCount.addAndGet(remaining);

                                if (proxyListener != null) {
                                    try {
                                        proxyListener.onResponseBodyChunkSentToClient(clientRequest, clientResponse, buffer.position(position));
                                    } catch (Exception e) {
                                        log.warn("proxyListener.onResponseBodyChunkSentToClient failed", e);
                                    }
                                }

                                if (++counter[0] >= total) {
                                    subscription.request(1);
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        // do nothing, trigger client request close on the httpClient.sendAsync() complete callback.
                    }

                    @Override
                    public void onComplete() {
                        // do nothing, trigger client request close on the httpClient.sendAsync() complete callback.
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
            .whenComplete((httpResponse, throwable) -> {

                if (clientResponse.responseState().endState()) {
                    return;
                }

                if (throwable == null) {
                    asyncHandle.complete();
                    return;
                }

                log.info("closing client request as target server error detected. " +
                    "client_request=[{}], target_request=[{}], error={}", clientRequest, targetRequestRef.get(), throwable.getMessage());

                if (proxyListener != null) {
                    try {
                        proxyListener.onErrorDetectedFromTarget(clientRequest, clientResponse, targetRequestRef.get(), throwable);
                    } catch (Exception error) {
                        log.warn("proxyListener.onErrorDetectedFromTarget failed", error);
                    }
                }

                if (clientResponse.hasStartedSendingData()) {
                    asyncHandle.complete(throwable);
                    return;
                }

                final int status = (throwable instanceof TimeoutException) ? 504 : 500;
                final String body = (throwable instanceof TimeoutException) ? "504 Gateway Timeout" : "500 Internal Server Error";
                clientResponse.status(status);
                asyncHandle.write(Mutils.toByteBuffer(body));
                asyncHandle.complete();

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
        if (forwardedHeader == null) return;
        String proto = forwardedHeader.proto();
        if (proto != null) {
            targetRequest.header(HeaderNames.X_FORWARDED_PROTO.toString(), proto);
        }
        String host = forwardedHeader.host();
        if (host != null) {
            targetRequest.header(HeaderNames.X_FORWARDED_HOST.toString(), host);
        }
        String forValue = forwardedHeader.forValue();
        if (forValue != null) {
            targetRequest.header(HeaderNames.X_FORWARDED_FOR.toString(), forValue);
        }
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

    @FunctionalInterface
    private interface ThrowableRunnable {
        void run() throws Throwable;
    }

    private static void logError(ThrowableRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            log.error("logError", e);
        }
    }

}

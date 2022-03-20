package io.muserver.murp;

import io.muserver.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotAllowedException;
import javax.ws.rs.ServerErrorException;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

public class ReverseProxy implements MuHandler, Closeable {
    private static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);
    private static final AsciiString FORWARDED = AsciiString.cached("forwarded");
    private static final AsciiString X_FORWARDED_PROTO = AsciiString.cached("x_forwarded_proto");
    private static final AsciiString X_FORWARDED_HOST = AsciiString.cached("x_forwarded_host");
    private static final AsciiString X_FORWARDED_FOR = AsciiString.cached("x_forwarded_for");

    private static final Map<Method, HttpMethod> ALLOWED_METHODS = new HashMap<>();

    /**
     * An unmodifiable set of the Hop By Hop headers. All are in lowercase.
     */
    public static final Set<String> HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(new HashSet<>(asList(
        "keep-alive", "transfer-encoding", "te", "connection", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate")));

    static final Set<String> REPRESSED;

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

        for (Method muMethod : Method.values()) {
            ALLOWED_METHODS.put(muMethod, HttpMethod.valueOf(muMethod.name()));
        }
    }

    private final AtomicLong counter = new AtomicLong();
    private static final String ipAddress;
    private final ProxySettings settings;
    private final ProxyConnectionPool pool;

    ReverseProxy(ProxySettings settings) {
        this.settings = settings;
        this.pool = new ProxyConnectionPool(settings);
    }

    public int poolSize(URI destination) {
        return pool.poolSize(destination);
    }

    @Override
    public boolean handle(MuRequest clientReq, MuResponse clientResp) throws Exception {
        URI target = settings.uriMapper.mapFrom(clientReq);
        if (target == null) {
            return false;
        }
        HttpMethod targetMethod = ALLOWED_METHODS.get(clientReq.method());
        if (targetMethod == null) {
            ArrayList<Method> can = new ArrayList<>(ALLOWED_METHODS.keySet());
            throw new NotAllowedException(can.get(0).name(), can.stream().skip(1).map(Method::name).collect(Collectors.toList()).toArray(new String[0]));
        }




        final AsyncHandle asyncHandle = clientReq.handleAsync();
        final long id = counter.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("[" + id + "] Proxying from " + clientReq.uri() + " to " + target);
        }


        CompletionStage<ProxyConnection> connection = pool.getConnection(target, clientReq.server().maxUrlSize(), clientReq.server().maxRequestHeadersSize());
        connection.whenComplete((pc, throwable) -> {
            if (pc != null) {
                String viaValue = clientReq.protocol() + " " + settings.viaName;
                ProxyExchange exchange = new ProxyExchange(clientReq, clientResp, asyncHandle, target, targetMethod, viaValue, settings.idleTimeoutInMillis);
                pc.sendRequestToTarget(exchange);
            } else {
                asyncHandle.complete(new ServerErrorException("Error connecting to target", 502));
            }
        });


//                    String errorID = UUID.randomUUID().toString();
//                    if (log.isDebugEnabled()) {
//                        log.debug("Failed to proxy response. ErrorID=" + errorID + " for " + result, result.getFailure());
//                    }
//                    if (!clientResp.hasStartedSendingData()) {
//                        clientResp.contentType(ContentTypes.TEXT_HTML);
//                        if (result.getFailure() instanceof TimeoutException) {
//                            clientResp.status(504);
//                            asyncHandle.write(toByteBuffer("<h1>504 Gateway Timeout</h1><p>The target did not respond in a timely manner. ErrorID=" + errorID + "</p>"));
//                        } else {
//                            clientResp.status(502);
//                            asyncHandle.write(toByteBuffer("<h1>502 Bad Gateway</h1><p>ErrorID=" + errorID + "</p>" + errorID + "</p>"));
//                        }
//                    }

        return true;
    }

    /**
     * Copies headers from the clientRequest to the targetRequest, removing any Hop-By-Hop headers and adding Forwarded headers.
     *
     * @param target                        The target destination
     * @param clientRequest                 The original Mu request to copy headers from.
     * @param targetRequestHeaders          A Jetty request to copy the headers to.
     * @param discardClientForwardedHeaders Set true to ignore Forwarded headers from the client request
     * @param sendLegacyForwardedHeaders    Set true to send X-Forwarded-* headers along with Forwarded headers
     * @param viaValue                      The value to set on the Via header, for example <code>HTTP/1.1 myserver</code>
     * @return Returns true if the client request has a body; otherwise false.
     */
    static boolean setTargetRequestHeaders(URI target, MuRequest clientRequest, Headers targetRequestHeaders, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders, String viaValue, Set<String> excludedHeaders) {
        Headers reqHeaders = clientRequest.headers();
        List<String> customHopByHop = getCustomHopByHopHeaders(reqHeaders.get(HeaderNames.CONNECTION));

        boolean hasContentLength = false, hasTransferEncoding = false, hasHost = false;
        for (Map.Entry<String, String> clientHeader : reqHeaders) {
            String key = clientHeader.getKey();
            String lowKey = key.toLowerCase();
            hasContentLength |= lowKey.equals("content-length");
            hasTransferEncoding |= lowKey.equals("transfer-encoding");
            if (excludedHeaders.contains(lowKey) || customHopByHop.contains(lowKey)) {
                continue;
            }
            hasHost |= lowKey.equals("host");
            targetRequestHeaders.add(key, clientHeader.getValue());
        }

        if (!hasHost) {
            targetRequestHeaders.set(HeaderNames.HOST, target.getAuthority());
        }
        if (hasTransferEncoding) {
            targetRequestHeaders.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
        }

        String newViaValue = getNewViaValue(viaValue, clientRequest.headers().getAll(HeaderNames.VIA));
        targetRequestHeaders.add(HttpHeaderNames.VIA, newViaValue);

        setForwardedHeaders(clientRequest, targetRequestHeaders, discardClientForwardedHeaders, sendLegacyForwardedHeaders);

        return hasContentLength || hasTransferEncoding;
    }

    static String getNewViaValue(String viaValue, List<String> previousViasList) {
        String previousVias = String.join(", ", previousViasList);
        if (!previousVias.isEmpty()) previousVias += ", ";
        return previousVias + viaValue;
    }


    /**
     * Sets Forwarded and optionally X-Forwarded-* headers to the target request, based on the client request
     *
     * @param clientRequest                 the received client request
     * @param targetRequestHeaders          the target request to write the headers to
     * @param discardClientForwardedHeaders if <code>true</code> then existing Forwarded headers on the client request will be discarded (normally false, unless you do not trust the upstream system)
     * @param sendLegacyForwardedHeaders    if <code>true</code> then X-Forwarded-Proto/Host/For headers will also be added
     */
    static void setForwardedHeaders(MuRequest clientRequest, Headers targetRequestHeaders, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders) {
        List<ForwardedHeader> forwardHeaders;
        if (discardClientForwardedHeaders) {
            forwardHeaders = Collections.emptyList();
        } else {
            forwardHeaders = clientRequest.headers().forwarded();
            for (ForwardedHeader existing : forwardHeaders) {
                targetRequestHeaders.add(FORWARDED, existing.toString());
            }
        }

        ForwardedHeader newForwarded = createForwardedHeader(clientRequest);
        targetRequestHeaders.add(FORWARDED, newForwarded.toString());

        if (sendLegacyForwardedHeaders) {
            ForwardedHeader first = forwardHeaders.isEmpty() ? newForwarded : forwardHeaders.get(0);
            setXForwardedHeaders(targetRequestHeaders, first);
        }
    }

    /**
     * Sets X-Forwarded-Proto, X-Forwarded-Host and X-Forwarded-For on the request given the forwarded header.
     *
     * @param targetRequestHeaders The request headers to add the headers to
     * @param forwardedHeader      The forwarded header that has the original client information on it.
     */
    private static void setXForwardedHeaders(Headers targetRequestHeaders, ForwardedHeader forwardedHeader) {
        targetRequestHeaders.add(X_FORWARDED_PROTO, forwardedHeader.proto());
        targetRequestHeaders.add(X_FORWARDED_HOST, forwardedHeader.host());
        targetRequestHeaders.add(X_FORWARDED_FOR, forwardedHeader.forValue());
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

    static List<String> getCustomHopByHopHeaders(String connectionHeaderValue) {
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

    @Override
    public void close() throws IOException {
        try {
            pool.shutdown().get();
        } catch (InterruptedException e) {
            // allow it
        } catch (ExecutionException e) {
            throw new IOException("Error shutting down reverse proxy connection pool", e.getCause());
        }
    }
}

class ProxyExchange {
    private ProxyExchangeState state = ProxyExchangeState.NOT_STARTED;
    final long start = System.currentTimeMillis();
    final MuRequest clientReq;
    final MuResponse clientResp;
    final AsyncHandle asyncHandle;
    final URI target;
    final HttpMethod targetMethod;
    final String viaValue;
    private final long idleTimeoutMillis;
    private Consumer<ServerErrorException> timeoutListener;

    ProxyExchange(MuRequest clientReq, MuResponse clientResp, AsyncHandle asyncHandle, URI target, HttpMethod targetMethod, String viaValue, long idleTimeoutMillis) {
        this.clientReq = clientReq;
        this.clientResp = clientResp;
        this.asyncHandle = asyncHandle;
        this.target = target;
        this.targetMethod = targetMethod;
        this.viaValue = viaValue;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    public void setTimeoutListener(Consumer<ServerErrorException> timeoutListener) {
        this.timeoutListener = timeoutListener;
    }

    @Override
    public String toString() {
        return "ProxyExchange{" +
            "start=" + start +
            ", clientReq=" + clientReq +
            ", clientResp=" + clientResp +
            ", asyncHandle=" + asyncHandle +
            ", target=" + target +
            ", targetMethod=" + targetMethod +
            '}';
    }

    public ProxyExchangeState state() {
        return state;
    }

    public void setState(ProxyExchangeState newState) {
        if (this.state.endState()) {
            throw new IllegalStateException("Cannot set the state (to " + newState + ") when the current state is " + this.state);
        }
        this.state = newState;
    }

    private ScheduledFuture<?> totalTimeoutTimer;
    private ScheduledFuture<?> idleTimeoutTimer;
    private static final Logger log = LoggerFactory.getLogger(ProxyExchange.class);

    private void onTimeout() {
        log.info("onTimeout!");
        Consumer<ServerErrorException> tl = this.timeoutListener;
        if (tl != null) {
            log.info("Sending event");
            tl.accept(new ServerErrorException("The target service took too long to respond", 504));
        }
    }

    void startTotalTimeoutTimer(ChannelHandlerContext ctx, long timeout) {
        totalTimeoutTimer = ctx.executor().schedule(this::onTimeout, timeout, TimeUnit.MILLISECONDS);
    }

    void restartIdleTimer(ChannelHandlerContext ctx) {
        cancelTimer(idleTimeoutTimer);
        idleTimeoutTimer = ctx.executor().schedule(this::onTimeout, idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    void cancelTimeouts() {
        idleTimeoutTimer = cancelTimer(idleTimeoutTimer);
        totalTimeoutTimer = cancelTimer(totalTimeoutTimer);
    }

    private ScheduledFuture<?> cancelTimer(ScheduledFuture<?> current) {
        if (current != null) {
            current.cancel(true);
        }
        return null;
    }
}

enum ProxyExchangeState {
    NOT_STARTED(false), IN_PROGRESS(false), COMPLETED(true), TIMED_OUT(true), ERRORED(true);
    private final boolean endState;

    ProxyExchangeState(boolean endState) {
        this.endState = endState;
    }

    public boolean endState() {
        return this.endState;
    }
}

class ProxyConnection extends ChannelDuplexHandler {
    private static final Logger log = LoggerFactory.getLogger(ProxyConnection.class);
    private final long start = System.currentTimeMillis();
    ChannelHandlerContext ctx;
    private final ProxySettings settings;
    private volatile ProxyExchange curExchange;
    long connectionTimeMillis = -1;
    private final ProxyConnectionListener readyToUseListener;
    private final ProxyConnectionListener connectionClosedListener;
    private ScheduledFuture<?> idleConnectionTimeout;

    public ProxyConnection(ProxySettings settings, ProxyConnectionListener readyToUseListener, ProxyConnectionListener connectionClosedListener) {
        this.settings = settings;
        this.readyToUseListener = readyToUseListener;
        this.connectionClosedListener = connectionClosedListener;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.ctx = ctx;
        ctx.channel().config().setAutoRead(false);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        connectionTimeMillis = System.currentTimeMillis() - start;
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        cancelTimers();
        connectionClosedListener.accept(ctx, this);
    }

    void sendRequestToTarget(ProxyExchange exchange) {
        cancelTimers();
        curExchange = exchange;
        exchange.setState(ProxyExchangeState.IN_PROGRESS);
        exchange.setTimeoutListener(e -> onCompleted(exchange, e, ProxyExchangeState.TIMED_OUT));
        exchange.startTotalTimeoutTimer(ctx, settings.totalTimeoutInMillis);
        Headers targetReqHeaders = Headers.http1Headers();

        boolean hasRequestBody = ReverseProxy.setTargetRequestHeaders(exchange.target, exchange.clientReq, targetReqHeaders, settings.discardClientForwardedHeaders, settings.sendLegacyForwardedHeaders, exchange.viaValue, settings.doNotProxyHeaders);
        if (settings.requestInterceptor != null) {
            ProxyRequestInfo info = new ProxyRequestInfoImpl(exchange.clientReq, targetReqHeaders, exchange.target);
            try {
                settings.requestInterceptor.intercept(info);
            } catch (Exception e) {
                log.warn("Exception thrown by request interceptor", e); // TODO: fire exception on channel
            }
        }
        String uri = Murp.pathAndQuery(exchange.target);
        HttpRequest targetRequest = hasRequestBody
            ? new DefaultHttpRequest(HttpVersion.HTTP_1_1, exchange.targetMethod, uri, toNettyHeaders(targetReqHeaders))
            : new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, exchange.targetMethod, uri, Unpooled.EMPTY_BUFFER, toNettyHeaders(targetReqHeaders), EmptyHttpHeaders.INSTANCE);


        log.info("Going to send " + targetRequest + " (" + hasRequestBody + ")");


        if (hasRequestBody) {
            exchange.asyncHandle.setReadListener(new RequestBodyListener() {
                @Override
                public void onDataReceived(ByteBuffer buffer, DoneCallback done) {
                    exchange.restartIdleTimer(ctx);
                    log.info("client sent " + buffer.remaining() + " bytes");
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(buffer)))
                        .addListener(f -> done.onComplete(f.cause())); // TODO: verify this will call onError and so cancel the async handle
                }

                @Override
                public void onComplete() {
                    ctx.writeAndFlush(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                }

                @Override
                public void onError(Throwable t) {
                    ctx.fireExceptionCaught(t); // TODO test this
                }
            });
        }
        ctx.writeAndFlush(targetRequest);
        exchange.restartIdleTimer(ctx);
        ctx.channel().read();
    }

    private void cancelTimers() {
        if (idleConnectionTimeout != null) {
            idleConnectionTimeout.cancel(false);
        }
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ProxyExchange ce = this.curExchange;
        if (ce == null) {
            log.info("Message received when no exchange in progress: " + msg);
            ctx.channel().close();
            return;
        }
        ce.restartIdleTimer(ctx);
        if (msg instanceof HttpResponse) {
            HttpResponse targetResp = (HttpResponse) msg;

            ce.clientResp.status(targetResp.status().code());
            setClientResponseHeaders(targetResp, ce);
            if (settings.responseInterceptor != null) {
                try {
                    ProxyResponseInfo info = new ProxyResponseInfoImpl(ce.clientReq, ce.clientResp);
                    settings.responseInterceptor.intercept(info);
                } catch (Exception e) {
                    log.error("Error while intercepting the response. The response will still be proxied.", e);
                }
            }
            ctx.channel().read();

        } else if (msg instanceof HttpContent) {

            HttpContent content = (HttpContent) msg;
            boolean isLast = msg instanceof LastHttpContent;
            ByteBuf bb = content.content();
            if (bb.readableBytes() > 0) {
                ce.asyncHandle.write(bb.nioBuffer(), error -> {
                    if (isLast) {
                        onCompleted(ce, null, ProxyExchangeState.COMPLETED);
                    } else {
                        ctx.channel().read();
                    }
                });
            } else if (isLast) {
                onCompleted(ce, null, ProxyExchangeState.COMPLETED);
            }
        } else {
            super.channelRead(ctx, msg);
            ctx.channel().read();
        }

    }

    private void raiseProxyCompleteEvent(ProxyExchange exchange) {
        if (!settings.proxyCompleteListeners.isEmpty()) {
            long duration = System.currentTimeMillis() - start;
            ProxyCompleteInfo info = new ProxyCompleteInfoImpl(exchange, duration);
            for (ProxyCompleteListener proxyCompleteListener : settings.proxyCompleteListeners) {
                try {
                    proxyCompleteListener.onComplete(info);
                } catch (Exception e) {
                    log.warn(proxyCompleteListener + " threw an error while processing onComplete", e);
                }
            }
        }
    }

    private void onCompleted(ProxyExchange exchange, Throwable error, ProxyExchangeState newState) {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> onCompleted(exchange, error, newState));
            return;
        }
        assert newState.endState() : "Invalid end state: " + newState;
        if (exchange.state().endState()) {
            return;
        }
        exchange.cancelTimeouts();
        exchange.setState(newState);
        exchange.asyncHandle.complete(error);
        raiseProxyCompleteEvent(exchange);
        if (newState == ProxyExchangeState.COMPLETED) {
            this.idleConnectionTimeout = ctx.executor().schedule(this::onIdleConnectionTimeout, settings.idleConnectionTimeoutInMillis, TimeUnit.MILLISECONDS);
            readyToUseListener.accept(ctx, this);
        } else {
            ctx.channel().close(); // i.e. cancel the in-progress request between this reverse proxy server and the target server (even if the connection to the client may be fine and still open)
        }
    }

    private void onIdleConnectionTimeout() {
        log.info("Connection pool timeout for connection");
        ctx.channel().close();
    }

    private void setClientResponseHeaders(HttpResponse targetResp, ProxyExchange exchange) {
        HttpHeaders targetRespHeaders = targetResp.headers();
        targetRespHeaders.remove(HeaderNames.DATE); // so that the target's date can be used
        List<String> customHopByHopHeaders = ReverseProxy.getCustomHopByHopHeaders(targetRespHeaders.get(HttpHeaderNames.CONNECTION));
        Iterator<Map.Entry<String, String>> it = targetRespHeaders.iteratorAsString();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String headerName = entry.getKey();
            String lowerName = headerName.toLowerCase();
            if (ReverseProxy.HOP_BY_HOP_HEADERS.contains(lowerName) || customHopByHopHeaders.contains(lowerName)) {
                continue;
            }
            String value = entry.getValue();
            exchange.clientResp.headers().add(headerName, value);
        }
        String newVia = ReverseProxy.getNewViaValue(exchange.viaValue, targetRespHeaders.getAll(HttpHeaderNames.VIA));
        exchange.clientResp.headers().set(HeaderNames.VIA, newVia);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (curExchange != null) {
            onCompleted(curExchange, cause, ProxyExchangeState.ERRORED);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent) {
            SslHandshakeCompletionEvent shce = (SslHandshakeCompletionEvent) evt;
            // TODO: why was I checking for this?
        }
        log.info("Got user event! " + evt);
        super.userEventTriggered(ctx, evt);
    }

    private static HttpHeaders toNettyHeaders(Headers headers) {
        // TODO: the mu implementation is backed by a netty header so just get that directly rather than copy them
        HttpHeaders nh = new DefaultHttpHeaders();
        for (Map.Entry<String, String> header : headers) {
            nh.add(header.getKey(), header.getValue());
        }
        return nh;
    }

}
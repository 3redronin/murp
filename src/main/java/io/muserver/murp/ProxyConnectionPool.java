package io.muserver.murp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Comparator;
import java.util.concurrent.*;

class ProxyConnectionPool {

    private final ConcurrentHashMap<String, ProxyDestination> destinations = new ConcurrentHashMap<>();
    private final Bootstrap bootstrap;
    private final ProxySettings settings;
    private final NioEventLoopGroup group;

    ProxyConnectionPool(ProxySettings settings) {
        this.settings = settings;
        this.group = new NioEventLoopGroup();

        this.bootstrap = new Bootstrap()
            .group(group)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.connectionTimeoutMillis)
            .channel(NioSocketChannel.class);
    }

    public int poolSize(URI destination) {
        ProxyDestination dest = destinations.get(ProxyDestination.key(destination));
        if (dest == null) {
            return 0;
        }
        return dest.idleConnections();
    }

    CompletionStage<ProxyConnection> getConnection(URI target, int maxUrlSize, int maxRequestHeadersSize) {
        ProxyDestination dest = destinations.computeIfAbsent(ProxyDestination.key(target), k -> ProxyDestination.create(target));
        return dest.connectOrGet(group, bootstrap, settings, maxUrlSize, maxRequestHeadersSize);
    }

    public io.netty.util.concurrent.Future<?> shutdown() {
        return bootstrap.config().group().shutdownGracefully();
    }

}

class ProxyConnectionQueueItem {
    private final CompletableFuture<ProxyConnection> pc;
    private final ScheduledFuture<?> timeout;

    ProxyConnectionQueueItem(CompletableFuture<ProxyConnection> pc, ScheduledFuture<?> timeout) {
        this.pc = pc;
        this.timeout = timeout;
    }

    public void complete(ProxyConnection proxyConnection) {
        timeout.cancel(false);
        pc.complete(proxyConnection);
    }
}

class ProxyDestination {
    private static final Logger log = LoggerFactory.getLogger(ProxyDestination.class);
    final String protocol;

    private final InetSocketAddress address;
    final PriorityBlockingQueue<ProxyConnection> pool = new PriorityBlockingQueue<>(4, Comparator.comparingLong(o -> o.connectionTimeMillis));
    private final ConcurrentLinkedQueue<ProxyConnectionQueueItem> connectionQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap.KeySetView<ProxyConnection, Boolean> inUse = ConcurrentHashMap.newKeySet();

    private ProxyDestination(InetSocketAddress address, String protocol) {
        this.address = address;
        this.protocol = protocol;
    }

    int idleConnections() {
        return pool.size();
    }
    int inProgressConnections() {
        return inUse.size();
    }
    int connectionQueueSize() {
        return connectionQueue.size();
    }

    static String key(URI uri) {
        return uri.getScheme() + ":" + uri.getHost() + ":" + uri.getPort();
    }

    // TODO: thread management - these are coming in on different threads
    public CompletionStage<ProxyConnection> connectOrGet(NioEventLoopGroup group, Bootstrap bootstrap, ProxySettings settings, int maxUrlSize, int maxRequestHeadersSize) {
        ProxyConnection existing = pool.poll();
        if (existing != null) {
            log.info("Reusing existing connection");
            inUse.add(existing);
            return CompletableFuture.completedFuture(existing);
        } else if (inUse.size() >= settings.maxConnectionsPerDestination) {
            log.info("Too many connections to " + address + " so going into queue which was size " + connectionQueueSize());
            CompletableFuture<ProxyConnection> stage = new CompletableFuture<>();
            ScheduledFuture<?> timeout = group.schedule(() -> {
                log.info("Timed out in queue");
                stage.completeExceptionally(new TimeoutException("Timed out waiting for an available connection"));
            }, settings.connectionTimeoutMillis, TimeUnit.MILLISECONDS);
            connectionQueue.add(new ProxyConnectionQueueItem(stage, timeout));
            return stage;
        } else {
            log.info("Creating new connection " + inUse.size());
            CompletableFuture<ProxyConnection> stage = new CompletableFuture<>();
            ProxyConnection pc = new ProxyConnection(settings, this::onConnectionReady, this::onConnectionClosed);
            inUse.add(pc);
            bootstrap.clone()
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (protocol.equals("https")) {
                            p.addLast("ssl", settings.sslCtx.newHandler(ch.alloc()));
                        }
                        p.addLast("codec", new HttpClientCodec(maxUrlSize + 17, maxRequestHeadersSize, HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE));
                        p.addLast("cfc", new FlowControlHandler()); // TODO: confirm this is needed
                        p.addLast("murp", pc);
                    }
                })
                .connect(address)
                .addListener(f -> {
                    if (f.isSuccess()) {
                        stage.complete(pc);
                    } else {
                        stage.completeExceptionally(f.cause());
                    }
                });
            return stage;
        }
    }

    private void onConnectionReady(ChannelHandlerContext ctx, ProxyConnection proxyConnection) {
        ProxyConnectionQueueItem nextInLine = connectionQueue.poll();
        if (nextInLine == null) {
            inUse.remove(proxyConnection);
            pool.add(proxyConnection);
        } else {
            log.info("Queue reduced!");
            nextInLine.complete(proxyConnection);
        }
    }

    private void onConnectionClosed(ChannelHandlerContext ctx, ProxyConnection proxyConnection) {
        pool.remove(proxyConnection);
        inUse.remove(proxyConnection);
    }

    static ProxyDestination create(URI target) {
        log.info("Creating destination for " + target);
        return new ProxyDestination(new InetSocketAddress(target.getHost(), target.getPort()), target.getScheme());
    }
}

@FunctionalInterface
interface ProxyConnectionListener {
    void accept(ChannelHandlerContext ctx, ProxyConnection proxyConnection);
}

package io.muserver.murp;

import io.muserver.MuServer;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.Http2ConfigBuilder.http2Config;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.request;

public class TargetFailureHttp2ProxyTest {

    private ManualTargetServer targetServer;
    private MuServer reverseProxyServer;

    @After
    public void stopServers() {
        closeQuietly(targetServer);
        targetServer = null;
        if (reverseProxyServer != null) {
            MuAssert.stopAndCheck(reverseProxyServer);
            reverseProxyServer = null;
        }
    }

    @Test
    public void slowUploadTimeoutResetsHttp2StreamAndConnectionCanBeReused() throws Exception {
        AtomicInteger targetRequestCount = new AtomicInteger(0);
        targetServer = startTarget((socket, input, output) -> {
            int requestNumber = targetRequestCount.incrementAndGet();
            RequestHead requestHead = readRequestHead(input);
            if (requestNumber == 1) {
                assertThat(requestHead.method, is("POST"));
                assertThat(requestHead.contentLength, is(greaterThan(5)));
                readExactly(input, 5);
                socket.setSoLinger(true, 0);
                socket.close();
                return;
            }

            assertThat(requestHead.method, is("GET"));
            writeAscii(output,
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "ok");
        });
        startReverseProxy(5_000);

        Set<Integer> connections = ConcurrentHashMap.newKeySet();
        OkHttpClient client = scaffolding.ClientUtils.client.newBuilder()
            .eventListenerFactory(call -> new EventListener() {
                @Override
                public void connectionAcquired(okhttp3.Call call, Connection connection) {
                    connections.add(System.identityHashCode(connection));
                }
            })
            .build();

        RequestBody slowBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return 3_000;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                for (int i = 0; i < 300; i++) {
                    sink.writeUtf8("0123456789");
                    sink.flush();
                    sleep(20);
                }
            }
        };

        try (Response first = execute(client, request(reverseProxyServer.uri().resolve("/slow-upload-timeout")).post(slowBody))) {
            assertThat(first.code(), is(502));
            assertThat(first.protocol().name(), is("HTTP_2"));
            assertThat(first.body().string(), is("502 Bad Gateway"));
        }

        try (Response second = execute(client, request(reverseProxyServer.uri().resolve("/after-error")))) {
            assertThat(second.code(), is(200));
            assertThat(second.protocol().name(), is("HTTP_2"));
            assertThat(second.body().string(), is("ok"));
        }
        assertThat(targetRequestCount.get(), is(2));
        assertThat("connection should stay reusable after stream failure", connections.size(), is(1));
    }

    @Test
    public void targetDisconnectAfterUploadBodySentButBeforeResponseReturns502OverHttp2() throws IOException {
        AtomicInteger targetRequestCount = new AtomicInteger(0);
        targetServer = startTargetUnchecked((socket, input, output) -> {
            int requestNumber = targetRequestCount.incrementAndGet();
            RequestHead requestHead = readRequestHead(input);
            if (requestNumber == 1) {
                assertThat(requestHead.method, is("POST"));
                assertThat(requestHead.contentLength, is(11));
                readExactly(input, requestHead.contentLength);
                socket.close();
                return;
            }

            assertThat(requestHead.method, is("GET"));
            writeAscii(output,
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "ok");
        });
        startReverseProxy(5_000);

        try (Response response = execute(scaffolding.ClientUtils.client,
            request(reverseProxyServer.uri().resolve("/upload")).post(bodyOf("hello world")))) {
            assertThat(response.code(), is(502));
            assertThat(response.protocol().name(), is("HTTP_2"));
            assertThat(response.body().string(), is("502 Bad Gateway"));
        }

        try (Response second = execute(scaffolding.ClientUtils.client, request(reverseProxyServer.uri().resolve("/after-error")))) {
            assertThat(second.code(), is(200));
            assertThat(second.protocol().name(), is("HTTP_2"));
            assertThat(second.body().string(), is("ok"));
        }
        assertThat(targetRequestCount.get(), is(2));
    }

    @Test
    public void targetTimeoutBeforeResponseAfterUploadReturns504OverHttp2() throws IOException {
        AtomicInteger targetRequestCount = new AtomicInteger(0);
        targetServer = startTargetUnchecked((socket, input, output) -> {
            int requestNumber = targetRequestCount.incrementAndGet();
            RequestHead requestHead = readRequestHead(input);
            if (requestNumber == 1) {
                assertThat(requestHead.method, is("POST"));
                readExactly(input, requestHead.contentLength);
                sleep(1_500);
                return;
            }

            assertThat(requestHead.method, is("GET"));
            writeAscii(output,
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "ok");
        });
        startReverseProxy(300);

        try (Response response = execute(scaffolding.ClientUtils.client,
            request(reverseProxyServer.uri().resolve("/upload-timeout")).post(bodyOf("hello world")))) {
            assertThat(response.code(), is(504));
            assertThat(response.protocol().name(), is("HTTP_2"));
            assertThat(response.body().string(), is("504 Gateway Timeout"));
        }

        try (Response second = execute(scaffolding.ClientUtils.client, request(reverseProxyServer.uri().resolve("/after-error")))) {
            assertThat(second.code(), is(200));
            assertThat(second.protocol().name(), is("HTTP_2"));
            assertThat(second.body().string(), is("ok"));
        }
        assertThat(targetRequestCount.get(), is(2));
    }

    @Test
    public void targetMalformedResponseBytesBeforeHeadersReturns502OverHttp2() throws IOException {
        AtomicInteger targetRequestCount = new AtomicInteger(0);
        targetServer = startTargetUnchecked((socket, input, output) -> {
            int requestNumber = targetRequestCount.incrementAndGet();
            readRequestHead(input);
            if (requestNumber == 1) {
                writeAscii(output, "NOT HTTP\r\n\r\n");
                socket.close();
                return;
            }

            writeAscii(output,
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "ok");
        });
        startReverseProxy(5_000);

        try (Response response = execute(scaffolding.ClientUtils.client, request(reverseProxyServer.uri().resolve("/malformed")))) {
            assertThat(response.code(), is(502));
            assertThat(response.protocol().name(), is("HTTP_2"));
            assertThat(response.body().string(), is("502 Bad Gateway"));
        }

        try (Response second = execute(scaffolding.ClientUtils.client, request(reverseProxyServer.uri().resolve("/after-error")))) {
            assertThat(second.code(), is(200));
            assertThat(second.protocol().name(), is("HTTP_2"));
            assertThat(second.body().string(), is("ok"));
        }
        assertThat(targetRequestCount.get(), is(2));
    }

    @Test
    public void targetClosesDuringFixedLengthResponseBodyFailsHttp2Stream() throws IOException {
        AtomicInteger targetRequestCount = new AtomicInteger(0);
        targetServer = startTargetUnchecked((socket, input, output) -> {
            int requestNumber = targetRequestCount.incrementAndGet();
            readRequestHead(input);
            if (requestNumber == 1) {
                writeAscii(output,
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 10\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n" +
                        "hello");
                socket.close();
                return;
            }

            writeAscii(output,
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n" +
                    "ok");
        });
        startReverseProxy(5_000);

        try (Response response = execute(scaffolding.ClientUtils.client, request(reverseProxyServer.uri().resolve("/fixed-length-close")))) {
            assertThat(response.code(), is(200));
            assertThat(response.protocol().name(), is("HTTP_2"));
            assertThrows(IOException.class, () -> response.body().string());
        }

        try (Response second = execute(scaffolding.ClientUtils.client, request(reverseProxyServer.uri().resolve("/after-error")))) {
            assertThat(second.code(), is(200));
            assertThat(second.protocol().name(), is("HTTP_2"));
            assertThat(second.body().string(), is("ok"));
        }
        assertThat(targetRequestCount.get(), is(2));
    }

    private void startReverseProxy(long timeoutMillis) {
        reverseProxyServer = httpsServer()
            .withHttp2Config(http2Config().enabled(true))
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withTotalTimeout(timeoutMillis))
            .start();
    }

    private static ManualTargetServer startTarget(ConnectionHandler handler) throws IOException {
        return new ManualTargetServer(handler);
    }

    private static ManualTargetServer startTargetUnchecked(ConnectionHandler handler) {
        try {
            return startTarget(handler);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private interface ConnectionHandler {
        void handle(Socket socket, InputStream input, OutputStream output) throws Exception;
    }

    private static class ManualTargetServer implements Closeable {
        private final ServerSocket serverSocket;
        private final ExecutorService executor;
        private final ConnectionHandler handler;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private ManualTargetServer(ConnectionHandler handler) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.executor = Executors.newCachedThreadPool();
            this.handler = handler;
            this.executor.submit(this::acceptLoop);
        }

        private void acceptLoop() {
            try {
                while (!closed.get()) {
                    Socket socket = serverSocket.accept();
                    executor.submit(() -> handle(socket));
                }
            } catch (IOException ignored) {
            }
        }

        private void handle(Socket socket) {
            try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
                handler.handle(socket, input, output);
            } catch (Exception ignored) {
            }
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + serverSocket.getLocalPort());
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            executor.shutdownNow();
        }
    }

    private static RequestHead readRequestHead(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int current = input.read();
            if (current == -1) {
                throw new EOFException("Connection closed before request headers completed");
            }
            buffer.write(current);
            if ((matched == 0 || matched == 2) && current == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && current == '\n') {
                matched++;
            } else {
                matched = 0;
            }
        }
        String headers = buffer.toString(ISO_8859_1);
        return new RequestHead(parseMethod(headers), parseContentLength(headers));
    }

    private static String parseMethod(String headers) {
        String[] lines = headers.split("\\r\\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new AssertionError("Expected request start line, but headers were:\n" + headers);
        }
        return lines[0].split(" ", 3)[0];
    }

    private static int parseContentLength(String headers) {
        for (String line : headers.split("\\r\\n")) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            if ("content-length".equalsIgnoreCase(name)) {
                return Integer.parseInt(line.substring(separator + 1).trim());
            }
        }
        return -1;
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(ISO_8859_1));
        output.flush();
    }

    private static byte[] readExactly(InputStream input, int byteCount) throws IOException {
        byte[] bytes = new byte[byteCount];
        int offset = 0;
        while (offset < byteCount) {
            int read = input.read(bytes, offset, byteCount - offset);
            if (read == -1) {
                throw new EOFException("Connection closed after reading " + offset + " of " + byteCount + " body bytes");
            }
            offset += read;
        }
        return bytes;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RequestBody bodyOf(String value) {
        byte[] bytes = value.getBytes(UTF_8);
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("text/plain; charset=utf-8");
            }

            @Override
            public long contentLength() {
                return bytes.length;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                sink.write(bytes);
            }
        };
    }

    private static Response execute(OkHttpClient client, okhttp3.Request.Builder requestBuilder) {
        okhttp3.Request request = requestBuilder.build();
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            throw new RuntimeException("Error while calling " + request, e);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static class RequestHead {
        private final String method;
        private final int contentLength;

        private RequestHead(String method, int contentLength) {
            this.method = method;
            this.contentLength = contentLength;
        }
    }
}

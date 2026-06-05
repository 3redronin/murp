package io.muserver.murp;

import io.muserver.MuServer;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.RawClient;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.murp.ReverseProxyBuilder.createHttpClientBuilder;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

public class TargetFailureProxyTest {

    private static final HttpClient client = createHttpClientBuilder(true).build();

    private ManualTargetServer targetServer;
    private MuServer reverseProxyServer;
    private boolean skipActiveRequestCheckOnStop;

    @After
    public void stopServers() {
        closeQuietly(targetServer);
        targetServer = null;
        if (reverseProxyServer != null) {
            if (skipActiveRequestCheckOnStop) {
                reverseProxyServer.stop();
            } else {
                MuAssert.stopAndCheck(reverseProxyServer);
            }
        }
        reverseProxyServer = null;
        skipActiveRequestCheckOnStop = false;
    }

    @Test
    public void targetDisconnectDuringUploadBeforeAnyResponseReturns502() throws Exception {
        targetServer = startTarget((socket, input, output) -> {
            RequestHead requestHead = readRequestHead(input);
            assertThat(requestHead.contentLength, is(greaterThan(0)));
            readExactly(input, 5);
            socket.close();
        });
        startReverseProxy();

        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
            .uri(reverseProxyServer.uri().resolve("/upload"))
            .POST(streamingBodyPublisher(List.of("abcdef", "ghijkl", "mnopqr"), 100))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode(), is(502));
        assertThat(response.body(), containsString("502 Bad Gateway"));
    }

    @Test
    public void targetDisconnectAfterUploadBodySentButBeforeResponseReturns502() throws Exception {
        String body = "abcdefghijklmnopqrstuvwxyz";
        targetServer = startTarget((socket, input, output) -> {
            RequestHead requestHead = readRequestHead(input);
            readExactly(input, requestHead.contentLength);
            socket.close();
        });
        startReverseProxy();

        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
            .uri(reverseProxyServer.uri().resolve("/upload"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode(), is(502));
        assertThat(response.body(), containsString("502 Bad Gateway"));
    }

    @Test
    public void targetDisconnectDuringUploadReturns502EvenIfClientHasNotFinishedSendingBody() throws Exception {
        targetServer = startTarget((socket, input, output) -> {
            RequestHead requestHead = readRequestHead(input);
            assertThat(requestHead.contentLength, is(greaterThan(10)));
            readExactly(input, 5);
            socket.setSoLinger(true, 0);
            socket.close();
        });
        startReverseProxy();

        try (RawClient rawClient = RawClient.create(reverseProxyServer.uri())
            .sendStartLine("POST", "/upload")
            .sendHeader("Host", reverseProxyServer.uri().getAuthority())
            .sendHeader("Content-Type", "application/octet-stream")
            .sendHeader("Content-Length", "1000000")
            .endHeaders()
            .sendUTF8("abcdefghij")
            .flushRequest()) {

            assertContainsWithin(rawClient::responseString, "502 Bad Gateway", 3_000);
        }
    }

    @Test
    public void targetDisconnectDuringChunkedUploadReturnsValid502AndClientConnectionCanBeReused() throws Exception {
        skipActiveRequestCheckOnStop = true;
        AtomicInteger targetRequestCount = new AtomicInteger();
        targetServer = startTarget((socket, input, output) -> {
            int requestNumber = targetRequestCount.incrementAndGet();
            RequestHead requestHead = readRequestHead(input);
            if (requestNumber == 1) {
                assertThat(requestHead.method, is("POST"));
                assertThat(requestHead.transferEncoding, is("chunked"));
                assertThat(new String(readChunk(input), UTF_8), is("hello"));
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
        startReverseProxy();

        try (Socket clientSocket = new Socket(reverseProxyServer.uri().getHost(), reverseProxyServer.uri().getPort())) {
            clientSocket.setSoTimeout(3_000);
            OutputStream output = clientSocket.getOutputStream();
            InputStream input = clientSocket.getInputStream();

            writeAscii(output,
                "POST /upload HTTP/1.1\r\n" +
                    "Host: " + reverseProxyServer.uri().getAuthority() + "\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n");
            writeChunk(output, "hello");
            sleep(100);
            for (int i = 0; i < 32; i++) {
                writeChunk(output, "remaining-chunk-" + i);
            }
            writeLastChunk(output);

            ResponseHead firstResponse;
            try {
                firstResponse = readResponseHead(input);
            } catch (SocketTimeoutException timeoutException) {
                throw new AssertionError("Expected murp to send a valid 502 response after the completed chunked upload even though the target disconnected, but no HTTP response arrived", timeoutException);
            }
            assertThat(firstResponse.statusCode, is(502));
            assertThat(readResponseBody(input, firstResponse), containsString("502 Bad Gateway"));

            writeAscii(output,
                "GET /after-error HTTP/1.1\r\n" +
                    "Host: " + reverseProxyServer.uri().getAuthority() + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

            ResponseHead secondResponse = readResponseHead(input);
            assertThat(secondResponse.statusCode, is(200));
            assertThat(readResponseBody(input, secondResponse), is("ok"));
            assertThat(targetRequestCount.get(), is(2));
            skipActiveRequestCheckOnStop = false;
        }
    }

    @Test
    public void targetTimeoutDuringUploadBeforeAnyResponseReturns504() throws Exception {
        targetServer = startTarget((socket, input, output) -> {
            readRequestHead(input);
            readExactly(input, 5);
            sleep(300);
        });
        startReverseProxy(50);

        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
            .uri(reverseProxyServer.uri().resolve("/upload"))
            .POST(streamingBodyPublisher(List.of("abcdef", "ghijkl", "mnopqr"), 100))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode(), is(504));
        assertThat(response.body(), containsString("504 Gateway Timeout"));
    }

    @Test
    public void targetDisconnectDuringResponseBodyFailsClientRequest() throws Exception {
        targetServer = startTarget((socket, input, output) -> {
            readRequestHead(input);
            writeAscii(output,
                "HTTP/1.1 200 OK\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "5\r\n" +
                    "hello\r\n");
            socket.close();
        });
        startReverseProxy();

        IOException exception = assertThrows(IOException.class, () ->
            client.send(HttpRequest.newBuilder()
                .uri(reverseProxyServer.uri().resolve("/download"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString())
        );

        assertThat(exception.getMessage(), containsString("chunked transfer encoding"));
    }

    @Test
    public void targetTimeoutDuringResponseBodyFailsClientRequest() throws Exception {
        targetServer = startTarget((socket, input, output) -> {
            readRequestHead(input);
            writeAscii(output,
                "HTTP/1.1 200 OK\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "5\r\n" +
                    "hello\r\n");
            sleep(300);
        });
        startReverseProxy(50);

        IOException exception = assertThrows(IOException.class, () ->
            client.send(HttpRequest.newBuilder()
                .uri(reverseProxyServer.uri().resolve("/download"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString())
        );

        assertThat(exception.getMessage(), containsString("chunked transfer encoding"));
    }

    private void startReverseProxy() {
        startReverseProxy(5_000);
    }

    private void startReverseProxy(long timeoutMillis) {
        reverseProxyServer = httpServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withTotalTimeout(timeoutMillis))
            .start();
    }

    private static HttpRequest.BodyPublisher streamingBodyPublisher(List<String> chunks, long delayBetweenChunksMillis) {
        long contentLength = chunks.stream().mapToLong(chunk -> chunk.getBytes(UTF_8).length).sum();
        return HttpRequest.BodyPublishers.fromPublisher(subscriber -> {
            ConcurrentLinkedDeque<byte[]> remaining = new ConcurrentLinkedDeque<>();
            for (String chunk : chunks) {
                remaining.add(chunk.getBytes(UTF_8));
            }
            AtomicBoolean cancelled = new AtomicBoolean(false);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (cancelled.get()) {
                        return;
                    }
                    if (n <= 0) {
                        return;
                    }
                    byte[] next = remaining.poll();
                    if (next == null) {
                        subscriber.onComplete();
                        return;
                    }
                    subscriber.onNext(ByteBuffer.wrap(next));
                    if (!remaining.isEmpty() && delayBetweenChunksMillis > 0) {
                        sleep(delayBetweenChunksMillis);
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }, contentLength);
    }

    private static ManualTargetServer startTarget(ConnectionHandler handler) throws IOException {
        return new ManualTargetServer(handler);
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
        return new RequestHead(parseMethod(headers), parseContentLength(headers), parseTransferEncoding(headers));
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

    private static String parseTransferEncoding(String headers) {
        for (String line : headers.split("\\r\\n")) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            if ("transfer-encoding".equalsIgnoreCase(name)) {
                return line.substring(separator + 1).trim();
            }
        }
        return null;
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

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(ISO_8859_1));
        output.flush();
    }

    private static void writeChunk(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(UTF_8);
        writeAscii(output, Integer.toHexString(bytes.length) + "\r\n");
        output.write(bytes);
        writeAscii(output, "\r\n");
    }

    private static void writeLastChunk(OutputStream output) throws IOException {
        writeAscii(output, "0\r\n\r\n");
    }

    private static byte[] readChunk(InputStream input) throws IOException {
        int chunkLength = Integer.parseInt(readLine(input), 16);
        byte[] chunk = readExactly(input, chunkLength);
        expectCrlf(input);
        return chunk;
    }

    private static ResponseHead readResponseHead(InputStream input) throws IOException {
        String statusLine = readLine(input);
        if (statusLine.isEmpty()) {
            throw new EOFException("Expected HTTP response status line but got an empty line");
        }
        String[] parts = statusLine.split(" ", 3);
        int statusCode = Integer.parseInt(parts[1]);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readLine(input)).isEmpty()) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            headers.put(line.substring(0, separator).trim().toLowerCase(), line.substring(separator + 1).trim());
        }
        return new ResponseHead(statusCode, headers);
    }

    private static String readResponseBody(InputStream input, ResponseHead responseHead) throws IOException {
        if ("chunked".equalsIgnoreCase(responseHead.headers.get("transfer-encoding"))) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                int chunkLength = Integer.parseInt(readLine(input), 16);
                if (chunkLength == 0) {
                    expectCrlf(input);
                    return body.toString(UTF_8);
                }
                body.write(readExactly(input, chunkLength));
                expectCrlf(input);
            }
        }
        String contentLength = responseHead.headers.get("content-length");
        if (contentLength == null) {
            return "";
        }
        return new String(readExactly(input, Integer.parseInt(contentLength)), UTF_8);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int current = input.read();
            if (current == -1) {
                throw new EOFException("Connection closed while waiting for a CRLF-delimited line");
            }
            if (current == '\r') {
                int next = input.read();
                if (next == -1) {
                    throw new EOFException("Connection closed after carriage return");
                }
                if (next == '\n') {
                    return buffer.toString(ISO_8859_1);
                }
                buffer.write(current);
                buffer.write(next);
                continue;
            }
            buffer.write(current);
        }
    }

    private static void expectCrlf(InputStream input) throws IOException {
        int carriageReturn = input.read();
        int lineFeed = input.read();
        if (carriageReturn != '\r' || lineFeed != '\n') {
            throw new IOException("Expected CRLF but got " + carriageReturn + ", " + lineFeed);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertContainsWithin(ThrowingSupplier<String> supplier, String expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String current = supplier.get();
            if (current.contains(expected)) {
                return;
            }
            sleep(25);
        }
        assertThat(supplier.get(), containsString(expected));
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
        private final String transferEncoding;

        private RequestHead(String method, int contentLength, String transferEncoding) {
            this.method = method;
            this.contentLength = contentLength;
            this.transferEncoding = transferEncoding;
        }
    }

    private static class ResponseHead {
        private final int statusCode;
        private final LinkedHashMap<String, String> headers;

        private ResponseHead(int statusCode, LinkedHashMap<String, String> headers) {
            this.statusCode = statusCode;
            this.headers = headers;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}







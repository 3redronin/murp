package io.muserver.murp;

import io.muserver.*;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MockMuRequest implements MuRequest {
    private final URI requestUri;

    public MockMuRequest(URI uri) {
        this.requestUri = uri;
    }

    @Override
    public String contentType() {
        return null;
    }

    @Override
    public long startTime() {
        return System.currentTimeMillis();
    }

    @Override
    public Method method() {
        return null;
    }

    @Override
    public URI uri() {
        return requestUri;
    }

    @Override
    public URI serverURI() {
        return requestUri;
    }

    @Override
    public Headers headers() {
        return null;
    }

    @Override
    public Optional<InputStream> inputStream() {
        return Optional.empty();
    }

    @Override
    public String readBodyAsString() {
        return null;
    }

    @Override
    public List<UploadedFile> uploadedFiles(String name) {
        return null;
    }

    @Override
    public UploadedFile uploadedFile(String name) {
        return null;
    }

    @Override
    public RequestParameters query() {
        return null;
    }

    @Override
    public RequestParameters form() {
        return null;
    }

    @Override
    public List<Cookie> cookies() {
        return null;
    }

    @Override
    public Optional<String> cookie(String name) {
        return Optional.empty();
    }

    @Override
    public String contextPath() {
        return null;
    }

    @Override
    public String relativePath() {
        return null;
    }

    @Override
    public Object attribute(String key) {
        return null;
    }

    @Override
    public void attribute(String key, Object value) {

    }

    @Override
    public Map<String, Object> attributes() {
        return null;
    }

    @Override
    public AsyncHandle handleAsync() {
        return null;
    }

    @Override
    public String remoteAddress() {
        return null;
    }

    @Override
    public String clientIP() {
        return null;
    }

    @Override
    public MuServer server() {
        return null;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public String protocol() {
        return null;
    }

    @Override
    public HttpConnection connection() {
        return null;
    }
}

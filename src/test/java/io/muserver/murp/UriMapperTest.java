package io.muserver.murp;

import io.muserver.*;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

public class UriMapperTest {

    @Test
    public void canJustForwardEverythingToANewDomain() throws Exception {
        UriMapper mapper = UriMapper.toDomain(URI.create("http://localhost:1234/ignored?ignore=yes"));

        assertThat(mapper.mapFrom(mockRequest(URI.create("https://murp.example.org"))),
            equalTo(URI.create("http://localhost:1234/")));

        assertThat(mapper.mapFrom(mockRequest(URI.create("https://murp.example.org/"))),
            equalTo(URI.create("http://localhost:1234/")));

        assertThat(mapper.mapFrom(mockRequest(URI.create("https://murp.example.org/mm%20hmm"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm")));

        assertThat(mapper.mapFrom(mockRequest(URI.create("https://murp.example.org/mm%20hmm?blah=yee%20ha"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm?blah=yee%20ha")));

        assertThat(mapper.mapFrom(mockRequest(URI.create("https://murp.example.org/mm%20hmm?blah=yee%20ha&err=umm"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm?blah=yee%20ha&err=umm")));

        assertThat(mapper.mapFrom(mockRequest(URI.create("https://murp.example.org/mm%20hmm?blah=yee%20ha&err=umm#hashisignored"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm?blah=yee%20ha&err=umm")));
    }


    private MuRequest mockRequest(URI requestUri) {
        return new MuRequest() {
            @Override
            public String contentType() {
                return null;
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
            public String readBodyAsString() throws IOException {
                return null;
            }

            @Override
            public List<UploadedFile> uploadedFiles(String name) throws IOException {
                return null;
            }

            @Override
            public UploadedFile uploadedFile(String name) throws IOException {
                return null;
            }

            @Override
            public RequestParameters query() {
                return null;
            }

            @Override
            public RequestParameters form() throws IOException {
                return null;
            }

            @Override
            public String parameter(String name) {
                return null;
            }

            @Override
            public List<String> parameters(String name) {
                return null;
            }

            @Override
            public String formValue(String name) throws IOException {
                return null;
            }

            @Override
            public List<String> formValues(String name) throws IOException {
                return null;
            }

            @Override
            public Set<Cookie> cookies() {
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
            public Object state() {
                return null;
            }

            @Override
            public void state(Object value) {

            }

            @Override
            public Object attribute(String key) {
                return null;
            }

            @Override
            public void attribute(String key, Object value) {

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
            public MuServer server() {
                return null;
            }

            @Override
            public boolean isAsync() {
                return false;
            }
        };
    }

}
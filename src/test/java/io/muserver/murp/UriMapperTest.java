package io.muserver.murp;

import org.junit.Test;

import java.net.URI;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

public class UriMapperTest {

    @Test
    public void canJustForwardEverythingToANewDomain() throws Exception {
        UriMapper mapper = UriMapper.toDomain(URI.create("http://localhost:1234/ignored?ignore=yes"));

        assertThat(mapper.mapFrom(new MockMuRequest(URI.create("https://murp.example.org"))),
            equalTo(URI.create("http://localhost:1234/")));

        assertThat(mapper.mapFrom(new MockMuRequest(URI.create("https://murp.example.org/"))),
            equalTo(URI.create("http://localhost:1234/")));

        assertThat(mapper.mapFrom(new MockMuRequest(URI.create("https://murp.example.org/mm%20hmm"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm")));

        assertThat(mapper.mapFrom(new MockMuRequest(URI.create("https://murp.example.org/mm%20hmm?blah=yee%20ha"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm?blah=yee%20ha")));

        assertThat(mapper.mapFrom(new MockMuRequest(URI.create("https://murp.example.org/mm%20hmm?blah=yee%20ha&err=umm"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm?blah=yee%20ha&err=umm")));

        assertThat(mapper.mapFrom(new MockMuRequest(URI.create("https://murp.example.org/mm%20hmm?blah=yee%20ha&err=umm#hashisignored"))),
            equalTo(URI.create("http://localhost:1234/mm%20hmm?blah=yee%20ha&err=umm")));
    }
}

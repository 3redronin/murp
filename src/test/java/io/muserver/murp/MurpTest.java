package io.muserver.murp;

import org.junit.Test;

import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

public class MurpTest {

    @Test
    public void canGetPathAndQueries() {
        assertThat(Murp.pathAndQuery(URI.create("http://localhost/this/is/a%20path")),
            equalTo("/this/is/a%20path"));

        assertThat(Murp.pathAndQuery(URI.create("http://localhost/a%20path?a%20param=a%20value")),
            equalTo("/a%20path?a%20param=a%20value"));

        assertThat(Murp.pathAndQuery(URI.create("http://localhost/a%20path?")),
            equalTo("/a%20path?"));
    }

    @Test
    public void versionWorks() {
        assertThat(Murp.artifactVersion(), startsWith("0."));
    }

}
package io.muserver.murp;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;

public class MurpTest {

    @Test
    public void versionWorks() {
        assertThat(Murp.artifactVersion(), startsWith("0."));
    }

}
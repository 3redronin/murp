package io.muserver.murp;

import io.muserver.MuServer;

import java.io.InputStream;
import java.util.Properties;

public class Murp {

    private static final String version;
    static {
        String v;
        try {
            Properties props = new Properties();
            InputStream in = MuServer.class.getResourceAsStream("/META-INF/maven/io.muserver/murp/pom.properties");
            if (in == null) {
                v = "0.x";
            } else {
                try {
                    props.load(in);
                } finally {
                    in.close();
                }
                v = props.getProperty("version");
            }
        } catch (Exception ex) {
            v = "0.x";
        }
        version = v;
    }

    /**
     * @return Returns the current version of Murp, or 0.x if unknown
     */
    public static String artifactVersion() {
        return version;
    }

}

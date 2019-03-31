package io.muserver.murp;

import io.muserver.MuServer;

import java.io.InputStream;
import java.net.URI;
import java.util.Properties;

/**
 * Some utilities for the reverse proxy. If you want to create a reverse proxy, use {@link ReverseProxyBuilder#reverseProxy()}
 */
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

    /**
     * <p>Given a gets the raw path and (if present) querystring portion of a URI.</p>
     * <p>Note: paths and querystrings are not URL decoded.</p>
     * @param uri The URI to get the info from
     * @return A string such as <code>/path?query=something</code>
     */
    public static String pathAndQuery(URI uri) {
        String pathAndQuery = uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            pathAndQuery += "?" + rawQuery;
        }
        return pathAndQuery;
    }
}

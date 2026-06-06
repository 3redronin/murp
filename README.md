![GitHub](https://img.shields.io/github/license/3redronin/murp)
[![Build and test](https://github.com/3redronin/murp/actions/workflows/ci.yaml/badge.svg)](https://github.com/3redronin/murp/actions/workflows/ci.yaml)
[![Maven Central](https://img.shields.io/maven-central/v/io.muserver/murp)](https://central.sonatype.com/artifact/io.muserver/murp)

murp
----

A reverse proxy handler for [Mu Server](https://muserver.io).

## Quick Start

Add the latest dependencies...

````xml
<dependency>
    <groupId>io.muserver</groupId>
    <artifactId>mu-server</artifactId>
    <version>RELEASE</version>
</dependency>
<dependency>
    <groupId>io.muserver</groupId>
    <artifactId>murp</artifactId>
    <version>RELEASE</version>
</dependency>
````

...and start a server. In this example, an HTTPS server listening on port 8443
proxies all requests to `http://target.example.org:8080`

````java
import io.muserver.*;
import io.muserver.murp.*;

public class QuickStart {
    public static void main(String[] args) {
        
        URI target = URI.create("http://target.example.org:8080");
        
        MuServer server = MuServerBuilder.muServer()
            .withHttpsPort(8443)
            .addHandler(ReverseProxyBuilder.reverseProxy()
                .withUriMapper(UriMapper.toDomain(target))
            )
            .start();
        
        System.out.println("Started reverse proxy at " + server.uri());
    }
}
````

For more documentation and a larger example, see <https://muserver.io/murp>

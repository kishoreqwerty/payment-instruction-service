import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Phase 12 §1: "a null endpoint that accepts and discards". No parsing, no
 * validation, no persistence -- reads and drops the request body, replies
 * 200 immediately. Exists to measure k6's own client-side ceiling (VU loop
 * overhead, corpus read, HTTP connection handling) in isolation from the
 * application, so a later throughput number against the real pipeline can
 * be read against a known-good instrument rather than an unverified one.
 *
 * <p>Deliberately zero external dependencies: {@code com.sun.net.httpserver}
 * is part of the JDK, so this runs via {@code java NullEndpoint.java} (JDK
 * 21 single-file source launch) with nothing to add to any pom.xml.
 */
public class NullEndpoint {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/null", exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
                body.readAllBytes();
            }
            byte[] response = "{}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("NullEndpoint listening on :" + port + "/null");
    }
}

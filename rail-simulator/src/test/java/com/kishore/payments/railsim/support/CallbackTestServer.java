package com.kishore.payments.railsim.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A minimal JDK-only HTTP server standing in for whatever settlement-gateway
 * will eventually be at the other end of a rail's callback URL. Deliberately
 * not WireMock or any other test-only dependency: rail-simulator's own
 * isolation rule (no shared code, no shared libraries beyond the wire
 * format) extends to what its own tests pull in, and {@code
 * com.sun.net.httpserver} needs nothing extra.
 */
public class CallbackTestServer implements AutoCloseable {

    public record ReceivedCallback(String path, byte[] body) {
        public String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final List<ReceivedCallback> received = new CopyOnWriteArrayList<>();

    public CallbackTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start CallbackTestServer", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new ReceivedCallback(exchange.getRequestURI().getPath(), body));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/rail-callbacks";
    }

    public List<ReceivedCallback> received() {
        return List.copyOf(received);
    }

    /** Polls until at least {@code count} callbacks have arrived, or fails after {@code timeout}. */
    public List<ReceivedCallback> awaitAtLeast(int count, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (received.size() < count) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for " + count + " callback(s); only received " + received.size());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return received();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

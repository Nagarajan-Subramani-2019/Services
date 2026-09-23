package com.oxygenraj.userui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Disposable loopback-only stand-in: these tests never contact Oracle or a live user API. */
final class StubUserInfoServer implements AutoCloseable {
    static final String PASSWORD = "FixtureSecret27!";
    static final String USER = """
            {"id":17,"username":"sample.user","email":"sample@example.test",
             "phoneNumber":"+919876543210","role":"USER","enabled":true,
             "createdAt":"2026-09-23T00:00:00Z","modifiedAt":"2026-09-23T00:00:00Z"}
            """;
    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Reply> responder = ignored -> Reply.json(500, "{}");

    StubUserInfoServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start disposable HTTP fixture", exception);
        }
    }

    int port() { return server.getAddress().getPort(); }
    String origin() { return "http://127.0.0.1:" + port(); }
    List<Request> requests() { return List.copyOf(requests); }
    void reset() { requests.clear(); responder = ignored -> Reply.json(500, "{}"); }
    void reply(int status, String body) { respond(ignored -> Reply.json(status, body)); }
    void respond(Function<Request, Reply> next) { responder = next; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(request);
            Reply reply = responder.apply(request);
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override public void close() { server.stop(0); }

    record Request(String method, String path, String body, String authorization) {}
    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(int status, String body) {
            return new Reply(status, body, Map.of("Content-Type", "application/json"));
        }
    }
}

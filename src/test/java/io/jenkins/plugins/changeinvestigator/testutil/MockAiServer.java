package io.jenkins.plugins.changeinvestigator.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * A tiny local HTTP server standing in for an OpenAI-compatible endpoint, used only in tests
 * (never shipped as part of the plugin runtime). Uses only the JDK's built-in
 * {@link com.sun.net.httpserver.HttpServer} so no extra test dependency is needed.
 */
public final class MockAiServer implements AutoCloseable {

    private final HttpServer server;
    public volatile String lastRequestBody;
    public volatile String lastAuthorizationHeader;

    private MockAiServer(HttpServer server) {
        this.server = server;
    }

    public static MockAiServer start(String responseBody) throws IOException {
        return start(exchange -> responseBody);
    }

    public static MockAiServer start(Function<HttpExchange, String> responder) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MockAiServer mock = new MockAiServer(server);
        server.createContext("/chat/completions", exchange -> {
            try {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                exchange.getRequestBody().transferTo(body);
                mock.lastRequestBody = body.toString(StandardCharsets.UTF_8);
                mock.lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");

                String response = responder.apply(exchange);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return mock;
    }

    public static MockAiServer startWithStatus(int statusCode, String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MockAiServer mock = new MockAiServer(server);
        server.createContext("/chat/completions", exchange -> {
            try {
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return mock;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

package io.jenkins.plugins.changeinvestigator.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * A tiny local HTTP server standing in for any of this plugin's supported AI providers, used
 * only in tests (never shipped as part of the plugin runtime). Uses only the JDK's built-in
 * {@link com.sun.net.httpserver.HttpServer} so no extra test dependency is needed. Listens at
 * the root path so it matches whatever path/query string a given provider adapter constructs
 * (e.g. Azure's {@code /openai/deployments/{d}/chat/completions?api-version=...}, or Gemini's
 * {@code /v1beta/models/{m}:generateContent}), recording exactly what was requested so tests
 * can assert on it.
 */
public final class MockAiServer implements AutoCloseable {

    private final HttpServer server;
    public volatile String lastRequestBody;
    public volatile String lastAuthorizationHeader;
    public volatile String lastPath;
    public volatile HttpExchange lastExchange;

    private MockAiServer(HttpServer server) {
        this.server = server;
    }

    public static MockAiServer start(String responseBody) throws IOException {
        return start(exchange -> responseBody);
    }

    public static MockAiServer start(Function<HttpExchange, String> responder) throws IOException {
        return start(responder, 200);
    }

    public static MockAiServer start(Function<HttpExchange, String> responder, int statusCode) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MockAiServer mock = new MockAiServer(server);
        server.createContext("/", exchange -> {
            try {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                exchange.getRequestBody().transferTo(body);
                mock.lastRequestBody = body.toString(StandardCharsets.UTF_8);
                mock.lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
                mock.lastPath = exchange.getRequestURI().toString();
                mock.lastExchange = exchange;

                String response = responder.apply(exchange);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return mock;
    }

    public static MockAiServer startWithStatus(int statusCode, String responseBody) throws IOException {
        return start(exchange -> responseBody, statusCode);
    }

    /** Reads a request header from the most recently handled request (case-insensitive per HTTP semantics). */
    public String lastHeader(String name) {
        return lastExchange == null ? null : lastExchange.getRequestHeaders().getFirst(name);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

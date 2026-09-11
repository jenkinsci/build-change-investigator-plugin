package io.jenkins.plugins.changeinvestigator.notification.slack;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SlackTransportTest {
    private static final String TOKEN = "xoxb-synthetic-test-token";
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private SlackTransport transport;
    private final List<ObjectNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String response = "{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1234.000001\"}";
    private volatile boolean drop;
    private volatile long delay;
    private volatile boolean connection;
    private volatile boolean missingParent;
    private volatile boolean wrongAuthWorkspace;

    @BeforeEach
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/", exchange -> {
            methods.add(exchange.getRequestURI().getPath());
            ObjectNode request =
                    (ObjectNode) JSON.readTree(exchange.getRequestBody().readAllBytes());
            boolean posting = exchange.getRequestURI().getPath().endsWith("chat.postMessage");
            if (posting) requests.add(request);
            assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            if (posting && delay > 0)
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            if (posting && drop) {
                exchange.close();
                return;
            }
            String body = response;
            if (exchange.getRequestURI().getPath().endsWith("auth.test"))
                body = "{\"ok\":true,\"team_id\":\"T123\",\"bot_id\":\"B123\"}";
            if (wrongAuthWorkspace && exchange.getRequestURI().getPath().endsWith("auth.test"))
                body = "{\"ok\":true,\"team_id\":\"T999\",\"bot_id\":\"B123\"}";
            if (exchange.getRequestURI().getPath().endsWith("conversations.history"))
                body = "{\"ok\":true,\"messages\":[{\"ts\":\"1234.000001\"}]}";
            if (missingParent && exchange.getRequestURI().getPath().endsWith("conversations.history")) {
                assertEquals("1234.000001", request.path("oldest").asText());
                assertEquals(request.path("oldest"), request.path("latest"));
                assertEquals(1, request.path("limit").asInt());
                assertTrue(request.path("inclusive").asBoolean());
                body = "{\"ok\":true,\"messages\":[]}";
            }
            if (connection)
                body = exchange.getRequestURI().getPath().endsWith("auth.test")
                        ? "{\"ok\":true,\"team_id\":\"T123\",\"bot_id\":\"B123\"}"
                        : "{\"ok\":true,\"channel\":{\"id\":\"C123\",\"is_member\":true,\"is_archived\":false,\"context_team_id\":\"T123\"}}";
            exchange.getResponseHeaders().add("Retry-After", "45");
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:9/private");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(posting ? status : 200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        transport = new SlackTransport(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"), Duration.ofSeconds(2));
    }

    @AfterEach
    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private SlackTransport.Outcome send(String thread) {
        return transport.send(TOKEN, "T123", "C123", JSON.createObjectNode().put("text", "Synthetic failure"), thread);
    }

    @Test
    public void acceptedRootAndThreadReturnBoundReceiptsAndSafeFlags() {
        var root = send(null);
        assertEquals(SlackTransport.Status.SENT, root.status());
        assertEquals(new SlackTransport.Receipt("T123", "C123", "1234.000001"), root.receipt());
        assertEquals(
                SlackTransport.Status.SENT, send(root.receipt().messageTs()).status());
        assertFalse(requests.get(0).has("thread_ts"));
        assertEquals("1234.000001", requests.get(1).path("thread_ts").asText());
        for (ObjectNode request : requests) {
            assertFalse(request.path("reply_broadcast").asBoolean());
            assertFalse(request.path("link_names").asBoolean());
            assertFalse(request.path("unfurl_links").asBoolean());
            assertFalse(request.path("unfurl_media").asBoolean());
            assertEquals("none", request.path("parse").asText());
        }
    }

    @Test
    public void rateLimitHasSafeDeadlineAndNoInternalRetry() {
        status = 429;
        var result = send(null);
        assertEquals(SlackTransport.Status.RETRYABLE, result.status());
        assertEquals(45000, result.retryAfterMillis());
        assertEquals(1, requests.size());
    }

    @Test
    public void authorizationPayloadAndChannelFailuresStop() {
        for (int code : new int[] {401, 403, 400}) {
            status = code;
            assertEquals(SlackTransport.Status.PERMANENT_FAILURE, send(null).status());
        }
        status = 200;
        for (String error :
                List.of("invalid_auth", "invalid_channel", "channel_not_found", "invalid_blocks", "token_revoked")) {
            response = "{\"ok\":false,\"error\":\"" + error + "\",\"detail\":\"" + TOKEN + "\"}";
            var result = send(null);
            assertEquals(SlackTransport.Status.PERMANENT_FAILURE, result.status());
            assertFalse(result.toString().contains(TOKEN));
        }
    }

    @Test
    public void malformedDuplicateOversizeAndServerFailuresRemainUnknown() {
        for (String body :
                List.of("not JSON " + TOKEN, "{\"ok\":true,\"ok\":false}", "{\"ok\":true}", "x".repeat(70000))) {
            response = body;
            assertEquals(SlackTransport.Status.UNKNOWN_OUTCOME, send(null).status());
        }
        status = 500;
        response = "{\"ok\":false,\"error\":\"internal_error\"}";
        assertEquals(SlackTransport.Status.UNKNOWN_OUTCOME, send(null).status());
        status = 302;
        assertEquals(SlackTransport.Status.UNKNOWN_OUTCOME, send(null).status());
    }

    @Test
    public void wrongWorkspaceOrChannelNeverProducesReceipt() {
        for (String body : List.of(
                "{\"ok\":true,\"channel\":\"C999\",\"ts\":\"1234.000001\"}",
                "{\"ok\":true,\"channel\":\"C123\",\"team\":\"T999\",\"ts\":\"1234.000001\"}")) {
            response = body;
            var result = send(null);
            assertEquals(SlackTransport.Status.UNKNOWN_OUTCOME, result.status());
            assertNull(result.receipt());
        }
    }

    @Test
    public void droppedAcceptedRequestIsUnknownWithoutResend() {
        drop = true;
        assertEquals(SlackTransport.Status.UNKNOWN_OUTCOME, send(null).status());
        assertEquals(1, requests.size());
    }

    @Test
    public void slowAcceptanceAndSlowUnacceptedResponseBothRemainUnknown() {
        transport = new SlackTransport(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"), Duration.ofMillis(150));
        delay = 500;
        assertEquals(SlackTransport.Status.UNKNOWN_OUTCOME, send(null).status());
        drop = true;
        assertEquals(SlackTransport.Status.UNKNOWN_OUTCOME, send(null).status());
        assertEquals(2, requests.size());
    }

    @Test
    public void missingParentCannotFallBackToReplacementRoot() {
        response = "{\"ok\":false,\"error\":\"thread_not_found\"}";
        assertEquals(
                SlackTransport.Status.THREAD_UNAVAILABLE, send("1234.000001").status());
        assertEquals(1, requests.size());
    }

    @Test
    public void exactDeletedParentLookupPreventsAnyPost() {
        missingParent = true;
        assertEquals(
                SlackTransport.Status.THREAD_UNAVAILABLE, send("1234.000001").status());
        assertTrue(requests.isEmpty());
        assertEquals(List.of("/api/auth.test", "/api/conversations.history"), methods);
    }

    @Test
    public void rotatedCredentialCannotPostToDifferentWorkspace() {
        wrongAuthWorkspace = true;
        assertEquals(SlackTransport.Status.PERMANENT_FAILURE, send(null).status());
        assertTrue(requests.isEmpty());
    }

    @Test
    public void testConnectionUsesOnlyNonPostingBotAndChannelMethods() {
        connection = true;
        var result = transport.testConnection(TOKEN, "C123");
        assertTrue(result.usable());
        assertEquals("T123", result.workspaceId());
        assertEquals(List.of("/api/auth.test", "/api/conversations.info"), new ArrayList<>(methods));
    }

    @Test
    public void invalidLocalInputsCannotSendOrExposeToken() {
        assertEquals(
                SlackTransport.Status.PERMANENT_FAILURE,
                transport
                        .send(TOKEN + "\nsecret", "T123", "C123", JSON.createObjectNode(), null)
                        .status());
        assertEquals(SlackTransport.Status.PERMANENT_FAILURE, send("invalid").status());
        assertEquals(
                SlackTransport.Status.PERMANENT_FAILURE,
                transport
                        .send(TOKEN, "T123", "C123", JSON.createObjectNode().put("text", "x".repeat(70000)), null)
                        .status());
        assertTrue(requests.isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SlackTransport(URI.create("https://example.com/api/"), Duration.ofSeconds(1)));
    }

    @Test
    public void credentialEqualityPayloadIsWithheld() {
        assertEquals(
                "SECRET_PAYLOAD_WITHHELD",
                transport
                        .send(
                                TOKEN,
                                "T123",
                                "C123",
                                JSON.createObjectNode().put("text", "Commit contains " + TOKEN),
                                null)
                        .safeCode());
        assertTrue(requests.isEmpty());
    }

    @Test
    public void actualApiGateSpacesChannelAcrossMethodsAndRejectsDeadlineOverflow() throws Exception {
        long start = System.nanoTime();
        long deadline = start + Duration.ofSeconds(5).toNanos();
        SlackTransport.pace("TTESTPACE", "CTESTPACE", "conversations.history", deadline);
        SlackTransport.pace("TTESTPACE", "CTESTPACE", "chat.postMessage", deadline);
        assertTrue(System.nanoTime() - start >= Duration.ofMillis(900).toNanos());
        assertThrows(
                IllegalStateException.class,
                () -> SlackTransport.pace(
                        "TTESTPACE",
                        "CTESTPACE",
                        "chat.postMessage",
                        System.nanoTime() + Duration.ofMillis(50).toNanos()));
    }
}

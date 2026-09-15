package io.jenkins.plugins.changeinvestigator.slack.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SlackTransportTest {
    private static final String TOKEN = "xoxb-synthetic-transport-test";
    private static final SlackRoute ROUTE = new SlackRoute("T12345678", "C12345678");
    private HttpServer server;
    private SlackTransport transport;
    private final List<String> methods = new ArrayList<>();
    private String posted;
    private int postStatus = 200;
    private String postBody = "{\"ok\":true,\"channel\":\"C12345678\",\"ts\":\"1800000000.000001\"}";
    private String authBody =
            "{\"ok\":true,\"team_id\":\"T12345678\",\"team\":\"Demo Workspace\",\"bot_id\":\"B12345678\"}";
    private String infoBody = "{\"ok\":true,\"channel\":{\"id\":\"C12345678\",\"name\":\"alerts\",\"is_member\":true}}";
    private String userBody = "{\"ok\":true,\"user\":{\"id\":\"U12345678\",\"team_id\":\"T12345678\"}}";
    private String scopes = "chat:write,channels:read";
    private String rateLimitedMethod = "";
    private String retryDelay = "123";

    @BeforeEach
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", this::serve);
        server.start();
        transport = new SlackTransport(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"), id -> TOKEN);
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        methods.add(path);
        assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
        int status = 200;
        String body;
        if (!rateLimitedMethod.isEmpty() && path.endsWith(rateLimitedMethod)) {
            status = 429;
            body = "{\"ok\":false,\"error\":\"ratelimited\"}";
        } else if (path.endsWith("auth.test")) {
            assertEquals("POST", exchange.getRequestMethod());
            body = authBody;
            exchange.getResponseHeaders().set("x-oauth-scopes", scopes);
        } else if (path.endsWith("conversations.list")) {
            body = "{\"ok\":true,\"channels\":[{\"id\":\"C12345678\",\"name\":\"alerts\"}]}";
        } else if (path.endsWith("conversations.info")) {
            body = infoBody;
        } else if (path.endsWith("users.info")) {
            body = userBody;
        } else {
            posted = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            body = postBody;
            status = postStatus;
            if (status == 302) exchange.getResponseHeaders().set("Location", "/stolen");
        }
        if (status == 429) exchange.getResponseHeaders().set("Retry-After", retryDelay);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private DeliveryResult send(String thread) {
        return transport.post(
                "bot",
                ROUTE,
                "{\"text\":\"Demo failure\",\"blocks\":[],\"channel\":\"CATTACKER\",\"reply_broadcast\":true}",
                thread,
                UUID.randomUUID().toString());
    }

    @Test
    public void nameResolvesWithoutPosting() {
        ConnectionResult result = transport.checkConnection("bot", "#alerts");
        assertTrue(result.success(), result.safeMessage());
        assertEquals(ROUTE, result.route());
        assertEquals("Demo Workspace", result.workspaceName());
        assertEquals(3, methods.size());
        assertNull(posted);
    }

    @Test
    public void idAvoidsChannelListing() {
        assertTrue(transport.checkConnection("bot", "C12345678").success());
        assertEquals(2, methods.size());
    }

    @Test
    public void invalidChannelNeverReachesNetwork() {
        for (String channel : List.of("https://evil.invalid", "<!channel>", "#alerts&token=bad", "D12345678", "alerts"))
            assertFalse(transport.checkConnection("bot", channel).success());
        assertTrue(methods.isEmpty());
    }

    @Test
    public void requiresMembershipAndWriteScope() {
        infoBody = infoBody.replace("true}}", "false}}");
        assertFalse(transport.checkConnection("bot", "C12345678").success());
        scopes = "channels:read";
        assertFalse(transport.checkConnection("bot", "C12345678").success());
        assertNull(posted);
    }

    @Test
    public void acceptedMessageHasFixedRoutingAndThread() {
        assertEquals(DeliveryResult.Outcome.ACCEPTED, send("1800000000.000001").outcome());
        JSONObject body = JSONObject.fromObject(posted);
        assertEquals("C12345678", body.getString("channel"));
        assertEquals("1800000000.000001", body.getString("thread_ts"));
        assertFalse(body.containsKey("reply_broadcast"));
        assertFalse(body.getBoolean("unfurl_links"));
        assertFalse(body.getBoolean("link_names"));
        assertFalse(posted.contains(TOKEN));
    }

    @Test
    public void rateLimitHonorsServerDelay() {
        postStatus = 429;
        postBody = "{\"ok\":false,\"error\":\"ratelimited\"}";
        DeliveryResult result = send(null);
        assertEquals(DeliveryResult.Outcome.RETRY, result.outcome());
        assertEquals(123, result.retryAfterSeconds());
    }

    @Test
    public void connectionRateLimitsPreserveFullDelayFromEveryReadinessStep() {
        retryDelay = "172800";
        for (String method : List.of("auth.test", "conversations.list", "conversations.info")) {
            rateLimitedMethod = method;
            ConnectionResult result = transport.checkConnection("bot", "#alerts");
            assertFalse(result.success());
            assertEquals(172800, result.retryAfterSeconds(), method);
            assertNull(result.route());
        }
        assertNull(posted);
    }

    @Test
    public void postPreflightRateLimitNeverSubmitsMessage() {
        rateLimitedMethod = "auth.test";
        retryDelay = "172800";
        DeliveryResult result = send(null);
        assertEquals(DeliveryResult.Outcome.RETRY, result.outcome());
        assertEquals(172800, result.retryAfterSeconds());
        assertNull(posted);
    }

    @Test
    public void missingThreadNeverCreatesReplacementRoot() {
        postBody = "{\"ok\":false,\"error\":\"thread_not_found\"}";
        assertEquals(DeliveryResult.Outcome.PERMANENT, send("1800000000.000001").outcome());
        assertEquals(2, methods.size());
    }

    @Test
    public void transientPostFailureIsUncertainNotBlindRetry() {
        postStatus = 500;
        postBody = "internal failure";
        assertEquals(DeliveryResult.Outcome.UNKNOWN, send(null).outcome());
    }

    @Test
    public void malformedAcceptanceIsUnknown() {
        postBody = "{\"ok\":true,\"ts\":\"not-a-timestamp\"}";
        assertEquals(DeliveryResult.Outcome.UNKNOWN, send(null).outcome());
    }

    @Test
    public void duplicateOrDeepResponseCannotForgeAcceptance() {
        postBody = "{\"ok\":false,\"ok\":true,\"channel\":\"C12345678\",\"ts\":\"1800000000.000001\"}";
        assertEquals(DeliveryResult.Outcome.UNKNOWN, send(null).outcome());
        postBody = "{\"nested\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}";
        assertEquals(DeliveryResult.Outcome.UNKNOWN, send(null).outcome());
    }

    @Test
    public void connectionDisplayNeverEchoesSelectedSecret() {
        authBody = authBody.replace("Demo Workspace", TOKEN);
        ConnectionResult connection = transport.checkConnection("bot", "C12345678");
        assertTrue(connection.success());
        assertFalse(connection.toString().contains(TOKEN));
    }

    @Test
    void workspaceProofIsBoundToTheExactCredentialUsedForVerification() {
        var selected = new java.util.concurrent.atomic.AtomicReference<>(TOKEN);
        SlackTransport selectedTransport = new SlackTransport(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"), id -> selected.get());
        ConnectionResult verified = selectedTransport.checkConnection("selected-bot", "C12345678");
        assertTrue(verified.success());
        assertEquals("Demo Workspace", verified.workspaceName());
        assertTrue(verified.authenticationDigest().matches("[a-f0-9]{64}"));
        assertEquals(selectedTransport.credentialFingerprint("selected-bot"), verified.authenticationDigest());
        assertFalse(verified.toString().contains(TOKEN));
        selected.set("xoxb-synthetic-rotated-credential");
        assertNotEquals(selectedTransport.credentialFingerprint("selected-bot"), verified.authenticationDigest());
        assertEquals(2, methods.size(), "Local fingerprint checks must not contact Slack");
    }

    @Test
    void missingOrInvalidCredentialCannotProduceWorkspaceProof() {
        SlackTransport missing = new SlackTransport(URI.create("http://127.0.0.1/"), id -> null);
        SlackTransport user = new SlackTransport(URI.create("http://127.0.0.1/"), id -> "xoxp-synthetic-user-token");
        assertEquals("", missing.credentialFingerprint("missing"));
        assertEquals("", user.credentialFingerprint("user"));
        ConnectionResult failure = missing.checkConnection("missing", "#alerts");
        assertFalse(failure.success());
        assertEquals("", failure.authenticationDigest());
        assertTrue(methods.isEmpty());
    }

    @Test
    void safeConnectionErrorsNeverExposeChannelOrWorkspaceIds() {
        authBody = "{\"ok\":false,\"error\":\"invalid_auth\",\"team\":\"T12345678\"}";
        assertEquals(
                "Slack authentication failed. Check the selected credential.",
                transport.checkConnection("bot", "C12345678").safeMessage());
        authBody = "{\"ok\":true,\"team_id\":\"T12345678\",\"team\":\"Demo Workspace\",\"bot_id\":\"B12345678\"}";
        infoBody = infoBody.replace("true}}", "false}}");
        ConnectionResult inaccessible = transport.checkConnection("bot", "C12345678");
        assertFalse(inaccessible.success());
        assertFalse(inaccessible.safeMessage().contains("C12345678"));
        assertFalse(inaccessible.safeMessage().contains("T12345678"));
        assertEquals(
                "Unable to access #alerts with this bot credential.",
                transport.checkConnection("bot", "#alerts").safeMessage());
    }

    @Test
    public void redirectsAreNotFollowed() {
        postStatus = 302;
        postBody = "";
        assertEquals(DeliveryResult.Outcome.UNKNOWN, send(null).outcome());
        assertEquals(2, methods.size());
    }

    @Test
    public void oversizedResponseFailsSafely() {
        postBody = "x".repeat(262145);
        assertEquals(DeliveryResult.Outcome.UNKNOWN, send(null).outcome());
    }

    @Test
    public void workspaceChangePreventsPost() {
        authBody = authBody.replace("T12345678", "T87654321");
        assertEquals(DeliveryResult.Outcome.PERMANENT, send(null).outcome());
        assertNull(posted);
    }

    @Test
    public void secretsAndLinkageFailuresAreNotExposed() {
        SlackTransport broken = new SlackTransport(URI.create("http://127.0.0.1/"), id -> {
            throw new LinkageError(TOKEN);
        });
        ConnectionResult result = broken.checkConnection("bot", "#alerts");
        assertFalse(result.success());
        assertFalse(result.toString().contains(TOKEN));
        assertFalse(transport.isSafeToStore("bot", "failure " + TOKEN));
        assertTrue(transport.isSafeToStore("bot", "Synthetic failure"));
        assertEquals(
                DeliveryResult.Outcome.PERMANENT,
                transport
                        .post("bot", ROUTE, TOKEN, null, UUID.randomUUID().toString())
                        .outcome());
        assertNull(posted);
    }

    @Test
    public void onlyExplicitSameWorkspaceUserIsVerified() {
        assertTrue(transport.verifyMappedUser("bot", ROUTE, "U12345678"));
        assertFalse(transport.verifyMappedUser("bot", new SlackRoute("T87654321", "C12345678"), "U12345678"));
        assertFalse(transport.verifyMappedUser("bot", ROUTE, "<!channel>"));
        assertEquals(List.of("/api/users.info", "/api/users.info"), methods);
    }

    @Test
    void explicitMemberProofUsesActualCredentialWorkspaceAndOptionalFriendlyName() {
        userBody =
                "{\"ok\":true,\"user\":{\"id\":\"U12345678\",\"team_id\":\"T12345678\",\"profile\":{\"display_name\":\"Alex Morrison\"}}}";
        MemberVerification proof = transport.verifyMember("bot", "U12345678");
        assertTrue(proof.verified());
        assertEquals(MemberVerification.Status.VERIFIED, proof.status());
        assertEquals("U12345678", proof.userId());
        assertEquals("T12345678", proof.workspaceId());
        assertEquals(transport.credentialFingerprint("bot"), proof.authenticationDigest());
        assertEquals("Alex Morrison", proof.friendlyName());
        assertEquals(List.of("/api/auth.test", "/api/users.info"), methods);
        assertNull(posted);
    }

    @Test
    void explicitMemberRemainsVerifiedWithoutOptionalProfileName() {
        MemberVerification proof = transport.verifyMember("bot", "U12345678");
        assertTrue(proof.verified());
        assertEquals("", proof.friendlyName());
        assertEquals("U12345678", proof.userId());
    }

    @Test
    void deletedBotOrForeignWorkspaceMemberCannotBeVerified() {
        for (String value : List.of(
                "{\"id\":\"U12345678\",\"team_id\":\"T12345678\",\"deleted\":true}",
                "{\"id\":\"U12345678\",\"team_id\":\"T12345678\",\"is_bot\":true}",
                "{\"id\":\"U12345678\",\"team_id\":\"T87654321\"}",
                "{\"id\":\"U87654321\",\"team_id\":\"T12345678\"}")) {
            userBody = "{\"ok\":true,\"user\":" + value + "}";
            MemberVerification proof = transport.verifyMember("bot", "U12345678");
            assertFalse(proof.verified());
            assertEquals(MemberVerification.Status.INVALID, proof.status());
            assertEquals("", proof.friendlyName());
            assertEquals(transport.credentialFingerprint("bot"), proof.authenticationDigest());
            assertEquals("T12345678", proof.workspaceId());
            assertEquals("U12345678", proof.userId());
        }
    }

    @Test
    void optionalMemberLookupFailureStaysSafeAndDoesNotEnumerateUsers() {
        userBody = "{\"ok\":false,\"error\":\"missing_scope\",\"raw\":\"" + TOKEN + "\"}";
        MemberVerification unavailable = transport.verifyMember("bot", "U12345678");
        assertFalse(unavailable.verified());
        assertEquals(MemberVerification.Status.NEEDS_VALIDATION, unavailable.status());
        assertFalse(unavailable.toString().contains(TOKEN));
        assertEquals(List.of("/api/auth.test", "/api/users.info"), methods);
        methods.clear();
        assertFalse(transport.verifyMember("bot", "<!channel>").verified());
        assertTrue(methods.isEmpty());
        SlackTransport broken = new SlackTransport(URI.create("http://127.0.0.1/"), id -> {
            throw new LinkageError(TOKEN);
        });
        assertFalse(broken.verifyMember("bot", "U12345678").verified());
    }

    @Test
    void missingMemberIsInvalidButMalformedAndUnavailableResponsesNeedValidation() {
        userBody = "{\"ok\":false,\"error\":\"user_not_found\"}";
        MemberVerification missing = transport.verifyMember("bot", "U12345678");
        assertEquals(MemberVerification.Status.INVALID, missing.status());
        assertEquals(transport.credentialFingerprint("bot"), missing.authenticationDigest());
        assertFalse(missing.verified());
        for (String body : List.of(
                "{\"ok\":true}",
                "{\"ok\":true,\"user\":{}}",
                "{\"ok\":false,\"error\":\"ratelimited\"}",
                "{\"ok\":false,\"error\":\"internal_error\"}")) {
            userBody = body;
            MemberVerification result = transport.verifyMember("bot", "U12345678");
            assertEquals(MemberVerification.Status.NEEDS_VALIDATION, result.status());
            assertFalse(result.verified());
            assertEquals("", result.authenticationDigest());
        }
        methods.clear();
        assertEquals(
                MemberVerification.Status.INVALID,
                transport.verifyMember("bot", "not-a-member").status());
        assertTrue(methods.isEmpty());
    }

    @Test
    void memberFriendlyMetadataIsBoundedAndCannotEchoSelectedSecret() {
        JSONObject user = new JSONObject();
        user.put("id", "U12345678");
        user.put("team_id", "T12345678");
        user.put("profile", java.util.Map.of("display_name", TOKEN + "<>" + "x".repeat(200)));
        userBody = JSONObject.fromObject(java.util.Map.of("ok", true, "user", user))
                .toString();
        MemberVerification proof = transport.verifyMember("bot", "U12345678");
        assertTrue(proof.verified());
        assertTrue(proof.friendlyName().length() <= 80);
        assertFalse(proof.friendlyName().contains(TOKEN));
        assertFalse(proof.friendlyName().contains("<"));
        assertFalse(proof.friendlyName().contains(">"));
    }

    @Test
    public void rotatingBotTokenSyntaxIsSupportedWithoutAcceptingUserTokens() {
        SlackTransport rotating =
                new SlackTransport(URI.create("http://127.0.0.1/"), id -> "xoxe.xoxb-synthetic-rotating");
        assertTrue(rotating.isSafeToStore("bot", "Demo evidence"));
        assertFalse(rotating.isSafeToStore("bot", "xoxe.xoxb-synthetic-rotating"));
        SlackTransport userToken =
                new SlackTransport(URI.create("http://127.0.0.1/"), id -> "xoxe.xoxp-synthetic-user");
        assertFalse(userToken.isSafeToStore("bot", "Demo evidence"));
    }
}

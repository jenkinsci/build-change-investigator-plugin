package io.jenkins.plugins.changeinvestigator.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import java.lang.reflect.Field;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void hasNoTokenOrSecretField() {
        // Structural guardrail: the API token must be supplied only as a chatCompletion()
        // parameter, never retained as object state - see the javadoc on chatCompletion().
        for (Field field : OpenAiCompatibleClient.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            assertFalse(
                    name.contains("token") || name.contains("secret") || name.contains("password"),
                    "OpenAiCompatibleClient must not carry a secret-shaped field: " + field.getName());
        }
    }

    @Test
    void clientToStringNeverContainsToken() {
        AiProviderConfig config = new AiProviderConfig("http://127.0.0.1:12345", "m", 5, 0.2, 1000);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(config, objectMapper);
        assertFalse(client.toString().toLowerCase(Locale.ROOT).contains("token"));
    }

    @Test
    void sendsBearerTokenAndReturnsMessageContent() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"mostLikelyCause\\\":\\\"x\\\"}\"}}]}")) {

            AiProviderConfig config = new AiProviderConfig(mock.baseUrl(), "test-model", 5, 0.2, 1000);
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(config, objectMapper);

            String content = client.chatCompletion("system prompt", "user content", "s3cr3t-token");

            assertEquals("{\"mostLikelyCause\":\"x\"}", content);
            assertEquals("Bearer s3cr3t-token", mock.lastAuthorizationHeader);
            assertTrue(mock.lastRequestBody.contains("test-model"), mock.lastRequestBody);
        }
    }

    @Test
    void sameClientInstanceHandlesDifferentTokensIndependently() throws Exception {
        // Proves the client is stateless with respect to the token across calls, and that
        // neither call's token leaks into the other call's outcome.
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            AiProviderConfig config = new AiProviderConfig(mock.baseUrl(), "m", 5, 0.2, 1000);
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(config, objectMapper);

            client.chatCompletion("s", "u", "first-token");
            assertEquals("Bearer first-token", mock.lastAuthorizationHeader);

            client.chatCompletion("s", "u", "second-token");
            assertEquals("Bearer second-token", mock.lastAuthorizationHeader);
        }
    }

    @Test
    void throwsHttpErrorOnNon2xxStatus() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(500, "{\"error\":\"boom\"}")) {
            AiProviderConfig config = new AiProviderConfig(mock.baseUrl(), "m", 5, 0.2, 1000);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class,
                    () -> new OpenAiCompatibleClient(config, objectMapper).chatCompletion("s", "u", "token"));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
            assertFalse(ex.getMessage().contains("token"));
        }
    }

    @Test
    void throwsMalformedResponseWhenEnvelopeIsNotJson() throws Exception {
        try (MockAiServer mock = MockAiServer.start("not json")) {
            AiProviderConfig config = new AiProviderConfig(mock.baseUrl(), "m", 5, 0.2, 1000);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class,
                    () -> new OpenAiCompatibleClient(config, objectMapper).chatCompletion("s", "u", "token"));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsConnectionFailedWhenServerUnreachable() {
        // Nothing is listening on this port.
        AiProviderConfig config = new AiProviderConfig("http://127.0.0.1:1", "m", 2, 0.2, 1000);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class,
                () -> new OpenAiCompatibleClient(config, objectMapper).chatCompletion("s", "u", "token"));
        assertTrue(
                ex.getKind() == AiAnalysisException.Kind.CONNECTION_FAILED
                        || ex.getKind() == AiAnalysisException.Kind.TIMEOUT,
                ex.getKind().toString());
    }

    @Test
    void throwsCredentialsMissingWhenTokenAbsent() {
        AiProviderConfig config = new AiProviderConfig("http://127.0.0.1:12345", "m", 5, 0.2, 1000);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class,
                () -> new OpenAiCompatibleClient(config, objectMapper).chatCompletion("s", "u", ""));
        assertEquals(AiAnalysisException.Kind.CREDENTIALS_MISSING, ex.getKind());
    }

    @Test
    void throwsCredentialsMissingWhenTokenNull() {
        AiProviderConfig config = new AiProviderConfig("http://127.0.0.1:12345", "m", 5, 0.2, 1000);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class,
                () -> new OpenAiCompatibleClient(config, objectMapper).chatCompletion("s", "u", null));
        assertEquals(AiAnalysisException.Kind.CREDENTIALS_MISSING, ex.getKind());
    }
}

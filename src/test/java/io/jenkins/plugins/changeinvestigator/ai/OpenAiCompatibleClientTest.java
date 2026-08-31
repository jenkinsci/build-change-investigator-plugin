package io.jenkins.plugins.changeinvestigator.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsBearerTokenAndReturnsMessageContent() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"mostLikelyCause\\\":\\\"x\\\"}\"}}]}")) {

            AiProviderConfig config = new AiProviderConfig(mock.baseUrl(), "test-model", 5, 0.2, 1000);
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(config, "s3cr3t-token", objectMapper);

            String content = client.chatCompletion("system prompt", "user content");

            assertEquals("{\"mostLikelyCause\":\"x\"}", content);
            assertEquals("Bearer s3cr3t-token", mock.lastAuthorizationHeader);
            assertTrue(mock.lastRequestBody.contains("test-model"), mock.lastRequestBody);
        }
    }

    @Test
    void throwsHttpErrorOnNon2xxStatus() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(500, "{\"error\":\"boom\"}")) {
            AiProviderConfig config = new AiProviderConfig(mock.baseUrl(), "m", 5, 0.2, 1000);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class,
                    () -> new OpenAiCompatibleClient(config, "token", objectMapper).chatCompletion("s", "u"));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenEnvelopeIsNotJson() throws Exception {
        try (MockAiServer mock = MockAiServer.start("not json")) {
            AiProviderConfig config = new AiProviderConfig(mock.baseUrl(), "m", 5, 0.2, 1000);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class,
                    () -> new OpenAiCompatibleClient(config, "token", objectMapper).chatCompletion("s", "u"));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsConnectionFailedWhenServerUnreachable() {
        // Nothing is listening on this port.
        AiProviderConfig config = new AiProviderConfig("http://127.0.0.1:1", "m", 2, 0.2, 1000);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class,
                () -> new OpenAiCompatibleClient(config, "token", objectMapper).chatCompletion("s", "u"));
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
                () -> new OpenAiCompatibleClient(config, "", objectMapper).chatCompletion("s", "u"));
        assertEquals(AiAnalysisException.Kind.CREDENTIALS_MISSING, ex.getKind());
    }

    @Test
    void throwsCredentialsMissingWhenTokenNull() {
        AiProviderConfig config = new AiProviderConfig("http://127.0.0.1:12345", "m", 5, 0.2, 1000);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class,
                () -> new OpenAiCompatibleClient(config, null, objectMapper).chatCompletion("s", "u"));
        assertEquals(AiAnalysisException.Kind.CREDENTIALS_MISSING, ex.getKind());
    }
}

package io.jenkins.plugins.changeinvestigator.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link OpenAiChatCompletionsProvider}, the shared implementation behind
 * {@link OpenAiProviderConfig}, {@link OpenAiCompatibleProviderConfig}, and
 * {@link OllamaProviderConfig} - all three configure the identical OpenAI "chat completions"
 * wire contract, just with different defaults and credential requirements.
 */
class OpenAiChatCompletionsProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsBearerTokenAndReturnsMessageContent() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"mostLikelyCause\\\":\\\"x\\\"}\"}}]}")) {
            var provider = new OpenAiChatCompletionsProvider(
                    "OpenAI", mock.baseUrl(), "test-model", "s3cr3t-token", 5, objectMapper);

            AiAnalysisResult result =
                    provider.chatCompletion(new AiAnalysisRequest("system prompt", "user content", 0.2));

            assertEquals("{\"mostLikelyCause\":\"x\"}", result.responseText());
            assertEquals("OpenAI", result.providerDisplayName());
            assertEquals("test-model", result.model());
            assertEquals("Bearer s3cr3t-token", mock.lastAuthorizationHeader);
            assertTrue(mock.lastRequestBody.contains("test-model"));
            assertTrue(mock.lastRequestBody.contains("\"temperature\":0.2"));
            assertEquals("/chat/completions", mock.lastPath);
        }
    }

    @Test
    void omitsAuthorizationHeaderWhenTokenIsNull() throws Exception {
        // The Ollama/no-auth case: no credential configured must not send "Bearer null" or any
        // Authorization header at all.
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            var provider =
                    new OpenAiChatCompletionsProvider("Ollama", mock.baseUrl(), "llama3.1", null, 5, objectMapper);

            provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));

            assertNull(mock.lastAuthorizationHeader);
        }
    }

    @Test
    void omitsAuthorizationHeaderWhenTokenIsBlank() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            var provider =
                    new OpenAiChatCompletionsProvider("Ollama", mock.baseUrl(), "llama3.1", "  ", 5, objectMapper);

            provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));

            assertNull(mock.lastAuthorizationHeader);
        }
    }

    @Test
    void throwsHttpErrorOnNon2xxStatus() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(500, "{\"error\":\"boom\"}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
            assertFalse(ex.getMessage().toLowerCase(Locale.ROOT).contains("token"));
        }
    }

    @Test
    void throwsMalformedResponseWhenEnvelopeIsNotJson() throws Exception {
        try (MockAiServer mock = MockAiServer.start("not json")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenChoicesMissing() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"foo\":\"bar\"}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsConnectionFailedOrTimeoutWhenServerUnreachable() {
        var provider = new OpenAiChatCompletionsProvider("OpenAI", "http://127.0.0.1:1", "m", "token", 2, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertTrue(
                ex.getKind() == AiAnalysisException.Kind.CONNECTION_FAILED
                        || ex.getKind() == AiAnalysisException.Kind.TIMEOUT,
                ex.getKind().toString());
    }

    @Test
    void throwsConfigurationInvalidWhenBaseUrlMissing() {
        var provider = new OpenAiChatCompletionsProvider("OpenAI", null, "m", "token", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }
}

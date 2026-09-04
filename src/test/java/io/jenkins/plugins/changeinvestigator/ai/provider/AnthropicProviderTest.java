package io.jenkins.plugins.changeinvestigator.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import org.junit.jupiter.api.Test;

class AnthropicProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsCorrectHeadersAndRequestShape() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"model\":\"claude-sonnet-5\","
                + "\"stop_reason\":\"end_turn\"}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "sk-ant-secret", 5, objectMapper, mock.baseUrl());

            AiAnalysisResult result =
                    provider.chatCompletion(new AiAnalysisRequest("system prompt", "user content", 0.5));

            assertEquals("hello", result.responseText());
            assertEquals("Anthropic", result.providerDisplayName());
            assertEquals("claude-sonnet-5", result.model());

            assertEquals("sk-ant-secret", mock.lastHeader("x-api-key"));
            assertEquals(AnthropicProvider.API_VERSION, mock.lastHeader("anthropic-version"));
            assertNull(mock.lastAuthorizationHeader, "Anthropic uses x-api-key, never Authorization: Bearer");

            assertTrue(mock.lastRequestBody.contains("\"model\":\"claude-sonnet-5\""));
            assertTrue(mock.lastRequestBody.contains("\"max_tokens\":" + AnthropicProvider.DEFAULT_MAX_TOKENS));
            assertTrue(mock.lastRequestBody.contains("\"system\":\"system prompt\""));
            assertTrue(mock.lastRequestBody.contains("\"role\":\"user\""));
            assertTrue(mock.lastRequestBody.contains("user content"));
        }
    }

    @Test
    void blankOrNullBaseUrlFallsBackToTheDefaultAnthropicApiConstant() {
        // Construction with a blank/null override must not throw, and must resolve to the real
        // default rather than an empty/broken URL - verified directly against the constant
        // rather than by making a live call to the real Anthropic API.
        assertEquals("https://api.anthropic.com/v1", AnthropicProvider.DEFAULT_BASE_URL);
        new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, null);
        new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, "  ");
    }

    @Test
    void concatenatesMultipleTextContentBlocks() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"content\":[{\"type\":\"text\",\"text\":\"part1 \"},{\"type\":\"text\",\"text\":\"part2\"}]}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisResult result = provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertEquals("part1 part2", result.responseText());
        }
    }

    @Test
    void clampsTemperatureToAnthropicsZeroToOneRange() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            provider.chatCompletion(new AiAnalysisRequest("s", "u", 2.0));
            assertTrue(mock.lastRequestBody.contains("\"temperature\":1.0"));
        }
    }

    @Test
    void throwsCredentialsMissingWhenApiKeyAbsent() {
        var provider = new AnthropicProvider("claude-sonnet-5", null, 5, objectMapper, null);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CREDENTIALS_MISSING, ex.getKind());
    }

    @Test
    void throwsConfigurationInvalidWhenModelMissing() {
        var provider = new AnthropicProvider(null, "token", 5, objectMapper, null);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsHttpErrorOnAuthenticationError() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "bad-token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenContentArrayMissing() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"id\":\"msg_1\"}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenEnvelopeIsNotJson() throws Exception {
        try (MockAiServer mock = MockAiServer.start("not json")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }
}

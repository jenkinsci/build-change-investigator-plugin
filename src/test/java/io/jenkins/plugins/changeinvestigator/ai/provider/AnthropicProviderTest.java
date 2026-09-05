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
            assertEquals("application/json", mock.lastHeader("Content-Type"));
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
    void throwsAuthenticationFailedOnHttp401() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "bad-token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.AUTHENTICATION_FAILED, ex.getKind());
        }
    }

    @Test
    void throwsModelNotFoundOnHttp404() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                404,
                "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-test-model\"}}")) {
            var provider = new AnthropicProvider("claude-test-model", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MODEL_NOT_FOUND, ex.getKind());
        }
    }

    @Test
    void throwsRateLimitedOnHttp429() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate limited\"}}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.RATE_LIMITED, ex.getKind());
        }
    }

    @Test
    void throwsQuotaExceededWhenCreditBalanceIsTooLow() throws Exception {
        // Anthropic reports an exhausted account balance as HTTP 400 invalid_request_error with
        // a message that literally says so - not a distinct HTTP status - so this must be
        // detected from the message body rather than the (otherwise generic) 400 status.
        try (MockAiServer mock = MockAiServer.startWithStatus(
                400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"Your credit balance is too low to access the Claude API.\"}}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.QUOTA_EXCEEDED, ex.getKind());
        }
    }

    @Test
    void throwsHttpErrorOnGenericInvalidRequest() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens is"
                        + " required\"}}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
        }
    }

    @Test
    void throwsProviderUnavailableOnHttp500() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"internal error\"}}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    @Test
    void throwsProviderUnavailableOnAnthropicSpecificOverloadedStatus() throws Exception {
        // Anthropic's own "overloaded" signal is HTTP 529, a non-standard status only Anthropic
        // uses - falls into the shared >=500 default branch, same as a conventional 5xx.
        try (MockAiServer mock = MockAiServer.startWithStatus(
                529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}")) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    @Test
    void throwsInvalidEndpointOnMalformedBaseUrl() {
        // A space in the authority makes this an invalid URI - must not escape as a raw
        // IllegalArgumentException, but be reported as a safe, categorized configuration problem.
        var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, "http://bad host/v1");
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.INVALID_ENDPOINT, ex.getKind());
    }

    @Test
    void throwsConnectionFailedWhenHostUnreachable() {
        var provider = new AnthropicProvider("claude-sonnet-5", "token", 2, objectMapper, "http://127.0.0.1:1");
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONNECTION_FAILED, ex.getKind());
    }

    @Test
    void throwsTimeoutWhenServerIsSlowerThanConfiguredTimeout() throws Exception {
        try (MockAiServer mock = MockAiServer.start(exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"content\":[{\"type\":\"text\",\"text\":\"too late\"}]}";
        })) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 1, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.TIMEOUT, ex.getKind());
        }
    }

    @Test
    void boundsErrorBodySnippetToThreeHundredCharacters() throws Exception {
        String hugeBody = "{\"error\":\"" + "x".repeat(2000) + "\"}";
        try (MockAiServer mock = MockAiServer.startWithStatus(500, hugeBody)) {
            var provider = new AnthropicProvider("claude-sonnet-5", "token", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertTrue(
                    ex.getMessage().length() < 400,
                    "expected a bounded message, got length " + ex.getMessage().length());
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
    void throwsMalformedResponseWhenOnlyNonTextContentBlocksPresent() throws Exception {
        // A well-formed response whose only content block is something other than "text" (e.g.
        // a tool_use block) must not crash trying to extract text - reported as malformed/unusable
        // rather than propagating an unchecked exception.
        try (MockAiServer mock = MockAiServer.start(
                "{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"x\",\"input\":{}}]}")) {
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

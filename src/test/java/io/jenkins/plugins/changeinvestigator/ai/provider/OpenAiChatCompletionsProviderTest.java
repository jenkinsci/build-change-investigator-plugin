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
 * wire contract, just with different defaults and credential requirements. This is the
 * adversarial matrix for the OpenAI-family hardening pass: every HTTP status/body shape a real
 * OpenAI-compatible endpoint can plausibly return must map to a safe, correctly-categorized
 * {@link AiAnalysisException} - never an uncaught exception, never a leaked secret, never an
 * unbounded raw response dumped into a user-facing message.
 */
class OpenAiChatCompletionsProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Happy path ---

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

    // --- Credential handling: optional for Ollama/Generic, never sent as a literal "null" ---

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

    // --- Configuration errors caught before any network call ---

    @Test
    void throwsConfigurationInvalidWhenBaseUrlMissing() {
        var provider = new OpenAiChatCompletionsProvider("OpenAI", null, "m", "token", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsConfigurationInvalidWhenModelBlank() {
        // A blank model field is a configuration mistake, not something worth a network round
        // trip to discover - must be caught up front, the same way a missing base URL is.
        var provider =
                new OpenAiChatCompletionsProvider("OpenAI", "http://127.0.0.1:1", "   ", "token", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsConfigurationInvalidWhenModelNull() {
        var provider =
                new OpenAiChatCompletionsProvider("OpenAI", "http://127.0.0.1:1", null, "token", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsInvalidEndpointRatherThanCrashingWhenBaseUrlIsMalformed() {
        // A raw, un-percent-encoded space makes java.net.URI.create(...) throw an unchecked
        // IllegalArgumentException - this must be caught and converted to a safe, Kind-ed
        // AiAnalysisException rather than propagating as a raw RuntimeException.
        var provider = new OpenAiChatCompletionsProvider(
                "OpenAI", "http://exa mple.com/not a valid url", "m", "token", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.INVALID_ENDPOINT, ex.getKind());
    }

    // --- Network failures ---

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
    void throwsTimeoutWhenServerRespondsSlowerThanConfiguredTimeout() throws Exception {
        try (MockAiServer mock = MockAiServer.start(exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"choices\":[{\"message\":{\"content\":\"too late\"}}]}";
        })) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 1, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.TIMEOUT, ex.getKind());
        }
    }

    // --- URL construction ---

    @Test
    void constructsUrlWithoutDoubleSlashWhenBaseUrlHasTrailingSlash() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            var provider =
                    new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl() + "/", "m", "token", 5, objectMapper);
            provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertEquals(
                    "/chat/completions",
                    mock.lastPath,
                    "a trailing slash on the base URL must not produce //chat/completions");
        }
    }

    // --- HTTP status -> Kind mapping ---

    @Test
    void throwsHttpErrorOnHttp400() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(400, "{\"error\":\"bad request\"}")) {
            var provider =
                    new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "s3cr3t-token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
            assertFalse(ex.getMessage().toLowerCase(Locale.ROOT).contains("s3cr3t-token"));
        }
    }

    @Test
    void throwsAuthenticationFailedOnHttp401() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(401, "{\"error\":{\"message\":\"invalid api key\"}}")) {
            var provider = new OpenAiChatCompletionsProvider(
                    "OpenAI", mock.baseUrl(), "m", "sk-test-fake-key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.AUTHENTICATION_FAILED, ex.getKind());
            assertFalse(ex.getMessage().contains("sk-test-fake-key"), "must never leak the credential value");
        }
    }

    @Test
    void throwsAuthorizationFailedOnHttp403() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(403, "{\"error\":{\"message\":\"forbidden\"}}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.AUTHORIZATION_FAILED, ex.getKind());
        }
    }

    @Test
    void throwsModelNotFoundOnHttp404() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                404, "{\"error\":{\"message\":\"The model 'gpt-4-test-model' does not exist\"}}")) {
            var provider = new OpenAiChatCompletionsProvider(
                    "OpenAI", mock.baseUrl(), "gpt-4-test-model", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MODEL_NOT_FOUND, ex.getKind());
        }
    }

    @Test
    void throwsProviderUnavailableOnHttp500() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(500, "{\"error\":\"internal server error\"}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    @Test
    void throwsProviderUnavailableOnHttp503() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(503, "{\"error\":\"service unavailable\"}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    // --- HTTP 429: transient rate limit vs. account/billing quota exhaustion ---

    @Test
    void throwsRateLimitedOnHttp429WithoutQuotaKeywords() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429, "{\"error\":{\"type\":\"requests\",\"message\":\"Rate limit reached, please retry later\"}}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.RATE_LIMITED, ex.getKind());
        }
    }

    @Test
    void throwsQuotaExceededOnHttp429WithInsufficientQuotaBody() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429, "{\"error\":{\"type\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\"}}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.QUOTA_EXCEEDED, ex.getKind());
        }
    }

    @Test
    void throwsQuotaExceededOnHttp429WithBillingKeywordInBody() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429, "{\"error\":{\"message\":\"Your account is not active, please check your billing details\"}}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.QUOTA_EXCEEDED, ex.getKind());
        }
    }

    // --- Malformed / unusable response bodies on an otherwise-2xx response ---

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
    void throwsMalformedResponseWhenChoicesArrayIsEmpty() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[]}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenMessageHasNoContentField() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    // --- Bounded error messages: never dump an unbounded/huge raw provider body ---

    @Test
    void httpErrorMessageIsBoundedSnippetNotFullHugeBody() throws Exception {
        String hugeBody = "x".repeat(50_000);
        try (MockAiServer mock = MockAiServer.startWithStatus(500, hugeBody)) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertTrue(
                    ex.getMessage().length() < 500,
                    "expected the 300-char snippet() bound to keep the message short, was "
                            + ex.getMessage().length() + " chars");
        }
    }

    @Test
    void handlesLargeButValidResponseBodyWithoutErrorOrExcessiveDelay() throws Exception {
        // Not a fix for the underlying "no size cap on the response body" risk (see final report)
        // - just confirms a large-but-legitimate response doesn't itself break anything within a
        // reasonable time budget.
        String padding = "y".repeat(2_000_000);
        try (MockAiServer mock = MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"padding\":\"" + padding + "\"}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 10, objectMapper);
            long start = System.currentTimeMillis();
            AiAnalysisResult result = provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            long elapsedMs = System.currentTimeMillis() - start;
            assertEquals("ok", result.responseText());
            assertTrue(elapsedMs < 10_000, "a 2MB response took suspiciously long: " + elapsedMs + "ms");
        }
    }
}

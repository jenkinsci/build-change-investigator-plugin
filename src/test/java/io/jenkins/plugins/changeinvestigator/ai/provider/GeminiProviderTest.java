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

class GeminiProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsGenerateContentUrlAndUsesGoogHeader() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}],\"role\":\"model\"}}]}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "goog-secret", 5, objectMapper, mock.baseUrl());

            AiAnalysisResult result =
                    provider.chatCompletion(new AiAnalysisRequest("system prompt", "user content", 0.4));

            assertEquals("hello", result.responseText());
            assertEquals("Google Gemini", result.providerDisplayName());
            assertEquals("gemini-2.5-flash", result.model());
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", mock.lastPath);
            assertEquals("goog-secret", mock.lastHeader("x-goog-api-key"));
            assertNull(mock.lastAuthorizationHeader);
        }
    }

    @Test
    void requestBodyUsesSystemInstructionContentsAndGenerationConfig() throws Exception {
        try (MockAiServer mock =
                MockAiServer.start("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            provider.chatCompletion(new AiAnalysisRequest("sys prompt text", "user content text", 0.7));

            assertTrue(mock.lastRequestBody.contains("systemInstruction"));
            assertTrue(mock.lastRequestBody.contains("sys prompt text"));
            assertTrue(mock.lastRequestBody.contains("\"contents\""));
            assertTrue(mock.lastRequestBody.contains("user content text"));
            assertTrue(mock.lastRequestBody.contains("\"temperature\":0.7"));
        }
    }

    @Test
    void doesNotLeakApiKeyInUrl() throws Exception {
        try (MockAiServer mock =
                MockAiServer.start("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "super-secret-key", 5, objectMapper, mock.baseUrl());
            provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertTrue(mock.lastPath.isEmpty() || !mock.lastPath.contains("super-secret-key"));
        }
    }

    @Test
    void throwsCredentialsMissingWhenApiKeyAbsent() {
        var provider = new GeminiProvider("gemini-2.5-flash", null, 5, objectMapper, null);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CREDENTIALS_MISSING, ex.getKind());
    }

    @Test
    void throwsConfigurationInvalidWhenModelMissing() {
        var provider = new GeminiProvider(null, "key", 5, objectMapper, null);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsAuthenticationFailedWhenApiKeyIsInvalid() throws Exception {
        // Google's Generative Language API rejects a bad key with HTTP 400 INVALID_ARGUMENT, not
        // a conventional 401 - must still be reported as AUTHENTICATION_FAILED by inspecting the
        // message, not left as the generic HTTP_ERROR the bare status would imply.
        try (MockAiServer mock = MockAiServer.startWithStatus(
                400,
                "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API"
                        + " key.\",\"status\":\"INVALID_ARGUMENT\"}}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "bad-key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.AUTHENTICATION_FAILED, ex.getKind());
        }
    }

    @Test
    void throwsHttpErrorOnGenericInvalidArgument() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                400,
                "{\"error\":{\"code\":400,\"message\":\"Invalid JSON payload\",\"status\":\"INVALID_ARGUMENT\"}}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
        }
    }

    @Test
    void throwsModelNotFoundOnHttp404() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                404,
                "{\"error\":{\"code\":404,\"message\":\"models/bad-model is not found\",\"status\":\"NOT_FOUND\"}}")) {
            var provider = new GeminiProvider("bad-model", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MODEL_NOT_FOUND, ex.getKind());
        }
    }

    @Test
    void throwsRateLimitedOnHttp429() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429,
                "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check"
                        + " quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.RATE_LIMITED, ex.getKind());
        }
    }

    @Test
    void throwsQuotaExceededWhenMessageNamesBillingAndQuota() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429,
                "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota, please check your plan"
                        + " and billing details.\",\"status\":\"RESOURCE_EXHAUSTED\"}}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.QUOTA_EXCEEDED, ex.getKind());
        }
    }

    @Test
    void throwsProviderUnavailableOnHttp500() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                500, "{\"error\":{\"code\":500,\"message\":\"internal error\",\"status\":\"INTERNAL\"}}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    @Test
    void throwsInvalidEndpointOnMalformedBaseUrl() {
        var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, "http://bad host/v1");
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.INVALID_ENDPOINT, ex.getKind());
    }

    @Test
    void throwsConnectionFailedWhenHostUnreachable() {
        var provider = new GeminiProvider("gemini-2.5-flash", "key", 2, objectMapper, "http://127.0.0.1:1");
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
            return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"too late\"}]}}]}";
        })) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 1, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.TIMEOUT, ex.getKind());
        }
    }

    @Test
    void boundsErrorBodySnippetToThreeHundredCharacters() throws Exception {
        String hugeBody = "{\"error\":\"" + "x".repeat(2000) + "\"}";
        try (MockAiServer mock = MockAiServer.startWithStatus(500, hugeBody)) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertTrue(
                    ex.getMessage().length() < 400,
                    "expected a bounded message, got length " + ex.getMessage().length());
        }
    }

    @Test
    void throwsMalformedResponseWhenCandidatesMissing() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenEnvelopeIsNotJson() throws Exception {
        try (MockAiServer mock = MockAiServer.start("not json")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsUnsupportedResponseWhenPromptFeedbackBlocksContent() throws Exception {
        // No candidates array at all - the prompt itself was blocked before any candidate could
        // be generated. Must not fall through to a generic MALFORMED_RESPONSE parse failure.
        try (MockAiServer mock =
                MockAiServer.start("{\"promptFeedback\":{\"blockReason\":\"SAFETY\",\"safetyRatings\":[]}}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.UNSUPPORTED_RESPONSE, ex.getKind());
            assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("safety"));
        }
    }

    @Test
    void throwsUnsupportedResponseWhenCandidateFinishedForSafetyWithNoContent() throws Exception {
        // A candidate can also be individually cut off by safety filtering, with no
        // promptFeedback.blockReason set at all and no content/parts present.
        try (MockAiServer mock = MockAiServer.start("{\"candidates\":[{\"finishReason\":\"SAFETY\",\"index\":0}]}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.UNSUPPORTED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenTextMissingAndFinishReasonIsNormal() throws Exception {
        // Distinguish the safety-block case above from a genuinely malformed envelope: a normal
        // STOP finish reason with no text is not a safety block, still MALFORMED_RESPONSE.
        try (MockAiServer mock = MockAiServer.start("{\"candidates\":[{\"finishReason\":\"STOP\",\"index\":0}]}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }
}

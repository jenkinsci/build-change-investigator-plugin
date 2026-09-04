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
    void throwsHttpErrorOnInvalidApiKey() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                400, "{\"error\":{\"code\":400,\"message\":\"API key not valid\",\"status\":\"INVALID_ARGUMENT\"}}")) {
            var provider = new GeminiProvider("gemini-2.5-flash", "bad-key", 5, objectMapper, mock.baseUrl());
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
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
}

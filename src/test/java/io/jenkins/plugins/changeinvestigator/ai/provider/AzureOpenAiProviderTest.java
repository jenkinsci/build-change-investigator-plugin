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
import org.junit.jupiter.api.Test;

class AzureOpenAiProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsDeploymentUrlWithApiVersionAndUsesApiKeyHeader() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}")) {
            var provider = new AzureOpenAiProvider(
                    mock.baseUrl(), "my-deployment", "2024-10-21", "azure-secret", 5, objectMapper);

            AiAnalysisResult result =
                    provider.chatCompletion(new AiAnalysisRequest("system prompt", "user content", 0.3));

            assertEquals("hello", result.responseText());
            assertEquals("Azure OpenAI", result.providerDisplayName());
            assertEquals("my-deployment", result.model());

            assertEquals("/openai/deployments/my-deployment/chat/completions?api-version=2024-10-21", mock.lastPath);
            assertEquals("azure-secret", mock.lastHeader("api-key"));
            assertNull(mock.lastAuthorizationHeader, "Azure's api-key header is not Authorization: Bearer");
        }
    }

    @Test
    void requestBodyDoesNotIncludeModelFieldSinceDeploymentDeterminesIt() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "my-deployment", "2024-10-21", "key", 5, objectMapper);
            provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertFalse(mock.lastRequestBody.contains("\"model\""));
            assertTrue(mock.lastRequestBody.contains("\"temperature\":0.2"));
        }
    }

    @Test
    void throwsCredentialsMissingWhenApiKeyAbsent() {
        var provider = new AzureOpenAiProvider("https://x.openai.azure.com", "d", "2024-10-21", null, 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CREDENTIALS_MISSING, ex.getKind());
    }

    @Test
    void throwsConfigurationInvalidWhenDeploymentMissing() {
        var provider =
                new AzureOpenAiProvider("https://x.openai.azure.com", null, "2024-10-21", "key", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsHttpErrorOnDeploymentNotFound() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                404, "{\"error\":{\"code\":\"DeploymentNotFound\",\"message\":\"not found\"}}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "bad-deployment", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
        }
    }

    @Test
    void throwsMalformedResponseWhenChoicesMissing() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{}")) {
            var provider = new AzureOpenAiProvider(mock.baseUrl(), "d", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }
}

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
    void trailingSlashOnEndpointDoesNotProduceADoubleSlash() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            var provider = new AzureOpenAiProvider(
                    mock.baseUrl() + "/", "my-deployment", "2024-10-21", "azure-secret", 5, objectMapper);
            provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertEquals("/openai/deployments/my-deployment/chat/completions?api-version=2024-10-21", mock.lastPath);
            assertFalse(mock.lastPath.contains("//openai"));
        }
    }

    @Test
    void urlEncodesSpecialCharactersInDeploymentName() throws Exception {
        // Deployment names are usually plain alphanumeric/hyphen, but the URL builder must not
        // construct an invalid or wrong URL if one contains characters that need escaping in a
        // path segment - defense in depth rather than relying on Azure-side naming rules alone.
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            var provider = new AzureOpenAiProvider(
                    mock.baseUrl(), "test deployment/v1", "2024-10-21", "azure-secret", 5, objectMapper);
            provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertEquals(
                    "/openai/deployments/test%20deployment%2Fv1/chat/completions?api-version=2024-10-21",
                    mock.lastPath);
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
    void throwsConfigurationInvalidWhenEndpointMissing() {
        var provider = new AzureOpenAiProvider(null, "d", "2024-10-21", "key", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsAuthenticationFailedOnHttp401() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                401, "{\"error\":{\"code\":\"401\",\"message\":\"Access denied due to invalid subscription key\"}}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "my-deployment", "2024-10-21", "bad-key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.AUTHENTICATION_FAILED, ex.getKind());
        }
    }

    @Test
    void throwsDeploymentNotFoundWhenAzureErrorCodeSaysSo() throws Exception {
        // Azure's own error body distinguishes this from any other 404 via a specific error
        // code - must be classified as the more actionable DEPLOYMENT_NOT_FOUND rather than the
        // generic MODEL_NOT_FOUND the bare status would otherwise imply.
        try (MockAiServer mock = MockAiServer.startWithStatus(
                404,
                "{\"error\":{\"code\":\"DeploymentNotFound\",\"message\":\"The API deployment for this resource does"
                        + " not exist.\"}}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "bad-deployment", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.DEPLOYMENT_NOT_FOUND, ex.getKind());
        }
    }

    @Test
    void throwsModelNotFoundOnGeneric404WithoutDeploymentNotFoundCode() throws Exception {
        // A 404 whose body does NOT identify itself as Azure's DeploymentNotFound (e.g. a
        // completely wrong path) cannot be reliably distinguished from "model not found" using
        // only the HTTP status - falls back to the shared generic mapping. See the report for
        // this documented limitation.
        try (MockAiServer mock = MockAiServer.startWithStatus(404, "{\"error\":{\"message\":\"Not Found\"}}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "some-deployment", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MODEL_NOT_FOUND, ex.getKind());
        }
    }

    @Test
    void throwsRateLimitedOnHttp429() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429,
                "{\"error\":{\"code\":\"429\",\"message\":\"Requests to the ChatCompletions_Create Operation"
                        + " have exceeded call rate limit.\"}}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "my-deployment", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.RATE_LIMITED, ex.getKind());
        }
    }

    @Test
    void throwsQuotaExceededWhenBodyNamesInsufficientQuota() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(
                429,
                "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"You exceeded your current quota, please"
                        + " check your plan and billing details.\"}}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "my-deployment", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.QUOTA_EXCEEDED, ex.getKind());
        }
    }

    @Test
    void throwsProviderUnavailableOnHttp500() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(500, "{\"error\":{\"message\":\"internal error\"}}")) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "my-deployment", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    @Test
    void throwsInvalidEndpointOnMalformedEndpoint() {
        var provider =
                new AzureOpenAiProvider("http://bad host", "my-deployment", "2024-10-21", "key", 5, objectMapper);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.INVALID_ENDPOINT, ex.getKind());
    }

    @Test
    void throwsConnectionFailedWhenHostUnreachable() {
        var provider =
                new AzureOpenAiProvider("http://127.0.0.1:1", "my-deployment", "2024-10-21", "key", 2, objectMapper);
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
            return "{\"choices\":[{\"message\":{\"content\":\"too late\"}}]}";
        })) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "my-deployment", "2024-10-21", "key", 1, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.TIMEOUT, ex.getKind());
        }
    }

    @Test
    void boundsErrorBodySnippetToThreeHundredCharacters() throws Exception {
        String hugeBody = "{\"error\":\"" + "x".repeat(2000) + "\"}";
        try (MockAiServer mock = MockAiServer.startWithStatus(500, hugeBody)) {
            var provider =
                    new AzureOpenAiProvider(mock.baseUrl(), "my-deployment", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertTrue(
                    ex.getMessage().length() < 400,
                    "expected a bounded message, got length " + ex.getMessage().length());
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

    @Test
    void throwsMalformedResponseWhenEnvelopeIsNotJson() throws Exception {
        try (MockAiServer mock = MockAiServer.start("not json")) {
            var provider = new AzureOpenAiProvider(mock.baseUrl(), "d", "2024-10-21", "key", 5, objectMapper);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }
}

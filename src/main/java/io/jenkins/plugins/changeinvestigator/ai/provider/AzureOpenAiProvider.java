package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.time.Duration;
import java.util.Map;

/**
 * Native Azure OpenAI, using the classic dated-{@code api-version} REST surface:
 * {@code POST https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version={version}},
 * authenticated with an {@code api-key} header (not {@code Authorization: Bearer} - Azure's
 * API-key auth is a different header entirely from plain OpenAI's). The deployment name in the
 * URL determines the model, so unlike OpenAI proper, the request body does not need (and does
 * not send) a {@code model} field.
 *
 * <p>This is why Azure was previously marked "not supported" when only the generic
 * OpenAI-compatible adapter existed: that adapter always calls a fixed
 * {@code {baseUrl}/chat/completions} with a bare {@code Authorization: Bearer} header and no
 * query-string support, which cannot express Azure's required URL shape or auth header.
 */
final class AzureOpenAiProvider implements AiProvider {

    private final String endpoint;
    private final String deploymentName;
    private final String apiVersion;
    private final String apiKey;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;

    AzureOpenAiProvider(
            String endpoint,
            String deploymentName,
            String apiVersion,
            String apiKey,
            int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.endpoint = endpoint;
        this.deploymentName = deploymentName;
        this.apiVersion = apiVersion;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiAnalysisResult chatCompletion(AiAnalysisRequest request) throws AiAnalysisException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CREDENTIALS_MISSING, "No API key is configured for Azure OpenAI.");
        }
        if (endpoint == null || endpoint.isBlank() || deploymentName == null || deploymentName.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID,
                    "Azure OpenAI requires both an endpoint and a deployment name.");
        }

        String url = stripTrailingSlash(endpoint) + "/openai/deployments/" + deploymentName
                + "/chat/completions?api-version=" + apiVersion;
        String requestBody = buildRequestBody(request);
        Map<String, String> headers = Map.of("Content-Type", "application/json", "api-key", apiKey);

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        String responseBody = HttpAiClientSupport.post("Azure OpenAI", url, headers, requestBody, timeout);
        return new AiAnalysisResult(extractContent(responseBody), "Azure OpenAI", deploymentName);
    }

    private String buildRequestBody(AiAnalysisRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("temperature", request.temperature());
        var messages = root.putArray("messages");
        var system = messages.addObject();
        system.put("role", "system");
        system.put("content", request.systemPrompt());
        var user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userContent());
        return root.toString();
    }

    private String extractContent(String responseBody) throws AiAnalysisException {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Azure OpenAI's response envelope was not valid JSON.",
                    e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Azure OpenAI's response did not contain choices[0].message.content.");
        }
        return content.asText();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

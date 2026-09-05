package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
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

        String url = stripTrailingSlash(endpoint) + "/openai/deployments/" + encodePathSegment(deploymentName)
                + "/chat/completions?api-version=" + apiVersion;
        String requestBody = buildRequestBody(request);
        Map<String, String> headers = Map.of("Content-Type", "application/json", "api-key", apiKey);

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        String responseBody;
        try {
            responseBody = HttpAiClientSupport.post("Azure OpenAI", url, headers, requestBody, timeout);
        } catch (AiAnalysisException e) {
            throw refineKind(e);
        }
        return new AiAnalysisResult(extractContent(responseBody), "Azure OpenAI", deploymentName);
    }

    /**
     * A deployment name can legitimately contain characters (spaces, etc.) that are not valid
     * unencoded in a URL path segment; encoding it here means a deployment name the administrator
     * actually configured on Azure is never silently mangled into a different, invalid, or
     * unintended URL. {@link URLEncoder} is form (application/x-www-form-urlencoded) encoding, so
     * its space-as-"+" is translated to the correct path-segment escape, "%20".
     */
    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * {@link HttpAiClientSupport} can only classify by HTTP status, but Azure's error body
     * distinguishes two very different HTTP-404 situations that a bare status code cannot: a
     * genuinely missing/misspelled deployment (Azure's own {@code DeploymentNotFound} error code)
     * versus every other kind of 404 (e.g. a wrong path entirely). It also mirrors OpenAI's
     * chat-completions error shape closely enough to carry the same rate-limit-vs-quota
     * ambiguity under HTTP 429 - see {@link OpenAiChatCompletionsProvider#chatCompletion} for the
     * same reasoning applied there.
     */
    private static AiAnalysisException refineKind(AiAnalysisException e) {
        if (e.getMessage() == null) {
            return e;
        }
        String lower = e.getMessage().toLowerCase(Locale.ROOT);
        if (e.getKind() == AiAnalysisException.Kind.MODEL_NOT_FOUND && lower.contains("deploymentnotfound")) {
            return new AiAnalysisException(AiAnalysisException.Kind.DEPLOYMENT_NOT_FOUND, e.getMessage(), e);
        }
        if (e.getKind() == AiAnalysisException.Kind.RATE_LIMITED
                && (lower.contains("insufficient_quota")
                        || lower.contains("insufficient quota")
                        || (lower.contains("quota") && lower.contains("billing")))) {
            return new AiAnalysisException(AiAnalysisException.Kind.QUOTA_EXCEEDED, e.getMessage(), e);
        }
        return e;
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

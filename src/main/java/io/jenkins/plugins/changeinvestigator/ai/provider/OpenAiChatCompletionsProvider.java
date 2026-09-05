package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OpenAI "chat completions" HTTP shape ({@code POST {baseUrl}/chat/completions}), which is
 * what "OpenAI-compatible" means in practice for OpenAI itself, self-hosted gateways/proxies,
 * and Ollama's OpenAI-compatibility layer - all three {@link AiProvider}s in this package
 * ({@link OpenAiProviderConfig}, {@link OpenAiCompatibleProviderConfig},
 * {@link OllamaProviderConfig}) delegate to this single implementation rather than duplicating
 * it, since the wire contract is identical.
 *
 * <p>The API token is optional here (unlike a strict OpenAI client): Ollama and many
 * self-hosted OpenAI-compatible servers don't check it at all. When present, it is sent as a
 * standard {@code Authorization: Bearer} header and, per the existing security guarantee, never
 * retained as field state - it exists only for the duration of {@link #chatCompletion}.
 */
final class OpenAiChatCompletionsProvider implements AiProvider {

    private final String providerDisplayName;
    private final String baseUrl;
    private final String model;
    private final String apiToken;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;

    OpenAiChatCompletionsProvider(
            String providerDisplayName,
            String baseUrl,
            String model,
            String apiToken,
            int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.providerDisplayName = providerDisplayName;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiToken = apiToken;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiAnalysisResult chatCompletion(AiAnalysisRequest request) throws AiAnalysisException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID, "No base URL is configured for AI analysis.");
        }
        if (model == null || model.isBlank()) {
            // Caught here, before any network call is attempted: an empty model field is a
            // configuration mistake, not something any provider's HTTP error response could tell
            // us about more specifically (some gateways would even silently accept it or 400 in a
            // way indistinguishable from other bad-request causes).
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID,
                    "No model is configured for " + providerDisplayName + ".");
        }

        String url = stripTrailingSlash(baseUrl) + "/chat/completions";
        String requestBody = buildRequestBody(request);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (apiToken != null && !apiToken.isBlank()) {
            headers.put("Authorization", "Bearer " + apiToken);
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        String responseBody;
        try {
            responseBody = HttpAiClientSupport.post(providerDisplayName, url, headers, requestBody, timeout);
        } catch (AiAnalysisException e) {
            throw refineRateLimitKind(e);
        }
        return new AiAnalysisResult(extractContent(responseBody), providerDisplayName, model);
    }

    /**
     * {@link HttpAiClientSupport} can only classify HTTP 429 as the generic {@code RATE_LIMITED}
     * kind, since it has no knowledge of any provider's JSON error shape. OpenAI (and
     * OpenAI-compatible gateways that mirror its error format) distinguishes two very different
     * situations under the same status code: a transient rate limit versus an account/project
     * that is out of quota or billing credits - the latter will never succeed on retry, so it
     * deserves its own, more actionable {@link AiAnalysisException.Kind#QUOTA_EXCEEDED}.
     */
    private static AiAnalysisException refineRateLimitKind(AiAnalysisException e) {
        if (e.getKind() != AiAnalysisException.Kind.RATE_LIMITED || e.getMessage() == null) {
            return e;
        }
        String lower = e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("insufficient_quota") || lower.contains("insufficient quota") || lower.contains("billing")) {
            return new AiAnalysisException(AiAnalysisException.Kind.QUOTA_EXCEEDED, e.getMessage(), e);
        }
        return e;
    }

    private String buildRequestBody(AiAnalysisRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
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
                    providerDisplayName + "'s response envelope was not valid JSON.",
                    e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    providerDisplayName + "'s response did not contain choices[0].message.content.");
        }
        return content.asText();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

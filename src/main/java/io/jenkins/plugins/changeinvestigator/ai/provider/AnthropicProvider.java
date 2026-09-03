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
 * Native Anthropic Messages API ({@code POST https://api.anthropic.com/v1/messages}) - not
 * routed through the OpenAI-compatible adapter, since Anthropic's request/response shape and
 * authentication are genuinely different (an {@code x-api-key} header rather than
 * {@code Authorization: Bearer}, a required {@code max_tokens}, and a {@code content[]} block
 * array rather than {@code choices[0].message.content}).
 *
 * <p>{@code max_tokens} is not exposed as a plugin setting (Anthropic requires it with no
 * server-side default) - {@link #DEFAULT_MAX_TOKENS} is a fixed, generous-but-timeout-safe
 * value appropriate for the short structured JSON assessment this plugin asks for.
 */
final class AnthropicProvider implements AiProvider {

    static final String API_VERSION = "2023-06-01";
    static final int DEFAULT_MAX_TOKENS = 8192;
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";

    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final String endpoint;

    AnthropicProvider(String model, String apiKey, int timeoutSeconds, ObjectMapper objectMapper) {
        this(model, apiKey, timeoutSeconds, objectMapper, DEFAULT_ENDPOINT);
    }

    /** Test-only overload: overrides the endpoint so unit tests can point at a local mock server instead of the real Anthropic API. */
    AnthropicProvider(String model, String apiKey, int timeoutSeconds, ObjectMapper objectMapper, String endpoint) {
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
    }

    @Override
    public AiAnalysisResult chatCompletion(AiAnalysisRequest request) throws AiAnalysisException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CREDENTIALS_MISSING, "No API key is configured for Anthropic.");
        }
        if (model == null || model.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID, "No model is configured for Anthropic.");
        }

        String requestBody = buildRequestBody(request);
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "x-api-key", apiKey,
                "anthropic-version", API_VERSION);

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        String responseBody = HttpAiClientSupport.post("Anthropic", endpoint, headers, requestBody, timeout);
        return new AiAnalysisResult(extractText(responseBody), "Anthropic", model);
    }

    private String buildRequestBody(AiAnalysisRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", DEFAULT_MAX_TOKENS);
        root.put("system", request.systemPrompt());
        root.put("temperature", clampToAnthropicRange(request.temperature()));
        var messages = root.putArray("messages");
        var user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userContent());
        return root.toString();
    }

    /** Anthropic's temperature range is 0.0-1.0, narrower than OpenAI's 0.0-2.0. */
    private static double clampToAnthropicRange(double temperature) {
        return Math.max(0.0, Math.min(1.0, temperature));
    }

    private String extractText(String responseBody) throws AiAnalysisException {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Anthropic's response envelope was not valid JSON.",
                    e);
        }
        JsonNode contentArray = root.path("content");
        if (!contentArray.isArray() || contentArray.isEmpty()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Anthropic's response did not contain a content array.");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        if (text.isEmpty()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Anthropic's response contained no text content block.");
        }
        return text.toString();
    }
}

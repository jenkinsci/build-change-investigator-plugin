package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.time.Duration;
import java.util.Locale;
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
    static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";

    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    /**
     * @param baseUrl e.g. {@code https://api.anthropic.com/v1} - "/messages" is appended
     *     automatically. {@code null}/blank uses {@link #DEFAULT_BASE_URL}; overriding it is an
     *     escape hatch for an enterprise gateway or private routing layer that speaks the native
     *     Anthropic Messages API shape, not something most users need to touch.
     */
    AnthropicProvider(String model, String apiKey, int timeoutSeconds, ObjectMapper objectMapper, String baseUrl) {
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
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

        String url = stripTrailingSlash(baseUrl) + "/messages";
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        String responseBody;
        try {
            responseBody = HttpAiClientSupport.post("Anthropic", url, headers, requestBody, timeout);
        } catch (AiAnalysisException e) {
            throw refineKind(e);
        }
        return new AiAnalysisResult(extractText(responseBody), "Anthropic", model);
    }

    /**
     * {@link HttpAiClientSupport} can only classify by HTTP status. Anthropic surfaces an
     * exhausted account/billing balance as HTTP 400 {@code invalid_request_error} with a message
     * that literally says so ("Your credit balance is too low...") - the shared status mapping
     * would otherwise leave this as the generic {@code HTTP_ERROR} alongside every other 400,
     * instead of the more actionable {@code QUOTA_EXCEEDED}.
     */
    private static AiAnalysisException refineKind(AiAnalysisException e) {
        if (e.getKind() != AiAnalysisException.Kind.HTTP_ERROR || e.getMessage() == null) {
            return e;
        }
        String lower = e.getMessage().toLowerCase(Locale.ROOT);
        if (lower.contains("credit balance") || lower.contains("insufficient_quota")) {
            return new AiAnalysisException(AiAnalysisException.Kind.QUOTA_EXCEEDED, e.getMessage(), e);
        }
        return e;
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

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

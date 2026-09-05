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
 * Native Google Gemini {@code generateContent} REST API:
 * {@code POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent}.
 * Authenticated with an {@code x-goog-api-key} header (deliberately not the {@code ?key=}
 * query-parameter form Google also supports, which would leak the key into server/proxy access
 * logs and this plugin's own outbound-request logging if ever enabled).
 */
final class GeminiProvider implements AiProvider {

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    /**
     * @param baseUrl e.g. {@code https://generativelanguage.googleapis.com} -
     *     "/v1beta/models/{model}:generateContent" is appended automatically. {@code null}/blank
     *     uses {@link #DEFAULT_BASE_URL}; overriding it is an escape hatch for an enterprise
     *     proxy or private gateway, not something most users need to touch.
     */
    GeminiProvider(String model, String apiKey, int timeoutSeconds, ObjectMapper objectMapper, String baseUrl) {
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
                    AiAnalysisException.Kind.CREDENTIALS_MISSING, "No API key is configured for Gemini.");
        }
        if (model == null || model.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID, "No model is configured for Gemini.");
        }

        String url = stripTrailingSlash(baseUrl) + "/v1beta/models/" + model + ":generateContent";
        String requestBody = buildRequestBody(request);
        Map<String, String> headers = Map.of("Content-Type", "application/json", "x-goog-api-key", apiKey);

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        String responseBody;
        try {
            responseBody = HttpAiClientSupport.post("Gemini", url, headers, requestBody, timeout);
        } catch (AiAnalysisException e) {
            throw refineKind(e);
        }
        return new AiAnalysisResult(extractText(responseBody), "Google Gemini", model);
    }

    /**
     * {@link HttpAiClientSupport} can only classify by HTTP status, but Gemini's own error body
     * carries information the status code alone does not:
     *
     * <ul>
     *   <li>Google's Generative Language API rejects an invalid/missing API key with HTTP 400
     *       {@code INVALID_ARGUMENT} ("API key not valid...") rather than a conventional HTTP
     *       401, so the shared status-based mapping would otherwise leave this as the generic
     *       {@code HTTP_ERROR} instead of the far more actionable {@code AUTHENTICATION_FAILED}.
     *   <li>HTTP 429 {@code RESOURCE_EXHAUSTED} covers both a transient rate limit and an
     *       account/project that is genuinely out of quota; when the message itself calls out
     *       quota/billing/plan we re-classify to the more actionable {@code QUOTA_EXCEEDED} -
     *       best-effort, since Google does not always distinguish the two in the message text.
     * </ul>
     */
    private static AiAnalysisException refineKind(AiAnalysisException e) {
        if (e.getMessage() == null) {
            return e;
        }
        String lower = e.getMessage().toLowerCase(Locale.ROOT);
        if (e.getKind() == AiAnalysisException.Kind.HTTP_ERROR
                && (lower.contains("api key not valid") || lower.contains("api_key_invalid"))) {
            return new AiAnalysisException(AiAnalysisException.Kind.AUTHENTICATION_FAILED, e.getMessage(), e);
        }
        if (e.getKind() == AiAnalysisException.Kind.RATE_LIMITED
                && lower.contains("quota")
                && (lower.contains("billing") || lower.contains("plan"))) {
            return new AiAnalysisException(AiAnalysisException.Kind.QUOTA_EXCEEDED, e.getMessage(), e);
        }
        return e;
    }

    private String buildRequestBody(AiAnalysisRequest request) {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode systemInstruction = root.putObject("systemInstruction");
        var systemParts = systemInstruction.putArray("parts");
        systemParts.addObject().put("text", request.systemPrompt());

        var contents = root.putArray("contents");
        var userContent = contents.addObject();
        userContent.put("role", "user");
        var userParts = userContent.putArray("parts");
        userParts.addObject().put("text", request.userContent());

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("temperature", request.temperature());

        return root.toString();
    }

    private String extractText(String responseBody) throws AiAnalysisException {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE, "Gemini's response envelope was not valid JSON.", e);
        }
        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (!blockReason.isMissingNode() && !blockReason.isNull()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.UNSUPPORTED_RESPONSE,
                    "Gemini blocked the response due to safety filters (blockReason: " + blockReason.asText() + ").");
        }

        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Gemini's response did not contain a candidates array.");
        }

        JsonNode firstCandidate = candidates.path(0);
        JsonNode text = firstCandidate.path("content").path("parts").path(0).path("text");
        if (text.isMissingNode() || text.isNull()) {
            // A candidate that finished for a reason other than normal completion (SAFETY,
            // RECITATION, PROHIBITED_CONTENT, SPII, ...) and carries no text is Gemini's way of
            // saying the response was blocked/filtered, not a malformed envelope - report it as
            // an actionable, well-formed-but-unusable response instead of a generic parse error.
            String finishReason = firstCandidate.path("finishReason").asText(null);
            if (finishReason != null
                    && !finishReason.isBlank()
                    && !"STOP".equals(finishReason)
                    && !"MAX_TOKENS".equals(finishReason)) {
                throw new AiAnalysisException(
                        AiAnalysisException.Kind.UNSUPPORTED_RESPONSE,
                        "Gemini did not return usable content (finishReason: " + finishReason
                                + "); the response may have been blocked or filtered.");
            }
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Gemini's response did not contain candidates[0].content.parts[0].text.");
        }
        return text.asText();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

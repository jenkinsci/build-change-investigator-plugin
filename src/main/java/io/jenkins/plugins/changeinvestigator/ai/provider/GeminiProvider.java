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
 * Native Google Gemini {@code generateContent} REST API:
 * {@code POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent}.
 * Authenticated with an {@code x-goog-api-key} header (deliberately not the {@code ?key=}
 * query-parameter form Google also supports, which would leak the key into server/proxy access
 * logs and this plugin's own outbound-request logging if ever enabled).
 */
final class GeminiProvider implements AiProvider {

    private static final String DEFAULT_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final String apiBase;

    GeminiProvider(String model, String apiKey, int timeoutSeconds, ObjectMapper objectMapper) {
        this(model, apiKey, timeoutSeconds, objectMapper, DEFAULT_API_BASE);
    }

    /** Test-only overload: overrides the API base so unit tests can point at a local mock server instead of the real Gemini API. */
    GeminiProvider(String model, String apiKey, int timeoutSeconds, ObjectMapper objectMapper, String apiBase) {
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
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

        String url = apiBase + model + ":generateContent";
        String requestBody = buildRequestBody(request);
        Map<String, String> headers = Map.of("Content-Type", "application/json", "x-goog-api-key", apiKey);

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        String responseBody = HttpAiClientSupport.post("Gemini", url, headers, requestBody, timeout);
        return new AiAnalysisResult(extractText(responseBody), "Google Gemini", model);
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
        JsonNode text = root.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text");
        if (text.isMissingNode() || text.isNull()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "Gemini's response did not contain candidates[0].content.parts[0].text.");
        }
        return text.asText();
    }
}

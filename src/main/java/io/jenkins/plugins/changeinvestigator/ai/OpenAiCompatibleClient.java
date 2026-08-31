package io.jenkins.plugins.changeinvestigator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Minimal client for the OpenAI "chat completions" HTTP shape
 * ({@code POST {baseUrl}/chat/completions}), which is what "OpenAI-compatible" means in
 * practice for self-hosted gateways, proxies, and most third-party providers. Deliberately
 * uses only the JDK's built-in {@link HttpClient} and a minimal, widely-supported request
 * body so no assumption is made about OpenAI-specific extensions being available.
 */
public final class OpenAiCompatibleClient {

    private final AiProviderConfig config;

    // Deliberately a plain field on a non-record class, not a field on AiProviderConfig: this
    // class never auto-generates toString()/equals()/hashCode(), so holding the token here
    // cannot leak it through a generated method the way it could on a record. Only ever read
    // once, to build the Authorization header below.
    private final String apiToken;

    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(AiProviderConfig config, String apiToken, ObjectMapper objectMapper) {
        this.config = config;
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
    }

    /**
     * Sends the system/user messages and returns the raw assistant message content string
     * (not yet parsed as the evidence-assessment JSON - see {@link AiResponseParser}).
     */
    public String chatCompletion(String systemPrompt, String userContent) throws AiAnalysisException {
        if (apiToken == null || apiToken.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CREDENTIALS_MISSING, "No API token is configured for AI analysis.");
        }
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID, "No base URL is configured for AI analysis.");
        }

        String url = stripTrailingSlash(config.baseUrl()) + "/chat/completions";
        String requestBody = buildRequestBody(systemPrompt, userContent);

        Duration timeout = Duration.ofSeconds(Math.max(1, config.timeoutSeconds()));
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiToken)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.TIMEOUT,
                    "Timed out waiting for the AI provider after " + config.timeoutSeconds() + "s.",
                    e);
        } catch (IOException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONNECTION_FAILED,
                    "Could not connect to the AI provider: " + e.getMessage(),
                    e);
        } catch (java.io.UncheckedIOException e) {
            // HttpClient's internal async plumbing can surface connection failures wrapped this
            // way rather than as a plain IOException; treat it the same.
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONNECTION_FAILED,
                    "Could not connect to the AI provider: " + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONNECTION_FAILED, "AI analysis was interrupted.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.HTTP_ERROR,
                    "The AI provider returned HTTP " + response.statusCode() + ": " + snippet(response.body()));
        }

        return extractContent(response.body());
    }

    private String buildRequestBody(String systemPrompt, String userContent) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", config.temperature());
        var messages = root.putArray("messages");
        var system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        var user = messages.addObject();
        user.put("role", "user");
        user.put("content", userContent);
        return root.toString();
    }

    private String extractContent(String responseBody) throws AiAnalysisException {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "The AI provider's response envelope was not valid JSON.",
                    e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "The AI provider's response did not contain choices[0].message.content.");
        }
        return content.asText();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}

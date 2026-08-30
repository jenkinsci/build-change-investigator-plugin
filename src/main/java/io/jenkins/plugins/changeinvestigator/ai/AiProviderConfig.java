package io.jenkins.plugins.changeinvestigator.ai;

import java.util.List;
import java.util.Map;

/**
 * Plain, Jenkins-independent snapshot of the settings needed to call an OpenAI-compatible
 * chat-completions endpoint. Kept separate from the Jenkins {@code GlobalConfiguration}
 * descriptor so the AI call/parse pipeline can be unit tested without a running Jenkins instance.
 *
 * @param baseUrl        e.g. {@code https://api.openai.com/v1} - "/chat/completions" is appended by the client
 * @param model          model name to request
 * @param apiToken       bearer token, already resolved from Jenkins credentials; never logged
 * @param timeoutSeconds connect + request timeout
 * @param temperature    sampling temperature; low by default to favor consistent analysis
 * @param extraHeaders   additional HTTP headers some gateways require (e.g. Azure's api-key)
 */
public record AiProviderConfig(
        String baseUrl,
        String model,
        String apiToken,
        int timeoutSeconds,
        double temperature,
        Map<String, String> extraHeaders,
        int maxLogContextChars) {

    public AiProviderConfig {
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    public boolean hasApiToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    public static Map<String, String> parseHeaderLines(List<String> lines) {
        if (lines == null) {
            return Map.of();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            result.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return result;
    }
}

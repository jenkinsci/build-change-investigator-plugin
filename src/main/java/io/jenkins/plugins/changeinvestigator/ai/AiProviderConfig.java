package io.jenkins.plugins.changeinvestigator.ai;

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
 */
public record AiProviderConfig(
        String baseUrl,
        String model,
        String apiToken,
        int timeoutSeconds,
        double temperature,
        int maxLogContextChars) {

    public boolean hasApiToken() {
        return apiToken != null && !apiToken.isBlank();
    }
}

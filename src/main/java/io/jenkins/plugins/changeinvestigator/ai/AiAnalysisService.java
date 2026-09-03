package io.jenkins.plugins.changeinvestigator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.changeinvestigator.ai.provider.AiProviderConfig;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates one AI analysis: builds the prompt, calls the configured provider, parses the
 * result. Never throws - every failure mode becomes a {@link AiAssessment#failed(String)} so
 * callers can always render observed evidence even when AI analysis is unavailable. Which
 * provider is called, and how, is entirely delegated to {@code providerConfig} - this class
 * knows nothing about any specific provider's API.
 */
public final class AiAnalysisService {

    private static final Logger LOGGER = Logger.getLogger(AiAnalysisService.class.getName());

    private final ObjectMapper objectMapper;

    public AiAnalysisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiAssessment analyze(
            BuildInvestigationEvidence evidence,
            AiProviderConfig providerConfig,
            int timeoutSeconds,
            double temperature) {
        PromptBuilder promptBuilder = new PromptBuilder(objectMapper);

        try {
            AiProvider provider = providerConfig.createProvider(objectMapper, timeoutSeconds);
            AiAnalysisResult result = provider.chatCompletion(new AiAnalysisRequest(
                    promptBuilder.systemPrompt(), promptBuilder.userContent(evidence), temperature));
            AiResponseParser parser = new AiResponseParser(objectMapper, result.providerDisplayName(), result.model());
            return parser.parse(result.responseText());
        } catch (AiAnalysisException e) {
            // FINER, not INFO: the same information is already shown to the user via the
            // AiAssessment.failed() message below, so logging it at INFO would just duplicate
            // an already-visible, expected condition (e.g. a misconfigured endpoint) into every
            // controller's default log.
            LOGGER.log(Level.FINER, "AI analysis unavailable (" + e.getKind() + "): " + e.getMessage());
            return AiAssessment.failed(describeFailure(e));
        } catch (RuntimeException e) {
            // Defensive: an AI provider or parsing edge case must never break the build page.
            LOGGER.log(Level.WARNING, "Unexpected error during AI analysis", e);
            return AiAssessment.failed(
                    "Unexpected error during AI analysis: " + e.getClass().getSimpleName());
        }
    }

    private static String describeFailure(AiAnalysisException e) {
        return switch (e.getKind()) {
            case CREDENTIALS_MISSING, CONFIGURATION_INVALID ->
                "AI analysis is not configured correctly: " + e.getMessage();
            case TIMEOUT -> "AI provider did not respond in time: " + e.getMessage();
            case CONNECTION_FAILED -> "Could not reach the AI provider: " + e.getMessage();
            case HTTP_ERROR -> "AI provider returned an error: " + e.getMessage();
            case MALFORMED_RESPONSE -> "AI provider returned an unusable response: " + e.getMessage();
            case DISABLED -> "AI analysis is disabled.";
        };
    }
}

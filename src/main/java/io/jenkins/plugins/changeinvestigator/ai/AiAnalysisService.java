package io.jenkins.plugins.changeinvestigator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates one AI analysis: builds the prompt, calls the configured provider, parses the
 * result. Never throws - every failure mode becomes a {@link AiAssessment#failed(String)} so
 * callers can always render observed evidence even when AI analysis is unavailable.
 */
public final class AiAnalysisService {

    private static final Logger LOGGER = Logger.getLogger(AiAnalysisService.class.getName());

    private final ObjectMapper objectMapper;

    public AiAnalysisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param apiToken resolved credential plaintext, passed straight through to the HTTP client
     *                 and never retained by this service or by {@link AiProviderConfig}.
     */
    public AiAssessment analyze(BuildInvestigationEvidence evidence, AiProviderConfig config, String apiToken) {
        PromptBuilder promptBuilder = new PromptBuilder(objectMapper);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(config, apiToken, objectMapper);
        AiResponseParser parser = new AiResponseParser(objectMapper, config.model());

        try {
            String rawContent =
                    client.chatCompletion(promptBuilder.systemPrompt(), promptBuilder.userContent(evidence));
            return parser.parse(rawContent);
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

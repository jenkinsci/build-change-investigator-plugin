package io.jenkins.plugins.changeinvestigator.ai;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * The AI's interpretation of a {@link io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence}
 * bundle. This is always kept as a distinct object from the observed evidence so the UI can
 * never confuse "what Jenkins observed" with "what the model guessed".
 *
 * <p>Exactly one of three states holds at any time:
 * <ul>
 *   <li>{@link #isDisabled()} - AI analysis is turned off in global configuration.</li>
 *   <li>{@link #isFailed()} - AI analysis was attempted but did not succeed; {@link #getErrorMessage()}
 *       explains why. Observed evidence is still shown to the user regardless.</li>
 *   <li>otherwise - a real (possibly "insufficient evidence") assessment is present.</li>
 * </ul>
 */
public final class AiAssessment implements Serializable {

    private static final long serialVersionUID = 1L;

    private enum State { DISABLED, FAILED, COMPLETED }

    private final State state;
    private final String errorMessage;

    private final String mostLikelyCause;
    private final Confidence confidence;
    private final String reasoning;
    private final List<String> supportingEvidence;
    private final List<String> recommendedChecks;
    private final boolean insufficientEvidence;

    private final String modelUsed;
    private final long generatedAtMillis;

    private AiAssessment(State state, String errorMessage, String mostLikelyCause, Confidence confidence,
                          String reasoning, List<String> supportingEvidence, List<String> recommendedChecks,
                          boolean insufficientEvidence, String modelUsed, long generatedAtMillis) {
        this.state = state;
        this.errorMessage = errorMessage;
        this.mostLikelyCause = mostLikelyCause;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.supportingEvidence = supportingEvidence == null
                ? Collections.emptyList() : List.copyOf(supportingEvidence);
        this.recommendedChecks = recommendedChecks == null
                ? Collections.emptyList() : List.copyOf(recommendedChecks);
        this.insufficientEvidence = insufficientEvidence;
        this.modelUsed = modelUsed;
        this.generatedAtMillis = generatedAtMillis;
    }

    public static AiAssessment disabled() {
        return new AiAssessment(State.DISABLED, null, null, null, null, null, null, false, null, 0);
    }

    public static AiAssessment failed(String errorMessage) {
        return new AiAssessment(State.FAILED, errorMessage, null, null, null, null, null, false, null,
                System.currentTimeMillis());
    }

    public static AiAssessment completed(String mostLikelyCause, Confidence confidence, String reasoning,
                                          List<String> supportingEvidence, List<String> recommendedChecks,
                                          boolean insufficientEvidence, String modelUsed) {
        return new AiAssessment(State.COMPLETED, null, mostLikelyCause, confidence, reasoning, supportingEvidence,
                recommendedChecks, insufficientEvidence, modelUsed, System.currentTimeMillis());
    }

    public boolean isDisabled() {
        return state == State.DISABLED;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getMostLikelyCause() {
        return mostLikelyCause;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public String getConfidenceLabel() {
        return confidence == null ? "UNKNOWN" : confidence.name();
    }

    public String getReasoning() {
        return reasoning;
    }

    public List<String> getSupportingEvidence() {
        return supportingEvidence;
    }

    public List<String> getRecommendedChecks() {
        return recommendedChecks;
    }

    public boolean isInsufficientEvidence() {
        return insufficientEvidence;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public long getGeneratedAtMillis() {
        return generatedAtMillis;
    }
}

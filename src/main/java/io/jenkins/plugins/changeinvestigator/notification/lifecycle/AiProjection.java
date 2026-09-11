package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import java.io.Serializable;
import java.util.Objects;

/** A scoped projection only; this value never initiates analysis or supplies causal evidence. */
public record AiProjection(State state, String evidenceScopeDigest, String assessmentId) implements Serializable {
    public enum State {
        AI_NOT_CONFIGURED,
        AI_DISABLED,
        AI_PENDING,
        AI_COMPLETE,
        AI_FAILED
    }

    public AiProjection {
        Objects.requireNonNull(state);
        evidenceScopeDigest = Objects.requireNonNullElse(evidenceScopeDigest, "");
        assessmentId = Objects.requireNonNullElse(assessmentId, "");
        if (evidenceScopeDigest.length() > 512
                || assessmentId.length() > 512
                || !evidenceScopeDigest.equals(
                        io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(
                                evidenceScopeDigest, 512))
                || !assessmentId.equals(
                        io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(assessmentId, 512))) {
            throw new IllegalArgumentException("AI projection bound exceeded");
        }
    }

    public boolean currentCompletion(String currentScope) {
        return state == State.AI_COMPLETE
                && !assessmentId.isBlank()
                && !evidenceScopeDigest.isBlank()
                && evidenceScopeDigest.equals(currentScope);
    }
}

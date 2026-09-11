package io.jenkins.plugins.changeinvestigator.notification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.CoverageEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.LifecycleReducer;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts;
import java.util.List;

/** Trusted adapter output; the display template is redacted and validated before any case write. */
public record NotificationObservation(
        String observationId,
        String runId,
        long order,
        String result,
        boolean actualEvidence,
        boolean completeHistory,
        ExecutionContextV1 context,
        FailureSignatureV1 signature,
        String affectedCheck,
        MaterialFacts facts,
        CoverageEvidence coverage,
        List<LifecycleReducer.RecoveryChange> recoveryChanges,
        ObjectNode display) {
    public NotificationObservation {
        if (observationId == null
                || !observationId.matches("[A-Za-z0-9:_-]{1,128}")
                || order < 1
                || context == null
                || signature == null
                || facts == null
                || display == null) throw new IllegalArgumentException("Invalid notification observation");
        java.util.UUID.fromString(runId);
        recoveryChanges = List.copyOf(recoveryChanges);
        display = display.deepCopy();
    }

    @Override
    public ObjectNode display() {
        return display.deepCopy();
    }
}

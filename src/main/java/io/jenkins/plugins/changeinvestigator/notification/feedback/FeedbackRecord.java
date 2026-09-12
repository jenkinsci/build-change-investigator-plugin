package io.jenkins.plugins.changeinvestigator.notification.feedback;

import java.util.UUID;

/** Append-only action audit, including the canonical replay binding. */
public record FeedbackRecord(
        UUID recordId,
        FeedbackRequest request,
        FeedbackActor actor,
        String requestDigest,
        long at,
        long beforeRevision,
        long afterRevision,
        ConfirmationRecord confirmation,
        String source) {
    public FeedbackRecord(
            UUID recordId,
            FeedbackRequest request,
            FeedbackActor actor,
            String requestDigest,
            long at,
            long beforeRevision,
            long afterRevision,
            ConfirmationRecord confirmation) {
        this(
                recordId,
                request,
                actor,
                requestDigest,
                at,
                beforeRevision,
                afterRevision,
                confirmation,
                "AUTHENTICATED_JENKINS_ACTION");
    }

    public FeedbackRecord {
        if (!"AUTHENTICATED_JENKINS_ACTION".equals(source))
            throw new IllegalArgumentException("Invalid feedback provenance");
        if (recordId == null
                || request == null
                || actor == null
                || requestDigest == null
                || !requestDigest.matches("[a-f0-9]{64}")
                || at < 0
                || beforeRevision < 1
                || afterRevision != beforeRevision + 1
                || request.expectedRevision() != beforeRevision)
            throw new IllegalArgumentException("Invalid feedback record");
        if (confirmation != null
                && (!confirmation.recordId().equals(recordId)
                        || confirmation.revision() != afterRevision
                        || confirmation.confirmedAt() != at
                        || !confirmation.actorId().equals(actor.id())
                        || !confirmation.actorLabel().equals(actor.label())
                        || !confirmation.source().equals(source)))
            throw new IllegalArgumentException("Confirmation audit mismatch");
    }
}

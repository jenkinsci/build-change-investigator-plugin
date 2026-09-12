package io.jenkins.plugins.changeinvestigator.notification.feedback;

import java.util.UUID;

/** Immutable human assertion; corrections and revocations append a successor. */
public record ConfirmationRecord(
        UUID recordId,
        long revision,
        UUID investigationId,
        long confirmedAt,
        String actorId,
        String actorLabel,
        String source,
        String causeCandidateId,
        String fixCommit,
        String fixBuild,
        String note,
        String correctiveAction,
        String validationBasis,
        UUID supersedesRecordId,
        Kind kind,
        boolean revoked) {
    public enum Kind {
        CAUSE,
        RESOLUTION
    }

    public ConfirmationRecord {
        if (recordId == null
                || revision < 1
                || investigationId == null
                || confirmedAt < 0
                || kind == null
                || !"AUTHENTICATED_JENKINS_ACTION".equals(source))
            throw new IllegalArgumentException("Invalid confirmation");
        new FeedbackActor(actorId, actorLabel);
        causeCandidateId = FeedbackRequest.text(causeCandidateId, 256);
        fixCommit = FeedbackRequest.text(fixCommit, 160);
        fixBuild = FeedbackRequest.text(fixBuild, 128);
        note = FeedbackRequest.text(note, 500);
        correctiveAction = FeedbackRequest.text(correctiveAction, 400);
        validationBasis = FeedbackRequest.text(validationBasis, 400);
    }
}

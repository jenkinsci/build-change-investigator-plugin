package io.jenkins.plugins.changeinvestigator.notification.feedback;

import java.util.List;
import java.util.UUID;

/** Bounded command from an authenticated Jenkins action. */
public record FeedbackRequest(
        Action action,
        UUID actionId,
        long expectedRevision,
        String candidateId,
        long evidenceRevision,
        String recoveryBuild,
        String correctiveAction,
        String validationBasis,
        String fixCommit,
        String note,
        UUID confirmationId,
        List<UUID> destinations,
        long muteUntil) {
    public enum Action {
        ACKNOWLEDGE,
        NOT_RELATED,
        CONFIRM_CAUSE,
        CONFIRM_RESOLUTION,
        REVOKE_CONFIRMATION,
        CORRECT_CONFIRMATION,
        REOPEN,
        MUTE,
        UNMUTE
    }

    public FeedbackRequest {
        if (action == null || actionId == null || expectedRevision < 1 || evidenceRevision < 0 || muteUntil < -1)
            throw new IllegalArgumentException("Invalid feedback command");
        candidateId = text(candidateId, 256);
        recoveryBuild = text(recoveryBuild, 128);
        correctiveAction = text(correctiveAction, 400);
        validationBasis = text(validationBasis, 400);
        fixCommit = text(fixCommit, 160);
        note = text(note, 500);
        boolean cause = action == Action.CONFIRM_CAUSE;
        boolean resolution = action == Action.CONFIRM_RESOLUTION;
        boolean correction = action == Action.CORRECT_CONFIRMATION;
        if (!candidateId.isBlank() && !(cause || resolution || correction || action == Action.NOT_RELATED)
                || (!recoveryBuild.isBlank() || !fixCommit.isBlank()) && !(resolution || correction)
                || confirmationId != null && !(correction || action == Action.REVOKE_CONFIRMATION))
            throw new IllegalArgumentException("Inapplicable feedback reference");
        destinations = destinations == null ? List.of() : List.copyOf(destinations);
        if (destinations.size() > 20 || destinations.stream().distinct().count() != destinations.size())
            throw new IllegalArgumentException("Invalid feedback destinations");
    }

    static String text(String value, int limit) {
        value = value == null ? "" : value.strip();
        if (value.length() > limit
                || value.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("Invalid feedback text");
        return value;
    }
}

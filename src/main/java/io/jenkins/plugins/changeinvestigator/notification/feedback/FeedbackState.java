package io.jenkins.plugins.changeinvestigator.notification.feedback;

import java.util.List;
import java.util.Optional;

/** Human history is never truncated to make room for another action. */
public record FeedbackState(List<FeedbackRecord> records) {
    public static final int MAX_RECORDS = 200;

    public FeedbackState {
        records = records == null ? List.of() : List.copyOf(records);
        if (records.size() > MAX_RECORDS
                || records.stream().map(r -> r.request().actionId()).distinct().count() != records.size())
            throw new IllegalArgumentException("Invalid feedback history");
        validateHistory(records);
    }

    private static void validateHistory(List<FeedbackRecord> records) {
        var ids = new java.util.HashSet<java.util.UUID>();
        var current = new java.util.EnumMap<ConfirmationRecord.Kind, ConfirmationRecord>(ConfirmationRecord.Kind.class);
        long previous = 0;
        for (var record : records) {
            if (!ids.add(record.recordId()) || record.beforeRevision() < previous)
                throw new IllegalArgumentException("Invalid feedback history order");
            previous = record.afterRevision();
            var action = record.request().action();
            var confirmation = record.confirmation();
            boolean assertion = action == FeedbackRequest.Action.CONFIRM_CAUSE
                    || action == FeedbackRequest.Action.CONFIRM_RESOLUTION
                    || action == FeedbackRequest.Action.CORRECT_CONFIRMATION
                    || action == FeedbackRequest.Action.REVOKE_CONFIRMATION;
            if (assertion != (confirmation != null))
                throw new IllegalArgumentException("Invalid feedback assertion provenance");
            if (confirmation == null) continue;
            boolean successor = action == FeedbackRequest.Action.CORRECT_CONFIRMATION
                    || action == FeedbackRequest.Action.REVOKE_CONFIRMATION;
            var old = current.get(confirmation.kind());
            if (successor) {
                if (old == null
                        || !old.recordId().equals(confirmation.supersedesRecordId())
                        || !old.recordId().equals(record.request().confirmationId()))
                    throw new IllegalArgumentException("Invalid confirmation successor");
            } else if (old != null
                    || confirmation.supersedesRecordId() != null
                    || action == FeedbackRequest.Action.CONFIRM_CAUSE
                            && confirmation.kind() != ConfirmationRecord.Kind.CAUSE
                    || action == FeedbackRequest.Action.CONFIRM_RESOLUTION
                            && confirmation.kind() != ConfirmationRecord.Kind.RESOLUTION)
                throw new IllegalArgumentException("Invalid confirmation kind");
            if (confirmation.revoked() != (action == FeedbackRequest.Action.REVOKE_CONFIRMATION))
                throw new IllegalArgumentException("Invalid confirmation revocation");
            if (confirmation.revoked()) current.remove(confirmation.kind());
            else current.put(confirmation.kind(), confirmation);
        }
    }

    public static FeedbackState empty() {
        return new FeedbackState(List.of());
    }

    public Optional<ConfirmationRecord> currentCause() {
        return current(ConfirmationRecord.Kind.CAUSE);
    }

    public Optional<ConfirmationRecord> currentResolution() {
        return current(ConfirmationRecord.Kind.RESOLUTION);
    }

    private Optional<ConfirmationRecord> current(ConfirmationRecord.Kind kind) {
        for (int i = records.size() - 1; i >= 0; i--) {
            var confirmation = records.get(i).confirmation();
            if (confirmation != null && confirmation.kind() == kind)
                return confirmation.revoked() ? Optional.empty() : Optional.of(confirmation);
        }
        return Optional.empty();
    }

    public Optional<ConfirmationRecord> currentConfirmation() {
        return java.util.stream.Stream.of(currentCause(), currentResolution())
                .flatMap(Optional::stream)
                .max(java.util.Comparator.comparingLong(ConfirmationRecord::revision));
    }

    public boolean notRelated(String candidateId, long evidenceRevision) {
        return records.stream()
                .anyMatch(r -> r.request().action() == FeedbackRequest.Action.NOT_RELATED
                        && r.request().candidateId().equals(candidateId)
                        && r.request().evidenceRevision() == evidenceRevision);
    }
}

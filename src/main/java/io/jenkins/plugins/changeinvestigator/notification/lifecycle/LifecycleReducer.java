package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure state reducer. Identity membership and ordered provenance are supplied by ingestion. */
public final class LifecycleReducer {
    private LifecycleReducer() {}

    public record Snapshot(
            LifecycleStatus status,
            long revision,
            long occurrenceCount,
            long lastFailureOrder,
            long currentOrder,
            String lastObservationId,
            MaterialFacts facts,
            RecoveryAssessment recoveryAssessment)
            implements Serializable {
        public Snapshot {
            Objects.requireNonNull(status);
            Objects.requireNonNull(facts);
            Objects.requireNonNull(recoveryAssessment);
            if (revision < 1 || occurrenceCount < 1 || lastFailureOrder < 0 || currentOrder < lastFailureOrder) {
                throw new IllegalArgumentException("Invalid lifecycle counters");
            }
            if ((status == LifecycleStatus.ACTIVE || status == LifecycleStatus.RECOVERY_PENDING)
                            && recoveryAssessment != RecoveryAssessment.NONE
                    || (status == LifecycleStatus.RECOVERED || status == LifecycleStatus.CONFIRMED_RESOLUTION)
                            && (recoveryAssessment == RecoveryAssessment.NONE || currentOrder <= lastFailureOrder)
                    || status == LifecycleStatus.RECOVERY_PENDING && currentOrder <= lastFailureOrder) {
                throw new IllegalArgumentException("Inconsistent lifecycle recovery state");
            }
            requireId(lastObservationId);
        }
    }

    public record RecoveryChange(String commit, boolean presentInSuccessfulHistory, String relationship)
            implements Serializable {
        public RecoveryChange {
            commit = Objects.requireNonNullElse(commit, "");
            relationship = Objects.requireNonNullElse(relationship, "");
            if (commit.length() > 160
                    || relationship.length() > 512
                    || !commit.equals(
                            io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(commit, 160))
                    || !relationship.equals(io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(
                            relationship, 512))) {
                throw new IllegalArgumentException("Recovery change bound exceeded");
            }
        }

        private boolean qualifies() {
            return presentInSuccessfulHistory && !commit.isBlank() && !relationship.isBlank();
        }
    }

    public record Observation(
            String id,
            String runId,
            long order,
            String result,
            boolean actualEvidence,
            boolean sameContext,
            boolean orderedAfterLatestFailure,
            String contextDigest,
            String affectedCheck,
            MaterialFacts facts,
            CoverageEvidence coverage,
            List<RecoveryChange> recoveryChanges) {
        public Observation {
            requireId(id);
            requireId(runId);
            Objects.requireNonNull(result);
            Objects.requireNonNull(contextDigest);
            Objects.requireNonNull(affectedCheck);
            Objects.requireNonNull(facts);
            coverage = Objects.requireNonNullElseGet(coverage, CoverageEvidence::unknown);
            recoveryChanges = List.copyOf(Objects.requireNonNull(recoveryChanges));
            if (order < 0 || recoveryChanges.size() > 50) {
                throw new IllegalArgumentException("Observation bound exceeded");
            }
        }
    }

    public record Transition(
            Snapshot snapshot,
            String eventType,
            List<MaterialReason> materialReasons,
            boolean newCaseRequired,
            String reason) {
        public Transition {
            materialReasons = List.copyOf(materialReasons);
        }
    }

    public static Transition reduce(Snapshot previous, Observation observation) {
        Objects.requireNonNull(observation);
        boolean failed = "FAILURE".equals(observation.result()) && observation.actualEvidence();
        if (previous == null) {
            if (!failed) {
                return unchanged(null, "INELIGIBLE_OPENING");
            }
            Snapshot opened = new Snapshot(
                    LifecycleStatus.ACTIVE,
                    1,
                    1,
                    observation.order(),
                    observation.order(),
                    observation.id(),
                    observation.facts(),
                    RecoveryAssessment.NONE);
            return new Transition(opened, "INVESTIGATION_OPENED", List.of(), false, "OPENED");
        }
        if (previous.lastObservationId().equals(observation.id())) {
            return unchanged(previous, "DUPLICATE_OBSERVATION");
        }
        if (observation.order() < previous.currentOrder()) {
            return unchanged(previous, "OUT_OF_ORDER");
        }
        if (!observation.sameContext()) {
            return new Transition(previous, null, List.of(), failed, "CONTEXT_NOT_COMPARABLE");
        }
        if (failed) {
            if (previous.status() == LifecycleStatus.RECOVERED
                    || previous.status() == LifecycleStatus.CONFIRMED_RESOLUTION
                    || previous.status() == LifecycleStatus.CLOSED) {
                return new Transition(previous, null, List.of(), true, "NEW_EPISODE_REQUIRED");
            }
            List<MaterialReason> reasons = reasons(previous.facts(), observation.facts());
            boolean sameRunRevision = previous.currentOrder() == observation.order();
            Snapshot active = new Snapshot(
                    LifecycleStatus.ACTIVE,
                    Math.addExact(previous.revision(), 1),
                    sameRunRevision ? previous.occurrenceCount() : Math.addExact(previous.occurrenceCount(), 1),
                    observation.order(),
                    observation.order(),
                    observation.id(),
                    observation.facts(),
                    RecoveryAssessment.NONE);
            return new Transition(
                    active,
                    reasons.isEmpty() ? null : "INVESTIGATION_UPDATED",
                    reasons,
                    false,
                    reasons.isEmpty() ? "UNCHANGED_FAILURE" : "MATERIAL_UPDATE");
        }
        if (!"SUCCESS".equals(observation.result())
                || previous.status() == LifecycleStatus.RECOVERED
                || previous.status() == LifecycleStatus.CONFIRMED_RESOLUTION
                || previous.status() == LifecycleStatus.CLOSED) {
            return unchanged(previous, "CONTEXT_ONLY");
        }
        if (observation.order() <= previous.lastFailureOrder() || !observation.orderedAfterLatestFailure()) {
            return unchanged(previous, "RECOVERY_ORDER_UNVERIFIED");
        }
        boolean covered = observation
                .coverage()
                .verifies(observation.runId(), observation.contextDigest(), observation.affectedCheck());
        RecoveryAssessment assessment = RecoveryAssessment.NONE;
        if (covered) {
            List<String> plausible = observation.recoveryChanges().stream()
                    .filter(RecoveryChange::qualifies)
                    .map(RecoveryChange::commit)
                    .distinct()
                    .toList();
            assessment =
                    plausible.size() == 1 ? RecoveryAssessment.LIKELY_RECOVERY_CHANGE : RecoveryAssessment.FIX_UNKNOWN;
        }
        Snapshot next = new Snapshot(
                covered ? LifecycleStatus.RECOVERED : LifecycleStatus.RECOVERY_PENDING,
                Math.addExact(previous.revision(), 1),
                previous.occurrenceCount(),
                previous.lastFailureOrder(),
                observation.order(),
                observation.id(),
                previous.facts(),
                assessment);
        return new Transition(
                next,
                covered ? "RECOVERY_OBSERVED" : null,
                covered ? List.of(MaterialReason.RECOVERY_VERIFIED) : List.of(),
                false,
                covered ? "COVERAGE_VERIFIED" : "COVERAGE_UNVERIFIED");
    }

    public static List<MaterialReason> reasons(MaterialFacts before, MaterialFacts after) {
        List<MaterialReason> reasons = new ArrayList<>();
        if (!before.verifiedFirstBadRunId().equals(after.verifiedFirstBadRunId())) {
            reasons.add(
                    before.verifiedFirstBadRunId().isEmpty()
                                    && !after.verifiedFirstBadRunId().isEmpty()
                            ? MaterialReason.FIRST_BAD_VERIFIED
                            : MaterialReason.BOUNDARY_CORRECTED);
        }
        if (!candidateIdentities(before).equals(candidateIdentities(after))) {
            reasons.add(MaterialReason.TOP_CANDIDATE_CHANGED);
        } else if (!java.util.Set.copyOf(before.topCandidates()).equals(java.util.Set.copyOf(after.topCandidates()))) {
            reasons.add(MaterialReason.EVIDENCE_STRENGTH_CHANGED);
        }
        if (!before.relevantCommits().equals(after.relevantCommits())) {
            reasons.add(
                    after.relevantCommits().containsAll(before.relevantCommits())
                            ? MaterialReason.RELEVANT_CHANGE_ADDED
                            : MaterialReason.CORRECTION);
        }
        if (!before.responderIdentity().equals(after.responderIdentity())) {
            reasons.add(MaterialReason.RESPONDER_CHANGED);
        }
        if (!before.recoveryCandidateIdentity().equals(after.recoveryCandidateIdentity())) {
            reasons.add(
                    after.recoveryCandidateIdentity().isEmpty()
                            ? MaterialReason.CORRECTION
                            : MaterialReason.RECOVERY_CANDIDATE_ADDED);
        }
        if (!before.correctionIdentity().equals(after.correctionIdentity())) {
            reasons.add(MaterialReason.CORRECTION);
        }
        return reasons.stream().distinct().toList();
    }

    private static List<String> candidateIdentities(MaterialFacts facts) {
        return facts.topCandidates().stream()
                .map(c -> c.repository().length() + ":" + c.repository()
                        + c.path().length() + ":" + c.path() + c.commit().length() + ":" + c.commit())
                .distinct()
                .sorted()
                .toList();
    }

    private static Transition unchanged(Snapshot previous, String reason) {
        return new Transition(previous, null, List.of(), false, reason);
    }

    private static void requireId(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid observation identifier");
        }
    }
}

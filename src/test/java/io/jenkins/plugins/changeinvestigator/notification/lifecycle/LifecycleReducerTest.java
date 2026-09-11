package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LifecycleReducerTest {
    private static final MaterialFacts A = facts("a", "STRONG", "");

    private static MaterialFacts facts(String commit, String strength, String firstBad) {
        return new MaterialFacts(
                firstBad,
                List.of(new MaterialFacts.Candidate("repo", "src/Check.java", commit, strength, List.of("SAME_FILE"))),
                List.of(commit),
                "team",
                "",
                "");
    }

    private static LifecycleReducer.Observation observation(
            long order, String result, MaterialFacts facts, CoverageEvidence coverage) {
        return new LifecycleReducer.Observation(
                "observation-" + order,
                "run-" + order,
                order,
                result,
                true,
                true,
                true,
                "context",
                "compiler:Check",
                facts,
                coverage,
                List.of());
    }

    private static CoverageEvidence coverage(long order) {
        return new CoverageEvidence("compiler-task", 1, "compiler:Check", "run-" + order, "context", true, true);
    }

    private static LifecycleReducer.Snapshot opened() {
        return LifecycleReducer.reduce(null, observation(221, "FAILURE", A, null))
                .snapshot();
    }

    @Test
    void tenFailuresHaveOneOpeningAndNoRepeatedEvents() {
        LifecycleReducer.Snapshot state = null;
        int events = 0;
        for (int i = 221; i < 231; i++) {
            var result = LifecycleReducer.reduce(state, observation(i, "FAILURE", A, null));
            state = result.snapshot();
            if (result.eventType() != null) events++;
        }
        assertEquals(1, events);
        assertEquals(10, state.occurrenceCount());
        assertEquals(LifecycleStatus.ACTIVE, state.status());
    }

    @Test
    void duplicateObservationDoesNotAdvanceRevision() {
        var state = opened();
        assertSame(
                state,
                LifecycleReducer.reduce(state, observation(221, "FAILURE", A, null))
                        .snapshot());
    }

    @Test
    void contextualResultsNeverOpen() {
        for (String result : List.of("SUCCESS", "ABORTED", "UNSTABLE", "NOT_BUILT")) {
            assertNull(LifecycleReducer.reduce(null, observation(1, result, A, null))
                    .snapshot());
        }
    }

    @Test
    void failureWithoutActualEvidenceNeverOpens() {
        var o = new LifecycleReducer.Observation(
                "o", "r", 1, "FAILURE", false, true, true, "context", "check", A, null, List.of());
        assertNull(LifecycleReducer.reduce(null, o).snapshot());
    }

    @Test
    void firstBadVerificationIsMaterialAndKeepsOccurrenceForSameRunRevision() {
        var o = new LifecycleReducer.Observation(
                "revision-two",
                "run-221",
                221,
                "FAILURE",
                true,
                true,
                true,
                "context",
                "compiler:Check",
                facts("a", "STRONG", "run-220"),
                null,
                List.of());
        var result = LifecycleReducer.reduce(opened(), o);
        assertEquals("INVESTIGATION_UPDATED", result.eventType());
        assertEquals(List.of(MaterialReason.FIRST_BAD_VERIFIED), result.materialReasons());
        assertEquals(1, result.snapshot().occurrenceCount());
    }

    @Test
    void tiesAndRelationshipReorderAreImmaterial() {
        var a = new MaterialFacts.Candidate("repo", "A.java", "a", "STRONG", List.of("FILE", "MODULE"));
        var b = new MaterialFacts.Candidate("repo", "B.java", "b", "STRONG", List.of("MODULE", "FILE"));
        var first = new MaterialFacts("", List.of(a, b), List.of("a", "b"), "team", "", "");
        var second = new MaterialFacts("", List.of(b, a), List.of("b", "a"), "team", "", "");
        assertEquals(first.fingerprint(), second.fingerprint());
        assertTrue(LifecycleReducer.reasons(first, second).isEmpty());
    }

    @Test
    void evidenceStrengthChangesInEitherDirection() {
        assertTrue(LifecycleReducer.reasons(A, facts("a", "MODERATE", ""))
                .contains(MaterialReason.EVIDENCE_STRENGTH_CHANGED));
        assertTrue(LifecycleReducer.reasons(facts("a", "MODERATE", ""), A)
                .contains(MaterialReason.EVIDENCE_STRENGTH_CHANGED));
    }

    @Test
    void candidateReversalIsMaterial() {
        var b = facts("b", "STRONG", "");
        assertTrue(LifecycleReducer.reasons(A, b).contains(MaterialReason.TOP_CANDIDATE_CHANGED));
        assertTrue(LifecycleReducer.reasons(b, A).contains(MaterialReason.TOP_CANDIDATE_CHANGED));
    }

    @Test
    void skippedCheckBecomesPendingThenSameFailureContinues() {
        var skipped = new CoverageEvidence("tests", 1, "compiler:Check", "run-222", "context", false, true);
        var pending = LifecycleReducer.reduce(opened(), observation(222, "SUCCESS", A, skipped));
        assertEquals(LifecycleStatus.RECOVERY_PENDING, pending.snapshot().status());
        assertNull(pending.eventType());
        var failed = LifecycleReducer.reduce(pending.snapshot(), observation(223, "FAILURE", A, null));
        assertEquals(LifecycleStatus.ACTIVE, failed.snapshot().status());
        assertFalse(failed.newCaseRequired());
        assertNull(failed.eventType());
    }

    @Test
    void coverageMustNameExactRunContextCheckAndVersion() {
        var values = List.of(
                CoverageEvidence.unknown(),
                new CoverageEvidence("tests", 0, "compiler:Check", "run-222", "context", true, true),
                new CoverageEvidence("tests", 1, "other", "run-222", "context", true, true),
                new CoverageEvidence("tests", 1, "compiler:Check", "run-999", "context", true, true),
                new CoverageEvidence("tests", 1, "compiler:Check", "run-222", "other", true, true),
                new CoverageEvidence("tests", 1, "compiler:Check", "run-222", "context", true, false));
        for (var value : values) {
            assertEquals(
                    LifecycleStatus.RECOVERY_PENDING,
                    LifecycleReducer.reduce(opened(), observation(222, "SUCCESS", A, value))
                            .snapshot()
                            .status());
        }
    }

    @Test
    void validRecoveryWithoutCommitsIsFixUnknown() {
        var result = LifecycleReducer.reduce(opened(), observation(222, "SUCCESS", A, coverage(222)));
        assertEquals("RECOVERY_OBSERVED", result.eventType());
        assertEquals(RecoveryAssessment.FIX_UNKNOWN, result.snapshot().recoveryAssessment());
    }

    @Test
    void olderSuccessCannotRecover() {
        var state = LifecycleReducer.reduce(opened(), observation(225, "FAILURE", A, null))
                .snapshot();
        assertSame(
                state,
                LifecycleReducer.reduce(state, observation(222, "SUCCESS", A, coverage(222)))
                        .snapshot());
    }

    @Test
    void orderingProvenanceIsRequiredEvenWithCoverage() {
        var o = new LifecycleReducer.Observation(
                "o",
                "run-222",
                222,
                "SUCCESS",
                true,
                true,
                false,
                "context",
                "compiler:Check",
                A,
                coverage(222),
                List.of());
        assertSame(
                opened().status(),
                LifecycleReducer.reduce(opened(), o).snapshot().status());
        assertNull(LifecycleReducer.reduce(opened(), o).eventType());
    }

    @Test
    void recurrenceLeavesRecoveryUntouchedAndRequiresNewCase() {
        var recovered = LifecycleReducer.reduce(opened(), observation(222, "SUCCESS", A, coverage(222)))
                .snapshot();
        var recurrence = LifecycleReducer.reduce(recovered, observation(223, "FAILURE", A, null));
        assertTrue(recurrence.newCaseRequired());
        assertSame(recovered, recurrence.snapshot());
    }

    @Test
    void differentContextNeverClosesOldCase() {
        var o = new LifecycleReducer.Observation(
                "o",
                "run-222",
                222,
                "SUCCESS",
                true,
                false,
                true,
                "other",
                "compiler:Check",
                A,
                coverage(222),
                List.of());
        assertEquals(
                LifecycleStatus.ACTIVE,
                LifecycleReducer.reduce(opened(), o).snapshot().status());
    }

    @Test
    void likelyRequiresOneDistinctPresentRelatedCommit() {
        var one = new LifecycleReducer.RecoveryChange("fix", true, "SAME_FILE");
        var other = new LifecycleReducer.RecoveryChange("other", true, "SAME_FILE");
        for (var changes : List.of(
                List.of(one),
                List.of(one, one),
                List.of(one, other),
                List.of(new LifecycleReducer.RecoveryChange("fix", false, "SAME_FILE")))) {
            var o = new LifecycleReducer.Observation(
                    "o",
                    "run-222",
                    222,
                    "SUCCESS",
                    true,
                    true,
                    true,
                    "context",
                    "compiler:Check",
                    A,
                    coverage(222),
                    changes);
            var expected = changes.equals(List.of(one)) || changes.equals(List.of(one, one))
                    ? RecoveryAssessment.LIKELY_RECOVERY_CHANGE
                    : RecoveryAssessment.FIX_UNKNOWN;
            assertEquals(
                    expected, LifecycleReducer.reduce(opened(), o).snapshot().recoveryAssessment());
        }
    }

    @Test
    void materialFactsRejectBoundsAndSensitiveCriticalInputs() {
        assertThrows(IllegalArgumentException.class, () -> facts("a".repeat(513), "STRONG", ""));
        assertThrows(IllegalArgumentException.class, () -> facts("password=synthetic-secret", "STRONG", ""));
        assertThrows(IllegalArgumentException.class, () -> facts("a\nother", "STRONG", ""));
    }

    @Test
    void materialCanonicalEncodingCannotAliasDelimiters() {
        var a = new MaterialFacts("a:1", List.of(), List.of(), "b", "", "");
        var b = new MaterialFacts("a", List.of(), List.of(), "1:b", "", "");
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void scopedAiIsSeparateAndFailsClosedForStaleScope() {
        var projection = new AiProjection(AiProjection.State.AI_COMPLETE, "scope-a", "assessment-a");
        assertTrue(projection.currentCompletion("scope-a"));
        assertFalse(projection.currentCompletion("scope-b"));
        assertFalse(new AiProjection(AiProjection.State.AI_FAILED, "scope-a", "a").currentCompletion("scope-a"));
    }

    @Test
    void snapshotRoundTripDoesNotReopenOrMutate() throws Exception {
        var mapper = new ObjectMapper();
        var snapshot = opened();
        var copy = mapper.readValue(mapper.writeValueAsBytes(snapshot), LifecycleReducer.Snapshot.class);
        assertEquals(snapshot, copy);
        assertNull(LifecycleReducer.reduce(copy, observation(222, "FAILURE", A, null))
                .eventType());
    }
}

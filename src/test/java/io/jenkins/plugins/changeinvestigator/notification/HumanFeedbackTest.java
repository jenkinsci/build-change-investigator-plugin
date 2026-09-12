package io.jenkins.plugins.changeinvestigator.notification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.feedback.*;
import io.jenkins.plugins.changeinvestigator.notification.identity.*;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanFeedbackTest {
    @TempDir
    Path directory;

    private final UUID job = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final FeedbackActor actor = new FeedbackActor("reviewer", "Demo reviewer");

    static ObjectNode template() throws Exception {
        var value = NotificationEngineTest.template();
        var candidate = value.putArray("topCandidates").addObject();
        candidate
                .put("candidateId", "a".repeat(64))
                .put("path", "src/Trade.java")
                .put("commit", "a254c24")
                .put("authorLabel", "Demo developer")
                .put("strength", "STRONG")
                .put("relationship", "Changed file matches compiler failure")
                .put("limitation", "Exact changed line unverified")
                .put("nextCheck", "Inspect the changed source file");
        candidate.putArray("evidenceRefs").add("compiler:file");
        return value;
    }

    private NotificationEngine engine() throws Exception {
        var engine = new NotificationEngine(directory, job);
        engine.arm(0);
        return engine;
    }

    private FeedbackRequest request(NotificationInvestigationRecord record, FeedbackRequest.Action action) {
        return new FeedbackRequest(
                action,
                UUID.randomUUID(),
                record.caseRevision(),
                "",
                record.evidenceRevision(),
                "",
                "Reviewed change",
                "Verified with build evidence",
                "",
                "Local private note",
                null,
                List.of(),
                0);
    }

    private FeedbackRequest copy(
            FeedbackRequest r, String candidate, String recovery, UUID confirmation, List<UUID> destinations) {
        return new FeedbackRequest(
                r.action(),
                r.actionId(),
                r.expectedRevision(),
                candidate,
                r.evidenceRevision(),
                recovery,
                r.correctiveAction(),
                r.validationBasis(),
                r.fixCommit(),
                r.note(),
                confirmation,
                destinations,
                r.muteUntil());
    }

    private FeedbackResult apply(
            NotificationEngine engine, NotificationInvestigationRecord record, FeedbackRequest r, long at)
            throws Exception {
        return engine.feedback(record.investigationId(), r, actor, () -> true, value -> false, at);
    }

    private NotificationInvestigationRecord load(NotificationEngine engine) throws Exception {
        return engine.records().get(0);
    }

    private NotificationObservation observation(
            int order, String symbol, String result, boolean covered, boolean complete) throws Exception {
        String id = UUID.nameUUIDFromBytes((job + ":" + order).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        var context = ExecutionContextV1.create(
                job.toString(),
                List.of(new ExecutionContextV1.ScmSource("primary", "demo-repository", "BRANCH", "main", null)),
                ExecutionContextV1.Origin.NORMAL,
                true,
                true);
        var signature = symbol == null
                ? FailureSignatureV1.unresolved(
                        FailureSignatureV1.Category.GENERIC,
                        FailureSignatureV1.ContextFields.empty(),
                        id,
                        "GENERIC_DIAGNOSTIC")
                : FailureSignatureV1.create(
                        FailureSignatureV1.Category.COMPILER,
                        Map.of(
                                "profile",
                                "COMPILER",
                                "scmSourceId",
                                "primary",
                                "repositoryPath",
                                "src/Trade.java",
                                "diagnosticKind",
                                "cannot-find-symbol",
                                "discriminant",
                                symbol),
                        FailureSignatureV1.ContextFields.empty(),
                        id);
        var display = template();
        display.putObject("boundary")
                .putNull("lastKnownGood")
                .putNull("firstBad")
                .put("firstBadVerified", false)
                .put("proofStatus", "UNKNOWN");
        ((ObjectNode) display.get("build"))
                .put("runId", id)
                .put("number", order)
                .put("result", result);
        return new NotificationObservation(
                id,
                id,
                order,
                result,
                true,
                complete,
                context,
                signature,
                "compile:demo",
                MaterialFacts.empty(),
                covered
                        ? new CoverageEvidence("compiler-task", 1, "compile:demo", id, context.digest(), true, true)
                        : CoverageEvidence.unknown(),
                List.of(),
                display);
    }

    @Test
    void acknowledgementIsLocalAndSurvivesEvidenceIngestion() throws Exception {
        var e = engine();
        var initial = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var event = initial.events().get(0).json();
        apply(e, initial, request(initial, FeedbackRequest.Action.ACKNOWLEDGE), 2);
        var updated = e.ingest(observation(2, "symbol", "FAILURE", false, true), List.of(destination), 3);
        assertEquals(1, updated.feedback().records().size());
        assertEquals(3, updated.caseRevision());
        assertEquals(2, updated.evidenceRevision());
        assertEquals(event, updated.events().get(0).json());
        assertEquals(
                initial.destinations().get(0).intents(),
                updated.destinations().get(0).intents());
    }

    @Test
    void nonceReplayIsIdempotentButStillRequiresFreshAuthorization() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var command = request(r, FeedbackRequest.Action.ACKNOWLEDGE);
        var first = apply(e, r, command, 2);
        var replay = apply(e, r, command, 3);
        assertTrue(replay.duplicate());
        assertEquals(first.recordId(), replay.recordId());
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> e.feedback(r.investigationId(), command, actor, () -> false, v -> false, 4));
        assertThrows(
                IOException.class,
                () -> e.feedback(
                        r.investigationId(), command, new FeedbackActor("other", "Other"), () -> true, v -> false, 4));
        assertEquals(1, load(e).feedback().records().size());
    }

    @Test
    void staleFormCannotOverwriteNewerReview() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        apply(e, r, request(r, FeedbackRequest.Action.ACKNOWLEDGE), 2);
        assertThrows(IOException.class, () -> apply(e, r, request(r, FeedbackRequest.Action.ACKNOWLEDGE), 3));
        assertEquals(1, load(e).feedback().records().size());
    }

    @Test
    void foreignCandidateIsRejectedWithoutAnyMutation() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var command = copy(request(r, FeedbackRequest.Action.NOT_RELATED), "f".repeat(64), "", null, List.of());
        assertThrows(IllegalArgumentException.class, () -> apply(e, r, command, 2));
        assertEquals(r, load(e));
    }

    @Test
    void foreignDestinationIsRejected() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var command = copy(request(r, FeedbackRequest.Action.MUTE), "", "", null, List.of(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> apply(e, r, command, 2));
        assertEquals(r, load(e));
    }

    @Test
    void activeFailureCannotBeConfirmedResolved() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        assertThrows(IOException.class, () -> apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_RESOLUTION), 2));
        assertEquals(LifecycleStatus.ACTIVE, load(e).lifecycle().status());
    }

    @Test
    void foreignRecoveryBuildCannotBeConfirmed() throws Exception {
        var e = engine();
        e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var r = e.ingest(observation(2, "symbol", "SUCCESS", true, true), List.of(), 2);
        var command = copy(
                request(r, FeedbackRequest.Action.CONFIRM_RESOLUTION),
                "",
                UUID.randomUUID().toString(),
                null,
                List.of());
        assertThrows(IOException.class, () -> apply(e, r, command, 3));
        assertEquals(r, load(e));
    }

    @Test
    void causeConfirmationDoesNotAssertRecoveryAndKeepsNotePrivate() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var changed = load(e);
        assertEquals(LifecycleStatus.ACTIVE, changed.lifecycle().status());
        assertEquals(
                ConfirmationRecord.Kind.CAUSE,
                changed.feedback().currentConfirmation().orElseThrow().kind());
        String event = changed.events().get(changed.events().size() - 1).json();
        assertFalse(event.contains("Local private note"));
        assertFalse(event.contains("\"actorId\""));
        assertTrue(event.contains("CAUSE_CONFIRMED"));
        assertEquals(r.evidenceRevision(), changed.evidenceRevision());
    }

    @Test
    void forgedConfirmationReferenceCannotRevoke() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var current = load(e);
        var command = copy(
                request(current, FeedbackRequest.Action.REVOKE_CONFIRMATION), "", "", UUID.randomUUID(), List.of());
        assertThrows(IOException.class, () -> apply(e, current, command, 3));
        assertEquals(current, load(e));
    }

    @Test
    void resolutionAndRevocationRetainOriginalRecoveryAndAppendHistory() throws Exception {
        var e = engine();
        e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var r = e.ingest(observation(2, "symbol", "SUCCESS", true, true), List.of(), 2);
        String recovery = r.events()
                .get(r.events().size() - 1)
                .snapshot()
                .path("recovery")
                .path("build")
                .path("runId")
                .asText();
        var result = apply(
                e, r, copy(request(r, FeedbackRequest.Action.CONFIRM_RESOLUTION), "", recovery, null, List.of()), 3);
        var confirmed = load(e);
        assertEquals(LifecycleStatus.CONFIRMED_RESOLUTION, confirmed.lifecycle().status());
        apply(
                e,
                confirmed,
                copy(
                        request(confirmed, FeedbackRequest.Action.REVOKE_CONFIRMATION),
                        "",
                        "",
                        result.recordId(),
                        List.of()),
                4);
        var revoked = load(e);
        assertEquals(LifecycleStatus.RECOVERED, revoked.lifecycle().status());
        assertEquals(2, revoked.feedback().records().size());
        assertTrue(revoked.feedback().currentConfirmation().isEmpty());
        assertEquals(r.events().get(1).json(), revoked.events().get(1).json());
    }

    @Test
    void secretGuardRejectsWholeMutationIncludingPrivateAudit() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var command = request(r, FeedbackRequest.Action.CONFIRM_CAUSE);
        assertThrows(
                IOException.class,
                () -> e.feedback(r.investigationId(), command, actor, () -> true, v -> v.contains("Local private"), 2));
        assertEquals(r, load(e));
    }

    @Test
    void muteCancelsUnattemptedIntentAndUnmuteDoesNotReplay() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        apply(e, r, request(r, FeedbackRequest.Action.MUTE), 2);
        var muted = load(e);
        assertEquals(
                SuppressionPolicy.DAY + 2, muted.destinations().get(0).policy().mutedUntil());
        assertEquals(
                io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State.CANCELLED,
                muted.destinations().get(0).intents().get(0).state());
        apply(e, muted, request(muted, FeedbackRequest.Action.UNMUTE), 3);
        assertEquals(
                muted.destinations().get(0).intents(),
                load(e).destinations().get(0).intents());
        assertEquals(0, load(e).destinations().get(0).policy().mutedUntil());
    }

    @Test
    void readonlyPeekDoesNotCreateDirectories() throws Exception {
        Path absent = directory.resolve("absent");
        assertTrue(NotificationEngine.peek(absent, job, UUID.randomUUID()).isEmpty());
        assertFalse(java.nio.file.Files.exists(absent));
    }

    @Test
    void hundredConcurrentReviewersApplyOneNonceExactlyOnce() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var command = request(r, FeedbackRequest.Action.ACKNOWLEDGE);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<FeedbackResult>>();
            for (int i = 0; i < 100; i++) tasks.add(() -> apply(e, r, command, 2));
            int applied = 0;
            for (var result : pool.invokeAll(tasks)) if (!result.get().duplicate()) applied++;
            assertEquals(1, applied);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, load(e).feedback().records().size());
    }

    @Test
    void hundredIndependentInvestigationsKeepReviewerHistoryIsolated() throws Exception {
        var e = engine();
        var cases = new ArrayList<NotificationInvestigationRecord>();
        for (int i = 1; i <= 100; i++)
            cases.add(e.ingest(observation(i, "symbol" + i, "FAILURE", false, true), List.of(), i));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<FeedbackResult>>();
            for (var r : cases) tasks.add(() -> apply(e, r, request(r, FeedbackRequest.Action.ACKNOWLEDGE), 1000));
            for (var result : pool.invokeAll(tasks)) assertFalse(result.get().duplicate());
        } finally {
            pool.shutdownNow();
        }
        for (var r : e.records()) {
            assertEquals(1, r.feedback().records().size());
            assertEquals(2, r.caseRevision());
        }
    }

    @Test
    void rejectingWeakCandidateRemainsLocalWhileStrongRejectionIsMaterial() throws Exception {
        var e = engine();
        var input = observation(1, "symbol", "FAILURE", false, true);
        var display = input.display();
        ((ObjectNode) display.path("topCandidates").get(0)).put("strength", "WEAK");
        var weakInput = new NotificationObservation(
                input.observationId(),
                input.runId(),
                input.order(),
                input.result(),
                input.actualEvidence(),
                input.completeHistory(),
                input.context(),
                input.signature(),
                input.affectedCheck(),
                input.facts(),
                input.coverage(),
                input.recoveryChanges(),
                display);
        var weak = e.ingest(weakInput, List.of(), 1);
        String candidate = weak.events()
                .get(0)
                .snapshot()
                .path("topCandidates")
                .get(0)
                .path("candidateId")
                .asText();
        apply(e, weak, copy(request(weak, FeedbackRequest.Action.NOT_RELATED), candidate, "", null, List.of()), 2);
        assertEquals(1, load(e).events().size());
        assertTrue(load(e).feedback().notRelated(candidate, weak.evidenceRevision()));
    }

    @Test
    void strongCandidateRejectionIdentifiesCandidateWithoutChangingEvidence() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        String candidate = r.events()
                .get(0)
                .snapshot()
                .path("topCandidates")
                .get(0)
                .path("candidateId")
                .asText();
        apply(e, r, copy(request(r, FeedbackRequest.Action.NOT_RELATED), candidate, "", null, List.of()), 2);
        var changed = load(e);
        assertEquals(2, changed.events().size());
        assertEquals(r.events().get(0).json(), changed.events().get(0).json());
        assertEquals(
                candidate,
                changed.events()
                        .get(1)
                        .snapshot()
                        .path("humanReview")
                        .path("candidateId")
                        .asText());
        var repeat = copy(request(changed, FeedbackRequest.Action.NOT_RELATED), candidate, "", null, List.of());
        apply(e, changed, repeat, 3);
        assertEquals(2, load(e).events().size());
    }

    @Test
    void revokingUnsentAssertionCancelsItWithoutSendingCorrection() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var result = apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var confirmed = load(e);
        apply(
                e,
                confirmed,
                copy(
                        request(confirmed, FeedbackRequest.Action.REVOKE_CONFIRMATION),
                        "",
                        "",
                        result.recordId(),
                        List.of()),
                3);
        var changed = load(e);
        assertTrue(changed.destinations().get(0).intents().stream()
                .allMatch(i -> i.state()
                        == io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State
                                .CANCELLED));
        assertNull(changed.destinations().get(0).policy().pending());
    }

    @Test
    void revokingLeasedAssertionStopsItsFinalSubmissionFence() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var result = apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var confirmed = load(e);
        var pending = confirmed.destinations().get(0).intents().stream()
                .filter(i -> i.state()
                        == io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State.QUEUED)
                .findFirst()
                .orElseThrow();
        var lease = pending.lease(20000, 30000);
        e.updateDestination(
                r.investigationId(),
                destination,
                state -> {
                    var intents = new ArrayList<>(state.intents());
                    intents.set(intents.indexOf(pending), lease);
                    return new NotificationInvestigationRecord.DestinationState(
                            state.destinationId(),
                            state.generation(),
                            state.policy(),
                            intents,
                            state.submissions(),
                            state.transport());
                },
                "TEST_LEASE",
                20000);
        assertTrue(
                e.submissionAllowed(r.investigationId(), destination, lease.deliveryId(), lease.leaseToken(), 20001));
        apply(
                e,
                load(e),
                copy(
                        request(load(e), FeedbackRequest.Action.REVOKE_CONFIRMATION),
                        "",
                        "",
                        result.recordId(),
                        List.of()),
                20002);
        assertFalse(
                e.submissionAllowed(r.investigationId(), destination, lease.deliveryId(), lease.leaseToken(), 20003));
        assertEquals(
                io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State.LEASED,
                load(e).destinations().get(0).intents().stream()
                        .filter(i -> i.deliveryId().equals(lease.deliveryId()))
                        .findFirst()
                        .orElseThrow()
                        .state());
    }

    @Test
    void muteRequiresRecordedReason() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var command = new FeedbackRequest(
                FeedbackRequest.Action.MUTE,
                UUID.randomUUID(),
                r.caseRevision(),
                "",
                r.evidenceRevision(),
                "",
                "",
                "",
                "",
                "",
                null,
                List.of(),
                0);
        assertThrows(IllegalArgumentException.class, () -> apply(e, r, command, 2));
        assertEquals(r, load(e));
    }

    @Test
    void readonlyPeekRejectsDuplicateEnvelopeKeys() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        Path file = directory.resolve("bci-notifications/cases/" + r.investigationId() + ".json");
        String json = java.nio.file.Files.readString(file);
        java.nio.file.Files.writeString(file, json.replaceFirst("\\{", "{\"schemaVersion\":1,"));
        assertThrows(IOException.class, () -> NotificationEngine.peek(directory, job, r.investigationId()));
        assertTrue(java.nio.file.Files.exists(file));
    }

    private NotificationInvestigationRecord recoveredWithFix(NotificationEngine engine) throws Exception {
        engine.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var input = observation(2, "symbol", "SUCCESS", true, true);
        return engine.ingest(
                new NotificationObservation(
                        input.observationId(),
                        input.runId(),
                        input.order(),
                        input.result(),
                        input.actualEvidence(),
                        input.completeHistory(),
                        input.context(),
                        input.signature(),
                        input.affectedCheck(),
                        input.facts(),
                        input.coverage(),
                        List.of(new LifecycleReducer.RecoveryChange(
                                "b".repeat(40), true, "Changed recovery source maps to the failed compiler check")),
                        input.display()),
                List.of(),
                2);
    }

    private FeedbackRequest fixRequest(NotificationInvestigationRecord record, String commit) {
        var base = request(record, FeedbackRequest.Action.CONFIRM_RESOLUTION);
        String build = record.events()
                .get(record.events().size() - 1)
                .snapshot()
                .path("recovery")
                .path("build")
                .path("runId")
                .asText();
        return new FeedbackRequest(
                base.action(),
                base.actionId(),
                base.expectedRevision(),
                base.candidateId(),
                base.evidenceRevision(),
                build,
                base.correctiveAction(),
                base.validationBasis(),
                commit,
                base.note(),
                null,
                List.of(),
                0);
    }

    @Test
    void fixCommitMatchesRetainedVerifiedRecoveryCandidateHash() throws Exception {
        var e = engine();
        var record = recoveredWithFix(e);
        apply(e, record, fixRequest(record, "b".repeat(40)), 3);
        assertEquals(
                "b".repeat(40),
                load(e).feedback().currentConfirmation().orElseThrow().fixCommit());
    }

    @Test
    void unrelatedFixCommitCannotBecomeConfirmedRecoveryEvidence() throws Exception {
        var e = engine();
        var record = recoveredWithFix(e);
        assertThrows(IllegalArgumentException.class, () -> apply(e, record, fixRequest(record, "a254c24"), 3));
        assertEquals(record, load(e));
    }

    @Test
    void causeConfirmationDoesNotAcceptFixCommit() throws Exception {
        var e = engine();
        var record = recoveredWithFix(e);
        var base = fixRequest(record, "b".repeat(40));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeedbackRequest(
                        FeedbackRequest.Action.CONFIRM_CAUSE,
                        base.actionId(),
                        base.expectedRevision(),
                        "",
                        base.evidenceRevision(),
                        "",
                        base.correctiveAction(),
                        base.validationBasis(),
                        base.fixCommit(),
                        base.note(),
                        null,
                        List.of(),
                        0));
        assertEquals(record, load(e));
    }

    @Test
    void revokingResolutionPreservesIndependentlyConfirmedCause() throws Exception {
        var e = engine();
        var initial = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var cause = apply(e, initial, request(initial, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var recovered = e.ingest(observation(2, "symbol", "SUCCESS", true, true), List.of(), 3);
        String build = recovered
                .events()
                .get(recovered.events().size() - 1)
                .snapshot()
                .path("recovery")
                .path("build")
                .path("runId")
                .asText();
        var resolution = apply(
                e,
                recovered,
                copy(request(recovered, FeedbackRequest.Action.CONFIRM_RESOLUTION), "", build, null, List.of()),
                4);
        var confirmed = load(e);
        assertEquals(
                cause.recordId(),
                confirmed.feedback().currentCause().orElseThrow().recordId());
        assertNull(confirmed.feedback().currentResolution().orElseThrow().supersedesRecordId());
        apply(
                e,
                confirmed,
                copy(
                        request(confirmed, FeedbackRequest.Action.REVOKE_CONFIRMATION),
                        "",
                        "",
                        resolution.recordId(),
                        List.of()),
                5);
        var revoked = load(e);
        assertEquals(LifecycleStatus.RECOVERED, revoked.lifecycle().status());
        assertTrue(revoked.feedback().currentResolution().isEmpty());
        assertEquals(
                cause.recordId(),
                revoked.feedback().currentCause().orElseThrow().recordId());
        assertEquals(
                cause.recordId(),
                revoked.feedback().currentConfirmation().orElseThrow().recordId());
    }

    @Test
    void revokingCausePreservesConfirmedResolutionAndItsExternalProvenance() throws Exception {
        var e = engine();
        var initial = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var cause = apply(e, initial, request(initial, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var recovered = e.ingest(observation(2, "symbol", "SUCCESS", true, true), List.of(), 3);
        String build = recovered
                .events()
                .get(recovered.events().size() - 1)
                .snapshot()
                .path("recovery")
                .path("build")
                .path("runId")
                .asText();
        var resolution = apply(
                e,
                recovered,
                copy(request(recovered, FeedbackRequest.Action.CONFIRM_RESOLUTION), "", build, null, List.of()),
                4);
        var confirmed = load(e);
        var command = copy(
                request(confirmed, FeedbackRequest.Action.REVOKE_CONFIRMATION), "", "", cause.recordId(), List.of());
        e.feedback(
                confirmed.investigationId(),
                command,
                new FeedbackActor("other", "Other reviewer"),
                () -> true,
                v -> false,
                5);
        var changed = load(e);
        assertEquals(LifecycleStatus.CONFIRMED_RESOLUTION, changed.lifecycle().status());
        assertTrue(changed.feedback().currentCause().isEmpty());
        assertEquals(
                resolution.recordId(),
                changed.feedback().currentResolution().orElseThrow().recordId());
        var event = changed.events().get(changed.events().size() - 1).snapshot();
        assertEquals(
                "Demo reviewer", event.path("confirmation").path("actorLabel").asText());
        assertEquals(
                "Other reviewer", event.path("humanReview").path("actorLabel").asText());
    }

    private Path caseFile(NotificationInvestigationRecord record) {
        return directory.resolve("bci-notifications/cases/" + record.investigationId() + ".json");
    }

    private ObjectNode envelope(NotificationInvestigationRecord record) throws Exception {
        return (ObjectNode) new ObjectMapper().readTree(java.nio.file.Files.readString(caseFile(record)));
    }

    @Test
    void transplantedConfirmationCaseIsRejectedByReadonlyAccess() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var current = load(e);
        var data = envelope(current);
        ((ObjectNode) data.path("aggregate")
                        .path("feedback")
                        .path("records")
                        .get(0)
                        .path("confirmation"))
                .put("investigationId", UUID.randomUUID().toString());
        java.nio.file.Files.writeString(caseFile(current), data.toString());
        assertThrows(IOException.class, () -> NotificationEngine.peek(directory, job, current.investigationId()));
    }

    @Test
    void reorderedActionHistoryIsRejected() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        apply(e, r, request(r, FeedbackRequest.Action.ACKNOWLEDGE), 2);
        var one = load(e);
        apply(e, one, request(one, FeedbackRequest.Action.ACKNOWLEDGE), 3);
        var current = load(e);
        var data = envelope(current);
        var records = (com.fasterxml.jackson.databind.node.ArrayNode)
                data.path("aggregate").path("feedback").path("records");
        var first = records.get(0);
        var second = records.get(1);
        records.set(0, second);
        records.set(1, first);
        java.nio.file.Files.writeString(caseFile(current), data.toString());
        assertThrows(IOException.class, () -> NotificationEngine.peek(directory, job, current.investigationId()));
    }

    @Test
    void missingAuthenticatedSourceIsRejectedRatherThanInvented() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        apply(e, r, request(r, FeedbackRequest.Action.ACKNOWLEDGE), 2);
        var current = load(e);
        var data = envelope(current);
        ((ObjectNode) data.path("aggregate").path("feedback").path("records").get(0)).remove("source");
        java.nio.file.Files.writeString(caseFile(current), data.toString());
        assertThrows(IOException.class, () -> NotificationEngine.peek(directory, job, current.investigationId()));
    }

    @Test
    void confirmationCannotChangeItsAuthenticatedActorInsideStoredHistory() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        var current = load(e);
        var data = envelope(current);
        ((ObjectNode) data.path("aggregate")
                        .path("feedback")
                        .path("records")
                        .get(0)
                        .path("confirmation"))
                .put("actorId", "other-user");
        java.nio.file.Files.writeString(caseFile(current), data.toString());
        assertThrows(IOException.class, () -> NotificationEngine.peek(directory, job, current.investigationId()));
    }

    @Test
    void unrelatedActionsRejectSuppliedForeignReferenceFields() {
        UUID nonce = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeedbackRequest(
                        FeedbackRequest.Action.ACKNOWLEDGE,
                        nonce,
                        1,
                        "f".repeat(64),
                        1,
                        "",
                        "",
                        "",
                        "",
                        "",
                        null,
                        List.of(),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeedbackRequest(
                        FeedbackRequest.Action.MUTE, nonce, 1, "", 1, "", "", "", "", "reason", foreign, List.of(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeedbackRequest(
                        FeedbackRequest.Action.CONFIRM_CAUSE,
                        nonce,
                        1,
                        "",
                        1,
                        foreign.toString(),
                        "cause",
                        "basis",
                        "",
                        "",
                        null,
                        List.of(),
                        0));
    }

    @Test
    void rejectedFeedbackReleasesEveryNewDeliveryReservation() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        Path budget = directory.resolve(
                "notification-admission-scope/bci-notification-admission/bci-notifications/cases/" + job + ".json");
        var mapper = new ObjectMapper();
        var before = mapper.readTree(java.nio.file.Files.readString(budget))
                .path("aggregate")
                .path("reservations")
                .deepCopy();
        for (int i = 0; i < 10; i++) {
            var command = request(r, FeedbackRequest.Action.CONFIRM_CAUSE);
            assertThrows(
                    IOException.class,
                    () -> e.feedback(
                            r.investigationId(),
                            command,
                            actor,
                            () -> true,
                            v -> v.equals("AUTHENTICATED_JENKINS_ACTION"),
                            2));
        }
        assertEquals(
                before,
                mapper.readTree(java.nio.file.Files.readString(budget))
                        .path("aggregate")
                        .path("reservations"));
        assertEquals(r, load(e));
    }

    @Test
    void inheritedResolutionMetadataDoesNotProveThatDestinationSawTheResolution() throws Exception {
        var e = engine();
        e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        var recovered = e.ingest(observation(2, "symbol", "SUCCESS", true, true), List.of(destination), 2);
        apply(e, recovered, request(recovered, FeedbackRequest.Action.MUTE), 3);
        var muted = load(e);
        String build = muted.events()
                .get(muted.events().size() - 1)
                .snapshot()
                .path("recovery")
                .path("build")
                .path("runId")
                .asText();
        var resolution = apply(
                e,
                muted,
                copy(request(muted, FeedbackRequest.Action.CONFIRM_RESOLUTION), "", build, null, List.of()),
                4);
        var current = load(e);
        apply(e, current, request(current, FeedbackRequest.Action.UNMUTE), 5);
        current = load(e);
        apply(e, current, request(current, FeedbackRequest.Action.CONFIRM_CAUSE), 6);
        current = load(e);
        var queued = current.destinations().get(0).intents().stream()
                .filter(i -> i.state()
                        == io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State.QUEUED)
                .findFirst()
                .orElseThrow();
        var leased = queued.lease(20000, 30000);
        var sent = leased.accepted(leased.leaseToken(), "synthetic-receipt");
        e.updateDestination(
                current.investigationId(),
                destination,
                state -> {
                    var intents = new ArrayList<>(state.intents());
                    intents.set(intents.indexOf(queued), sent);
                    return new NotificationInvestigationRecord.DestinationState(
                            state.destinationId(),
                            state.generation(),
                            state.policy(),
                            intents,
                            state.submissions(),
                            state.transport());
                },
                "TEST_ACCEPTED",
                20000);
        current = load(e);
        apply(e, current, request(current, FeedbackRequest.Action.MUTE), 20001);
        current = load(e);
        apply(
                e,
                current,
                copy(
                        request(current, FeedbackRequest.Action.REVOKE_CONFIRMATION),
                        "",
                        "",
                        resolution.recordId(),
                        List.of()),
                20002);
        assertTrue(load(e).destinations().get(0).intents().stream()
                .noneMatch(i -> i.state()
                        == io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State.QUEUED));
        assertTrue(load(e).feedback().currentCause().isPresent());
    }

    @Test
    void newEventAfterUnmuteInSameMillisecondPassesTheSubmissionFence() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        apply(e, r, request(r, FeedbackRequest.Action.MUTE), 2);
        var muted = load(e);
        apply(e, muted, request(muted, FeedbackRequest.Action.UNMUTE), 3);
        var unmuted = load(e);
        apply(e, unmuted, request(unmuted, FeedbackRequest.Action.CONFIRM_CAUSE), 3);
        var current = load(e);
        var queued = current.destinations().get(0).intents().stream()
                .filter(i -> i.state()
                        == io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State.QUEUED)
                .findFirst()
                .orElseThrow();
        var leased = queued.lease(20000, 30000);
        e.updateDestination(
                current.investigationId(),
                destination,
                state -> {
                    var intents = new ArrayList<>(state.intents());
                    intents.set(intents.indexOf(queued), leased);
                    return new NotificationInvestigationRecord.DestinationState(
                            state.destinationId(),
                            state.generation(),
                            state.policy(),
                            intents,
                            state.submissions(),
                            state.transport());
                },
                "TEST_LEASE",
                20000);
        assertTrue(e.submissionAllowed(
                current.investigationId(), destination, leased.deliveryId(), leased.leaseToken(), 20001));
    }

    private void acceptPending(NotificationEngine engine, long now) throws Exception {
        var record = load(engine);
        engine.updateDestination(
                record.investigationId(),
                destination,
                state -> {
                    var pending = state.intents().stream()
                            .filter(i -> i.state()
                                    == io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent.State
                                            .QUEUED)
                            .findFirst()
                            .orElseThrow();
                    var lease = pending.lease(now, 30000);
                    var sent = lease.accepted(lease.leaseToken(), "receipt." + now);
                    var intents = new ArrayList<>(state.intents());
                    intents.set(intents.indexOf(pending), sent);
                    return new NotificationInvestigationRecord.DestinationState(
                            state.destinationId(),
                            state.generation(),
                            SuppressionPolicy.accepted(
                                    state.policy(), pending.eventId().toString(), now),
                            intents,
                            state.submissions(),
                            state.transport());
                },
                "TEST_ACCEPTED",
                now);
    }

    @Test
    void correctionTruthPersistsWhenAttemptedIntentHistoryIsFull() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        acceptPending(e, 20000);
        long now = 20000;
        for (int i = 0; i < 14; i++) {
            now += SuppressionPolicy.DAY + 1000;
            var current = load(e);
            apply(
                    e,
                    current,
                    copy(
                            request(current, FeedbackRequest.Action.CORRECT_CONFIRMATION),
                            "",
                            "",
                            current.feedback().currentCause().orElseThrow().recordId(),
                            List.of()),
                    now);
            acceptPending(e, now + 1);
        }
        var full = load(e);
        assertEquals(16, full.destinations().get(0).intents().size());
        now += SuppressionPolicy.DAY + 1000;
        var result = apply(
                e,
                full,
                copy(
                        request(full, FeedbackRequest.Action.CORRECT_CONFIRMATION),
                        "",
                        "",
                        full.feedback().currentCause().orElseThrow().recordId(),
                        List.of()),
                now);
        var changed = load(e);
        assertEquals(
                result.recordId(),
                changed.feedback().currentCause().orElseThrow().recordId());
        assertEquals(
                full.destinations().get(0).intents(),
                changed.destinations().get(0).intents());
        assertTrue(changed.audit().stream()
                .anyMatch(a ->
                        a.code().equals("HUMAN_CORRECTION_DELIVERY_UNAVAILABLE") && a.revision() == result.revision()));
        assertNull(changed.destinations().get(0).policy().pending());
    }

    @Test
    void correctionTruthPersistsAtAdmissionCapacityWithoutReservationOrReplay() throws Exception {
        var e = engine();
        var r = e.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(destination), 1);
        apply(e, r, request(r, FeedbackRequest.Action.CONFIRM_CAUSE), 2);
        acceptPending(e, 20000);
        Path budget = directory.resolve(
                "notification-admission-scope/bci-notification-admission/bci-notifications/cases/" + job + ".json");
        var mapper = new ObjectMapper();
        var data = (ObjectNode) mapper.readTree(java.nio.file.Files.readString(budget));
        var reservations = (ObjectNode) data.path("aggregate").path("reservations");
        for (int i = 0; i < 1000; i++)
            reservations
                    .putObject(UUID.randomUUID().toString())
                    .put("destination", destination.toString())
                    .put("bytes", 0);
        java.nio.file.Files.writeString(budget, data.toString());
        var expected = reservations.deepCopy();
        var current = load(e);
        var command = copy(
                request(current, FeedbackRequest.Action.REVOKE_CONFIRMATION),
                "",
                "",
                current.feedback().currentCause().orElseThrow().recordId(),
                List.of());
        var result = apply(e, current, command, 30000);
        assertEquals("APPLIED", result.code());
        var changed = load(e);
        assertTrue(changed.feedback().currentCause().isEmpty());
        assertTrue(changed.audit().stream().anyMatch(a -> a.code().equals("HUMAN_CORRECTION_DELIVERY_UNAVAILABLE")));
        assertEquals(
                current.destinations().get(0).intents(),
                changed.destinations().get(0).intents());
        assertNull(changed.destinations().get(0).policy().pending());
        assertTrue(apply(e, current, command, 30001).duplicate());
        assertEquals(
                expected,
                mapper.readTree(java.nio.file.Files.readString(budget))
                        .path("aggregate")
                        .path("reservations"));
    }
}

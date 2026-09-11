package io.jenkins.plugins.changeinvestigator.notification;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.CoverageEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.SuppressionPolicy;
import io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationPolicyIntegrationTest {
    @TempDir
    Path directory;

    private final UUID job = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();

    private NotificationEngine engine() throws Exception {
        var engine = new NotificationEngine(directory, job);
        engine.arm(0);
        return engine;
    }

    private NotificationObservation input(int order, String candidate, boolean specific, boolean recovered)
            throws Exception {
        String id = UUID.nameUUIDFromBytes((job + ":" + order).getBytes(StandardCharsets.UTF_8))
                .toString();
        var context = ExecutionContextV1.create(
                job.toString(),
                List.of(new ExecutionContextV1.ScmSource("primary", "demo-repository", "BRANCH", "main", null)),
                ExecutionContextV1.Origin.NORMAL,
                true,
                true);
        var signature = specific
                ? FailureSignatureV1.create(
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
                                "missingSymbol"),
                        FailureSignatureV1.ContextFields.empty(),
                        id)
                : FailureSignatureV1.unresolved(
                        FailureSignatureV1.Category.GENERIC,
                        FailureSignatureV1.ContextFields.empty(),
                        id,
                        "GENERIC_DIAGNOSTIC");
        var facts = new MaterialFacts(
                "",
                List.of(new MaterialFacts.Candidate(
                        "repo", "src/Trade.java", candidate, "STRONG", List.of("SAME_FILE"))),
                List.of(candidate),
                "",
                "",
                "");
        var display = NotificationEngineTest.template();
        display.putObject("boundary")
                .putNull("lastKnownGood")
                .putNull("firstBad")
                .put("firstBadVerified", false)
                .put("proofStatus", "UNKNOWN");
        ((com.fasterxml.jackson.databind.node.ObjectNode) display.get("build"))
                .put("runId", id)
                .put("number", order)
                .put("result", recovered ? "SUCCESS" : "FAILURE");
        return new NotificationObservation(
                id,
                id,
                order,
                recovered ? "SUCCESS" : "FAILURE",
                true,
                true,
                context,
                signature,
                "compile:demo",
                facts,
                recovered
                        ? new CoverageEvidence("compiler-task", 1, "compile:demo", id, context.digest(), true, true)
                        : CoverageEvidence.unknown(),
                List.of(),
                display);
    }

    @Test
    void thirtyMaterialRevisionsCoalesceWithoutEventRetentionFailureOrLostCancellationAudit() throws Exception {
        var engine = engine();
        NotificationInvestigationRecord record = null;
        for (int i = 1; i <= 30; i++) {
            record = engine.ingest(input(i, "commit-" + i, true, false), List.of(destination), i * 1000L);
        }
        assertNotNull(record);
        assertEquals(30, record.semanticSequence());
        assertEquals(1, engine.records().size());
        var state = record.destinations().get(0);
        assertEquals(SuppressionPolicy.Kind.INITIAL, state.policy().pending().kind());
        assertEquals(16_000, SuppressionPolicy.evaluate(state.policy(), 30_000).eligibleAt());
        assertEquals(
                1,
                state.intents().stream()
                        .filter(i -> i.state() == OutboxIntent.State.QUEUED)
                        .count());
        assertTrue(state.intents().size() <= 16);
        assertTrue(record.events().size() <= 3);
        assertTrue(record.audit().stream().anyMatch(a -> a.code().equals("SUPERSEDED_UNATTEMPTED")));
        assertTrue(record.audit().stream().anyMatch(a -> a.code().equals("CANCELLED_INTENT_COMPACTED")));
        var restored = engine().records().get(0);
        assertEquals(record, restored);
    }

    @Test
    void uncertainEpisodesAreIndependentButInitialAdmissionIsBounded() throws Exception {
        var engine = engine();
        for (int i = 1; i <= 5; i++) engine.ingest(input(i, "commit", false, false), List.of(destination), 1000);
        var records = engine.records();
        assertEquals(5, records.size());
        long initialIntents = records.stream()
                .flatMap(r -> r.destinations().stream())
                .flatMap(d -> d.intents().stream())
                .filter(i -> i.state() == OutboxIntent.State.QUEUED)
                .count();
        assertEquals(3, initialIntents);
        assertEquals(
                2,
                records.stream()
                        .filter(r -> r.audit().stream().anyMatch(a -> a.code().equals("INITIAL_ADMISSION_LIMIT")))
                        .count());
    }

    @Test
    void verifiedRecoverySupersedesUnsentFailureWithSmallClosureIntent() throws Exception {
        var engine = engine();
        engine.ingest(input(1, "commit", true, false), List.of(destination), 1000);
        var recovered = engine.ingest(input(2, "commit", true, true), List.of(destination), 2000);
        var state = recovered.destinations().get(0);
        assertEquals(SuppressionPolicy.Kind.RECOVERY, state.policy().pending().kind());
        assertEquals(7000, SuppressionPolicy.evaluate(state.policy(), 2000).eligibleAt());
        assertEquals(
                1,
                state.intents().stream()
                        .filter(i -> i.state() == OutboxIntent.State.QUEUED)
                        .count());
        assertEquals(
                1,
                state.intents().stream()
                        .filter(i -> i.state() == OutboxIntent.State.CANCELLED)
                        .count());
        assertFalse(state.intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.SENT));
    }

    @Test
    void suppressedInitialCannotBypassAdmissionViaLaterMaterialUpdate() throws Exception {
        var engine = engine();
        for (int i = 1; i <= 3; i++) engine.ingest(input(i, "commit", false, false), List.of(destination), 1000);
        var original = input(4, "a", false, false);
        var first = engine.ingest(original, List.of(destination), 2000);
        assertTrue(first.destinations().get(0).intents().isEmpty());
        var revised = new NotificationObservation(
                original.observationId() + "-material2",
                original.runId(),
                original.order(),
                original.result(),
                true,
                true,
                original.context(),
                original.signature(),
                original.affectedCheck(),
                input(4, "b", false, false).facts(),
                original.coverage(),
                List.of(),
                original.display());
        var later = engine.ingest(revised, List.of(destination), 3000);
        assertEquals(first.investigationId(), later.investigationId());
        assertTrue(later.destinations().get(0).intents().isEmpty());
    }

    @Test
    void unresolvedQuotaDoesNotSuppressDistinctSpecificRegressions() throws Exception {
        var engine = engine();
        for (int i = 1; i <= 3; i++) engine.ingest(input(i, "commit", false, false), List.of(destination), 1000);
        for (int i = 4; i <= 8; i++) {
            var record = engine.ingest(distinctSpecific(i), List.of(destination), 2000);
            assertEquals(1, record.destinations().get(0).intents().size());
        }
        var fourth = engine.ingest(input(9, "commit", false, false), List.of(destination), 3000);
        assertTrue(fourth.destinations().get(0).intents().isEmpty());
        assertEquals(9, engine.records().size());
    }

    @Test
    void specificRegressionsDoNotConsumeUnresolvedAdmissionSlots() throws Exception {
        var engine = engine();
        for (int i = 1; i <= 5; i++) {
            var record = engine.ingest(distinctSpecific(i), List.of(destination), 1000);
            assertEquals(1, record.destinations().get(0).intents().size());
        }
        for (int i = 6; i <= 8; i++) {
            var record = engine.ingest(input(i, "commit", false, false), List.of(destination), 2000);
            assertEquals(1, record.destinations().get(0).intents().size());
        }
        var fourth = engine.ingest(input(9, "commit", false, false), List.of(destination), 3000);
        assertTrue(fourth.destinations().get(0).intents().isEmpty());
    }

    private NotificationObservation distinctSpecific(int order) throws Exception {
        var original = input(order, "commit", true, false);
        var fields = new java.util.TreeMap<>(original.signature().identityFields());
        fields.put("discriminant", "missingSymbol" + order);
        var signature = FailureSignatureV1.create(
                FailureSignatureV1.Category.COMPILER,
                fields,
                FailureSignatureV1.ContextFields.empty(),
                original.observationId());
        return new NotificationObservation(
                original.observationId(),
                original.runId(),
                order,
                original.result(),
                true,
                true,
                original.context(),
                signature,
                original.affectedCheck(),
                original.facts(),
                original.coverage(),
                List.of(),
                original.display());
    }

    @Test
    void sameRunFirstBadRefinementKeepsCaseIdentityAndAddsOneMaterialRevision() throws Exception {
        var engine = engine();
        var original = input(2, "commit", true, false);
        var first = engine.ingest(original, List.of(destination), 1000);
        var refinedFacts = new MaterialFacts(
                original.runId(),
                original.facts().topCandidates(),
                original.facts().relevantCommits(),
                "",
                "",
                "");
        var refinedDisplay = original.display();
        var boundary = (com.fasterxml.jackson.databind.node.ObjectNode) refinedDisplay.get("boundary");
        boundary.putObject("lastKnownGood")
                .put("runId", UUID.randomUUID().toString())
                .put("number", 1)
                .put("result", "SUCCESS")
                .putNull("url");
        boundary.set("firstBad", refinedDisplay.get("build"));
        boundary.put("firstBadVerified", true).put("proofStatus", "VERIFIED");
        var refined = new NotificationObservation(
                original.observationId() + "-revision2",
                original.runId(),
                2,
                original.result(),
                true,
                true,
                original.context(),
                original.signature(),
                original.affectedCheck(),
                refinedFacts,
                original.coverage(),
                List.of(),
                refinedDisplay);
        var second = engine.ingest(refined, List.of(destination), 2000);
        assertEquals(first.investigationId(), second.investigationId());
        assertEquals(first.key(), second.key());
        assertEquals(1, second.lifecycle().occurrenceCount());
        assertEquals(2, second.semanticSequence());
        assertEquals(
                "FIRST_BAD_VERIFIED",
                second.events()
                        .get(second.events().size() - 1)
                        .snapshot()
                        .path("materialReasons")
                        .get(0)
                        .asText());
        assertEquals(second, engine().records().get(0));
    }

    @Test
    void sameRunAiProjectionDoesNotCreateAnotherEventOrOccurrence() throws Exception {
        var engine = engine();
        var original = input(1, "commit", true, false);
        var first = engine.ingest(original, List.of(destination), 1000);
        var display = original.display();
        ((com.fasterxml.jackson.databind.node.ObjectNode) display.get("ai")).put("state", "AI_COMPLETE");
        var revised = new NotificationObservation(
                original.observationId() + "-ai2",
                original.runId(),
                1,
                original.result(),
                true,
                true,
                original.context(),
                original.signature(),
                original.affectedCheck(),
                original.facts(),
                original.coverage(),
                List.of(),
                display);
        var next = engine.ingest(revised, List.of(destination), 2000);
        assertEquals(first.investigationId(), next.investigationId());
        assertEquals("AI_COMPLETE", next.aiState());
        assertEquals(1, next.lifecycle().occurrenceCount());
        assertEquals(1, next.semanticSequence());
        assertEquals(first.events(), next.events());
    }

    @Test
    void unverifiedSuccessInsideEpisodeCannotBecomeTheNextFailureBaseline() throws Exception {
        var engine = engine();
        var firstInput = input(1, "commit", true, false);
        var first = engine.ingest(firstInput, List.of(), 1000);
        var success = input(2, "commit", true, true);
        var skipped = new NotificationObservation(
                success.observationId(),
                success.runId(),
                success.order(),
                "SUCCESS",
                true,
                true,
                success.context(),
                success.signature(),
                success.affectedCheck(),
                success.facts(),
                CoverageEvidence.unknown(),
                List.of(),
                success.display());
        var pending = engine.ingest(skipped, List.of(), 2000);
        assertEquals(
                io.jenkins.plugins.changeinvestigator.notification.lifecycle.LifecycleStatus.RECOVERY_PENDING,
                pending.lifecycle().status());
        var current = input(3, "commit", true, false);
        var display = current.display();
        var boundary = (com.fasterxml.jackson.databind.node.ObjectNode) display.get("boundary");
        boundary.set("lastKnownGood", success.display().get("build"));
        boundary.set("firstBad", display.get("build"));
        boundary.put("firstBadVerified", true).put("proofStatus", "VERIFIED");
        var fakeNarrowing = new MaterialFacts(
                current.runId(),
                current.facts().topCandidates(),
                current.facts().relevantCommits(),
                "",
                "",
                "");
        var reappeared = new NotificationObservation(
                current.observationId(),
                current.runId(),
                current.order(),
                "FAILURE",
                true,
                true,
                current.context(),
                current.signature(),
                current.affectedCheck(),
                fakeNarrowing,
                CoverageEvidence.unknown(),
                List.of(),
                display);
        var result = engine.ingest(reappeared, List.of(), 3000);
        assertEquals(first.investigationId(), result.investigationId());
        assertEquals(
                first.lifecycle().facts().verifiedFirstBadRunId(),
                result.lifecycle().facts().verifiedFirstBadRunId());
        assertEquals(1, result.semanticSequence());
        assertEquals(
                first.events().get(0).snapshot().get("boundary"),
                result.events().get(0).snapshot().get("boundary"));
    }
}

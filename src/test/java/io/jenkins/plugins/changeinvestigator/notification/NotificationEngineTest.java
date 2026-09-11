package io.jenkins.plugins.changeinvestigator.notification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.CoverageEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.LifecycleStatus;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationEngineTest {
    @TempDir
    Path directory;

    private final UUID job = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();

    private NotificationEngine engine() throws Exception {
        var engine = new NotificationEngine(directory, job);
        engine.arm(0);
        return engine;
    }

    static ObjectNode template() throws Exception {
        try (var stream = NotificationEngineTest.class.getResourceAsStream("events-v1.json")) {
            return (ObjectNode) new ObjectMapper().readTree(stream).get("specific");
        }
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
    void tenIdenticalFailuresPersistOneCaseOneInitialEventAndIntentAcrossRestart() throws Exception {
        var engine = engine();
        for (int i = 1; i <= 10; i++)
            engine.ingest(observation(i, "isPortolioIM", "FAILURE", false, true), List.of(destination), i * 1000L);
        var records = engine().records();
        assertEquals(1, records.size());
        var record = records.get(0);
        assertEquals(10, record.lifecycle().occurrenceCount());
        assertEquals(1, record.events().size());
        assertEquals(1, record.destinations().get(0).intents().size());
        assertEquals(LifecycleStatus.ACTIVE, record.lifecycle().status());
        for (int i = 1; i <= 10; i++)
            engine().ingest(observation(i, "isPortolioIM", "FAILURE", false, true), List.of(destination), 20000);
        assertEquals(1, engine().records().get(0).events().size());
    }

    @Test
    void skippedSuccessPreservesEpisodeAndVerifiedRecoveryCreatesLinkedRecurrence() throws Exception {
        var engine = engine();
        var first = engine.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        var pending = engine.ingest(observation(2, "symbol", "SUCCESS", false, true), List.of(), 2);
        assertEquals(LifecycleStatus.RECOVERY_PENDING, pending.lifecycle().status());
        var again = engine.ingest(observation(3, "symbol", "FAILURE", false, true), List.of(), 3);
        assertEquals(first.investigationId(), again.investigationId());
        var recovered = engine.ingest(observation(4, "symbol", "SUCCESS", true, true), List.of(), 4);
        assertEquals(LifecycleStatus.RECOVERED, recovered.lifecycle().status());
        assertEquals(
                "RECOVERY_OBSERVED",
                recovered.events().get(1).snapshot().path("eventType").asText());
        var recurrence = engine.ingest(observation(5, "symbol", "FAILURE", false, true), List.of(), 5);
        assertNotEquals(first.investigationId(), recurrence.investigationId());
        assertEquals(
                first.investigationId().toString(),
                recurrence
                        .events()
                        .get(0)
                        .snapshot()
                        .path("relatedInvestigations")
                        .get(0)
                        .path("investigationId")
                        .asText());
        assertEquals(2, engine.records().size());
    }

    @Test
    void olderSuccessCannotRecoverNewerFailure() throws Exception {
        var engine = engine();
        engine.ingest(observation(3, "symbol", "FAILURE", false, true), List.of(), 1);
        engine.ingest(observation(2, "symbol", "SUCCESS", true, true), List.of(), 2);
        assertEquals(LifecycleStatus.ACTIVE, engine.records().get(0).lifecycle().status());
    }

    @Test
    void unknownFailuresNeverGroupAndHistoryGapDoesNotClaimContinuity() throws Exception {
        var engine = engine();
        engine.ingest(observation(1, null, "FAILURE", false, true), List.of(), 1);
        engine.ingest(observation(2, null, "FAILURE", false, true), List.of(), 2);
        engine.ingest(observation(3, "symbol", "FAILURE", false, false), List.of(), 3);
        assertEquals(3, engine.records().size());
        for (var record : engine.records())
            assertEquals(
                    "UNKNOWN",
                    record.events().get(0).snapshot().path("correlationStatus").asText());
    }

    @Test
    void differingSignatureOpensDistinctCase() throws Exception {
        var engine = engine();
        engine.ingest(observation(1, "symbol", "FAILURE", false, true), List.of(), 1);
        engine.ingest(observation(2, "otherSymbol", "FAILURE", false, true), List.of(), 2);
        assertEquals(2, engine.records().size());
    }

    @Test
    void principalFailureReversalStartsOneNewEpisodeThenContinuesIt() throws Exception {
        var engine = engine();
        engine.ingest(observation(1, "symbolA", "FAILURE", false, true), List.of(), 1);
        engine.ingest(observation(2, "symbolB", "FAILURE", false, true), List.of(), 2);
        var returned = engine.ingest(observation(3, "symbolA", "FAILURE", false, true), List.of(), 3);
        var repeated = engine.ingest(observation(4, "symbolA", "FAILURE", false, true), List.of(), 4);
        assertEquals(returned.investigationId(), repeated.investigationId());
        assertEquals(2, repeated.lifecycle().occurrenceCount());
        assertEquals(3, engine.records().size());
    }

    @Test
    void lateEarlierFailureDoesNotCreateAYoungerEpisodeOrRewindCurrent() throws Exception {
        var engine = engine();
        var current = engine.ingest(observation(3, "symbolA", "FAILURE", false, false), List.of(), 1);
        engine.ingest(observation(2, "symbolB", "FAILURE", false, false), List.of(), 2);
        assertEquals(1, engine.records().size());
        assertEquals(current.investigationId(), engine.records().get(0).investigationId());
        assertEquals(3, engine.records().get(0).lifecycle().currentOrder());
    }

    @Test
    void duplicateCallbackInParallelCreatesOneEvent() throws Exception {
        var engine = engine();
        var observation = observation(1, "symbol", "FAILURE", false, true);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 100; i++)
                tasks.add(() -> {
                    engine.ingest(observation, List.of(destination), 1);
                    return null;
                });
            for (var result : pool.invokeAll(tasks)) result.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, engine.records().size());
        assertEquals(1, engine.records().get(0).events().size());
    }

    @Test
    void schemaRejectsInvalidRecoveryAndUnknownFields() throws Exception {
        ObjectNode valid = template();
        assertNotNull(NotificationEvent.freeze(valid));
        valid.put("lifecycleState", "RECOVERED");
        assertThrows(IllegalArgumentException.class, () -> NotificationEvent.freeze(valid));
        valid.put("lifecycleState", "ACTIVE").put("unexpected", "field");
        assertThrows(IllegalArgumentException.class, () -> NotificationEvent.freeze(valid));
    }

    @Test
    void secretsAreRemovedBeforeCasePersistence() throws Exception {
        var engine = engine();
        var observation = observation(1, "symbol", "FAILURE", false, true);
        var display = observation.display();
        ((ObjectNode) display.get("failureSummary"))
                .put("diagnostic", "password=synthetic-secret https://user:pass@example.invalid/?token=secret");
        var secret = new NotificationObservation(
                observation.observationId(),
                observation.runId(),
                observation.order(),
                observation.result(),
                observation.actualEvidence(),
                observation.completeHistory(),
                observation.context(),
                observation.signature(),
                observation.affectedCheck(),
                observation.facts(),
                observation.coverage(),
                List.of(),
                display);
        var record = engine.ingest(secret, List.of(destination), 1);
        String frozen = record.events().get(0).json();
        assertFalse(frozen.contains("synthetic-secret"));
        assertFalse(frozen.contains("user:pass"));
        assertTrue(frozen.contains("REDACTED"));
    }
}

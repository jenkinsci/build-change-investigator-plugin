package io.jenkins.plugins.changeinvestigator.notification.slack;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.NotificationInvestigationRecord;
import io.jenkins.plugins.changeinvestigator.notification.NotificationObservation;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.CoverageEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts;
import io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackOutboxTest {
    @TempDir
    Path directory;

    private final UUID job = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final UUID controller = UUID.randomUUID();
    private final MutableClock clock = new MutableClock();
    private NotificationEngine engine;
    private SlackSubmissionBudget budget;
    private UUID caseId;
    private final List<Attempt> attempts = new ArrayList<>();
    private SlackTransport.Status outcome = SlackTransport.Status.SENT;

    private record Attempt(String token, String thread, String payload) {}

    @BeforeEach
    void setup() throws Exception {
        engine = new NotificationEngine(directory.resolve("job"), job);
        engine.arm(0);
        budget = new SlackSubmissionBudget(directory.resolve("controller"), controller);
        clock.now = 1000;
    }

    private SlackOutbox.Target target() {
        return target(1, "synthetic-token-one");
    }

    private SlackOutbox.Target target(long generation, String token) {
        return new SlackOutbox.Target(
                generation,
                "T123",
                "C123",
                URI.create("https://jenkins.example.invalid/"),
                token,
                true,
                true,
                false,
                null);
    }

    private SlackOutbox outbox() {
        return new SlackOutbox(clock, budget, (token, workspace, channel, payload, thread) -> {
            try {
                var state = state();
                assertTrue(state.intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.LEASED));
                assertFalse(state.submissions().isEmpty());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            attempts.add(new Attempt(token, thread, payload.toString()));
            return outcome == SlackTransport.Status.SENT
                    ? new SlackTransport.Outcome(
                            SlackTransport.Status.SENT,
                            "ACCEPTED",
                            0,
                            new SlackTransport.Receipt(
                                    "T123",
                                    "C123",
                                    "1234." + String.format(java.util.Locale.ROOT, "%06d", attempts.size())))
                    : result(outcome);
        });
    }

    private static SlackTransport.Outcome result(SlackTransport.Status status) {
        return new SlackTransport.Outcome(
                status,
                "SYNTHETIC_OUTCOME",
                status == SlackTransport.Status.RETRYABLE ? 45000 : 0,
                status == SlackTransport.Status.SENT
                        ? new SlackTransport.Receipt("T123", "C123", "1234.000001")
                        : null);
    }

    private NotificationInvestigationRecord.DestinationState state() throws Exception {
        return engine.records().stream()
                .filter(r -> r.investigationId().equals(caseId))
                .findFirst()
                .orElseThrow()
                .destinations()
                .get(0);
    }

    private String dispatch() throws Exception {
        return outbox().dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> true);
    }

    private void ingest(int order, String candidate, boolean recovered) throws Exception {
        var record = engine.ingest(input(job, order, candidate, recovered), List.of(destination), clock.now);
        if (record != null) caseId = record.investigationId();
    }

    private void initial() throws Exception {
        ingest(1, "commit-a", false);
        clock.now += 20000;
    }

    private void material() throws Exception {
        clock.now += 1000000;
        ingest(2, "commit-b", false);
        clock.now += 60001;
    }

    private void recover(int order) throws Exception {
        clock.now += 1000000;
        ingest(order, "commit-b", true);
        clock.now += 6000;
    }

    private NotificationObservation input(UUID jobId, int order, String candidate, boolean recovered) throws Exception {
        String id = UUID.nameUUIDFromBytes((jobId + ":" + order).getBytes(StandardCharsets.UTF_8))
                .toString();
        var context = ExecutionContextV1.create(
                jobId.toString(),
                List.of(new ExecutionContextV1.ScmSource("primary", "demo-repository", "BRANCH", "main", null)),
                ExecutionContextV1.Origin.NORMAL,
                true,
                true);
        var signature = FailureSignatureV1.create(
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
                id);
        var facts = new MaterialFacts(
                "",
                List.of(new MaterialFacts.Candidate(
                        "repo", "src/Trade.java", candidate, "STRONG", List.of("SAME_FILE"))),
                List.of(candidate),
                "",
                "",
                "");
        ObjectNode display;
        try (var stream =
                getClass().getResourceAsStream("/io/jenkins/plugins/changeinvestigator/notification/events-v1.json")) {
            display = (ObjectNode) new ObjectMapper().readTree(stream).get("specific");
        }
        display.putObject("boundary")
                .putNull("lastKnownGood")
                .putNull("firstBad")
                .put("firstBadVerified", false)
                .put("proofStatus", "UNKNOWN");
        ((ObjectNode) display.get("build"))
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
    void approvedDestinationUpperBoundsRemainDispatchable() throws Exception {
        initial();
        var approved = new io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackDestination(
                destination.toString(),
                "Synthetic",
                "Synthetic",
                "T" + "A".repeat(31),
                "C" + "A".repeat(31),
                "synthetic-credential",
                "synthetic",
                true);
        approved.setVerifiedMentionId("U" + "A".repeat(31));
        var target = new SlackOutbox.Target(
                1,
                approved.getWorkspaceId(),
                approved.getChannelId(),
                URI.create("https://jenkins.example.invalid/"),
                "synthetic-token-one",
                true,
                false,
                true,
                approved.getVerifiedMentionId());
        AtomicInteger sent = new AtomicInteger();
        var delivery = new SlackOutbox(clock, budget, (token, workspace, channel, payload, thread) -> {
            assertEquals(approved.getWorkspaceId(), workspace);
            assertEquals(approved.getChannelId(), channel);
            assertTrue(payload.path("text").asText().contains("<@" + approved.getVerifiedMentionId() + ">"));
            sent.incrementAndGet();
            return new SlackTransport.Outcome(
                    SlackTransport.Status.SENT,
                    "ACCEPTED",
                    0,
                    new SlackTransport.Receipt(workspace, channel, "1234.000001"));
        });
        assertEquals("SENT", delivery.dispatch(engine, caseId, destination, () -> Optional.of(target), () -> true));
        assertEquals(1, sent.get());
        assertTrue(state().intents().get(0).receipt().contains(approved.getChannelId()));
    }

    @Test
    void rootMaterialRecoveryAndRepeatedSuccessUseOneThreadWithoutRepeatClosure() throws Exception {
        initial();
        assertEquals("SENT", dispatch());
        material();
        assertEquals("SENT", dispatch());
        recover(3);
        assertEquals("SENT", dispatch());
        clock.now += 10000;
        ingest(4, "commit-b", true);
        dispatch();
        assertEquals(3, attempts.size());
        assertNull(attempts.get(0).thread());
        assertEquals("1234.000001", attempts.get(1).thread());
        assertEquals("1234.000001", attempts.get(2).thread());
        assertTrue(
                attempts.get(2).payload().length() < attempts.get(0).payload().length());
        assertTrue(state().submissions().isEmpty());
    }

    @Test
    void queuedFailureSupersededByRecoveryBecomesOneCurrentClosureRoot() throws Exception {
        ingest(1, "commit-a", false);
        clock.now += 1000;
        ingest(2, "commit-a", true);
        clock.now += 6000;
        assertEquals("SENT", dispatch());
        assertEquals(1, attempts.size());
        assertNull(attempts.get(0).thread());
        assertTrue(attempts.get(0).payload().toLowerCase().contains("recovered"));
        assertTrue(state().intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.CANCELLED));
    }

    @Test
    void unknownAcceptancePersistsAndPreventsRootRetryAndChildPosting() throws Exception {
        initial();
        outcome = SlackTransport.Status.UNKNOWN_OUTCOME;
        assertEquals("UNKNOWN_OUTCOME", dispatch());
        clock.now += 100000;
        assertEquals("UNKNOWN_OUTCOME", dispatch());
        material();
        assertEquals("THREAD_UNAVAILABLE", dispatch());
        assertEquals(1, attempts.size());
        assertTrue(state().intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.UNKNOWN_OUTCOME));
    }

    @Test
    void deletedParentResultStopsReplyAndNeverCreatesReplacementRoot() throws Exception {
        initial();
        dispatch();
        material();
        outcome = SlackTransport.Status.THREAD_UNAVAILABLE;
        assertEquals("THREAD_UNAVAILABLE", dispatch());
        assertEquals("THREAD_UNAVAILABLE", dispatch());
        assertEquals(2, attempts.size());
        assertEquals("1234.000001", attempts.get(1).thread());
    }

    @Test
    void expiredLeaseAfterReloadIsUnknownEvenWhenControllerIsPaused() throws Exception {
        initial();
        engine.updateDestination(
                caseId,
                destination,
                state -> new NotificationInvestigationRecord.DestinationState(
                        state.destinationId(),
                        state.generation(),
                        state.policy(),
                        state.intents().stream()
                                .map(i -> i.lease(clock.now, 1000))
                                .toList(),
                        state.submissions()),
                "LEASE_TEST",
                clock.now);
        clock.now += 2000;
        engine = new NotificationEngine(directory.resolve("job"), job);
        assertEquals(
                "CONTROLLER_PAUSED",
                outbox().dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> false));
        assertEquals(
                OutboxIntent.State.UNKNOWN_OUTCOME, state().intents().get(0).state());
        assertEquals("UNKNOWN_OUTCOME", dispatch());
        assertTrue(attempts.isEmpty());
    }

    @Test
    void sentRootSurvivesReloadAndMaterialUsesSavedReceipt() throws Exception {
        initial();
        dispatch();
        engine = new NotificationEngine(directory.resolve("job"), job);
        budget = new SlackSubmissionBudget(directory.resolve("controller"), controller);
        assertEquals("SENT", dispatch());
        assertEquals(1, attempts.size());
        material();
        assertEquals("SENT", dispatch());
        assertEquals("1234.000001", attempts.get(1).thread());
    }

    @Test
    void revokedDestinationCancelsWithoutAnyNetworkAttempt() throws Exception {
        initial();
        assertEquals(
                "DESTINATION_REVOKED", outbox().dispatch(engine, caseId, destination, Optional::empty, () -> true));
        assertTrue(attempts.isEmpty());
        assertTrue(state().intents().stream().allMatch(i -> i.state() == OutboxIntent.State.CANCELLED));
    }

    @Test
    void authorizationIsRecheckedAfterDurableLease() throws Exception {
        initial();
        AtomicInteger checks = new AtomicInteger();
        assertEquals(
                "REVOKED_BEFORE_SUBMISSION",
                outbox().dispatch(
                                engine,
                                caseId,
                                destination,
                                () -> checks.incrementAndGet() == 1 ? Optional.of(target()) : Optional.empty(),
                                () -> true));
        assertEquals(2, checks.get());
        assertTrue(attempts.isEmpty());
        assertEquals("REVOKED_BEFORE_SUBMISSION", state().intents().get(0).safeCode());
    }

    @Test
    void semanticGenerationChangeCancelsInsteadOfRerouting() throws Exception {
        initial();
        assertEquals(
                "DESTINATION_CHANGED",
                outbox().dispatch(
                                engine,
                                caseId,
                                destination,
                                () -> Optional.of(target(2, "synthetic-token-two")),
                                () -> true));
        assertTrue(attempts.isEmpty());
        assertEquals(OutboxIntent.State.CANCELLED, state().intents().get(0).state());
    }

    @Test
    void conclusiveRetryRetainsFrozenPayloadAndUsesRotatedCredential() throws Exception {
        initial();
        outcome = SlackTransport.Status.RETRYABLE;
        assertEquals("RETRYABLE", dispatch());
        var first = state().intents().get(0);
        assertEquals(OutboxIntent.State.RETRY_WAIT, first.state());
        assertTrue(first.nextAttemptAt() >= clock.now + 45000);
        assertEquals(first.deliveryId(), state().submissions().get(0).deliveryId());
        clock.now = first.nextAttemptAt() + 1;
        outcome = SlackTransport.Status.SENT;
        assertEquals(
                "SENT",
                outbox().dispatch(
                                engine,
                                caseId,
                                destination,
                                () -> Optional.of(target(1, "synthetic-token-two")),
                                () -> true));
        assertEquals(2, attempts.size());
        assertEquals(attempts.get(0).payload(), attempts.get(1).payload());
        assertEquals("synthetic-token-two", attempts.get(1).token());
        assertNull(attempts.get(1).thread());
        assertEquals(first.deliveryId(), state().intents().get(0).deliveryId());
        assertEquals(2, state().intents().get(0).attempts());
    }

    @Test
    void exceptionAfterLeaseIsUncertainAndCannotBeRetried() throws Exception {
        initial();
        var failed = new SlackOutbox(clock, budget, (token, workspace, channel, payload, thread) -> {
            throw new IllegalStateException("Synthetic accepted response lost");
        });
        assertEquals(
                "UNKNOWN_OUTCOME",
                failed.dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> true));
        assertEquals("UNKNOWN_OUTCOME", dispatch());
        assertTrue(attempts.isEmpty());
    }

    @Test
    void permanentFailureStopsRepeatedDispatchAttempts() throws Exception {
        initial();
        outcome = SlackTransport.Status.PERMANENT_FAILURE;
        assertEquals("PERMANENT_FAILURE", dispatch());
        clock.now += 1000000;
        dispatch();
        assertEquals(1, attempts.size());
        assertEquals(
                OutboxIntent.State.FAILED_PERMANENT, state().intents().get(0).state());
    }

    @Test
    void oneHundredIndependentJobsEachDispatchOnceAcrossBoundedRateWindows() throws Exception {
        List<NotificationEngine> engines = new ArrayList<>();
        List<UUID> cases = new ArrayList<>();
        for (int n = 0; n < 100; n++) {
            UUID jobId = UUID.randomUUID();
            var item = new NotificationEngine(directory.resolve("jobs").resolve("job-" + n), jobId);
            item.arm(0);
            cases.add(item.ingest(input(jobId, 1, "commit-a", false), List.of(destination), clock.now)
                    .investigationId());
            engines.add(item);
        }
        clock.now += 20000;
        var identities = new HashSet<UUID>();
        AtomicInteger sent = new AtomicInteger();
        for (int round = 0; round < 4; round++) {
            for (int n = 0; n < 100; n++) {
                final NotificationEngine current = engines.get(n);
                final UUID currentCase = cases.get(n);
                var dispatcher = new SlackOutbox(clock, budget, (token, workspace, channel, payload, thread) -> {
                    try {
                        var intent = current.records()
                                .get(0)
                                .destinations()
                                .get(0)
                                .intents()
                                .get(0);
                        assertTrue(identities.add(intent.deliveryId()));
                        assertEquals(OutboxIntent.State.LEASED, intent.state());
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    sent.incrementAndGet();
                    return result(SlackTransport.Status.SENT);
                });
                dispatcher.dispatch(current, currentCase, destination, () -> Optional.of(target()), () -> true);
                clock.now += 1000;
            }
            clock.now += 60000;
        }
        assertEquals(100, sent.get());
        assertEquals(100, identities.size());
        for (var current : engines)
            assertEquals(
                    OutboxIntent.State.SENT,
                    current.records()
                            .get(0)
                            .destinations()
                            .get(0)
                            .intents()
                            .get(0)
                            .state());
    }

    @Test
    void uncertainThreadReplyStopsLaterRecoveryReplies() throws Exception {
        initial();
        dispatch();
        material();
        outcome = SlackTransport.Status.UNKNOWN_OUTCOME;
        assertEquals("UNKNOWN_OUTCOME", dispatch());
        recover(3);
        outcome = SlackTransport.Status.SENT;
        assertEquals("THREAD_UNAVAILABLE", dispatch());
        assertEquals(2, attempts.size());
    }

    @Test
    void invalidReceiptNeverReportsSuccessfulDelivery() throws Exception {
        initial();
        var invalid = new SlackOutbox(
                clock,
                budget,
                (token, workspace, channel, payload, thread) -> new SlackTransport.Outcome(
                        SlackTransport.Status.SENT,
                        "ACCEPTED",
                        0,
                        new SlackTransport.Receipt("T999", "C123", "1234.000001")));
        assertEquals(
                "UNKNOWN_OUTCOME",
                invalid.dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> true));
        assertEquals(
                OutboxIntent.State.UNKNOWN_OUTCOME, state().intents().get(0).state());
        assertFalse(
                engine.records().get(0).audit().stream().anyMatch(a -> a.code().equals("DELIVERY_SENT")));
    }

    @Test
    void duplicateDispatchCallbacksCannotSendWhileNetworkLeaseIsActive() throws Exception {
        initial();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var sending = new SlackOutbox(clock, budget, (token, workspace, channel, payload, thread) -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(30, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return result(SlackTransport.Status.SENT);
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var first = pool.submit(
                    () -> sending.dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> true));
            assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
            var duplicates = new ArrayList<java.util.concurrent.Callable<String>>();
            for (int n = 0; n < 100; n++)
                duplicates.add(
                        () -> sending.dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> true));
            for (var result : pool.invokeAll(duplicates)) result.get();
            release.countDown();
            assertEquals("SENT", first.get());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void optedInAiCompletionPostsOneThreadUpdateWithoutNewFailureOrRepeat() throws Exception {
        var policies = List.of(new NotificationEngine.DestinationPolicy(destination, 1, true, true));
        var original = input(job, 1, "commit-a", false);
        caseId = engine.ingestConfigured(original, policies, clock.now).investigationId();
        clock.now += 20000;
        assertEquals("SENT", dispatch());
        clock.now += 1000000;
        var display = original.display();
        ((ObjectNode) display.get("ai"))
                .put("state", "AI_COMPLETE")
                .put("summary", "Check the changed source declaration.")
                .put("suggestedCheck", "Compare the declaration and failing reference.");
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
        engine.ingestConfigured(revised, policies, clock.now);
        clock.now += 60001;
        assertEquals("SENT", dispatch());
        engine.ingestConfigured(revised, policies, clock.now);
        dispatch();
        assertEquals(2, attempts.size());
        assertEquals("1234.000001", attempts.get(1).thread());
        assertTrue(attempts.get(1).payload().contains("Check the changed source declaration."));
        assertEquals(1, engine.records().get(0).lifecycle().occurrenceCount());
    }

    @Test
    void aiCompletionRemainsSilentWithoutOptIn() throws Exception {
        initial();
        dispatch();
        var original = input(job, 1, "commit-a", false);
        var display = original.display();
        ((ObjectNode) display.get("ai"))
                .put("state", "AI_COMPLETE")
                .put("summary", "Check the changed source declaration.");
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
        engine.ingest(revised, List.of(destination), clock.now);
        clock.now += 1000000;
        dispatch();
        assertEquals(1, attempts.size());
        assertEquals(1, engine.records().get(0).semanticSequence());
    }

    private static final class MutableClock extends Clock {
        long now;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }
    }

    private SlackOutbox.Target policyTarget(boolean recovery, boolean ai, boolean mentions) {
        return new SlackOutbox.Target(
                1,
                "T123",
                "C123",
                URI.create("https://jenkins.example.invalid/"),
                "synthetic-token-one",
                recovery,
                ai,
                mentions,
                "U123");
    }

    @Test
    void recoveryPolicyRevokedAfterLeaseStopsClosureBeforeSubmission() throws Exception {
        initial();
        assertEquals("SENT", dispatch());
        recover(2);
        var checks = new AtomicInteger();
        assertEquals(
                "REVOKED_BEFORE_SUBMISSION",
                outbox().dispatch(
                                engine,
                                caseId,
                                destination,
                                () -> Optional.of(policyTarget(checks.incrementAndGet() == 1, true, false)),
                                () -> true));
        assertEquals(2, checks.get());
        assertEquals(1, attempts.size());
        assertTrue(state().intents().stream().anyMatch(i -> "REVOKED_BEFORE_SUBMISSION".equals(i.safeCode())));
    }

    @Test
    void mentionPolicyRevokedAfterLeaseCannotSendFrozenMention() throws Exception {
        initial();
        var checks = new AtomicInteger();
        assertEquals(
                "REVOKED_BEFORE_SUBMISSION",
                outbox().dispatch(
                                engine,
                                caseId,
                                destination,
                                () -> Optional.of(policyTarget(true, true, checks.incrementAndGet() == 1)),
                                () -> true));
        assertEquals(2, checks.get());
        assertTrue(attempts.isEmpty());
        assertTrue(state().submissions().get(0).payload().contains("<@U123>"));
        assertEquals("REVOKED_BEFORE_SUBMISSION", state().intents().get(0).safeCode());
    }

    @Test
    void aiPolicyRevokedAfterLeaseStopsInterpretationBeforeSubmission() throws Exception {
        var policies = List.of(new NotificationEngine.DestinationPolicy(destination, 1, true, true));
        var original = input(job, 1, "commit-a", false);
        caseId = engine.ingestConfigured(original, policies, clock.now).investigationId();
        clock.now += 20000;
        assertEquals("SENT", dispatch());
        clock.now += 1000000;
        var display = original.display();
        ((ObjectNode) display.get("ai"))
                .put("state", "AI_COMPLETE")
                .put("summary", "Inspect the changed declaration before modifying the call.");
        var revised = new NotificationObservation(
                original.observationId() + "-late-ai",
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
        engine.ingestConfigured(revised, policies, clock.now);
        clock.now += 60001;
        var checks = new AtomicInteger();
        assertEquals(
                "REVOKED_BEFORE_SUBMISSION",
                outbox().dispatch(
                                engine,
                                caseId,
                                destination,
                                () -> Optional.of(policyTarget(true, checks.incrementAndGet() == 1, false)),
                                () -> true));
        assertEquals(2, checks.get());
        assertEquals(1, attempts.size());
    }

    @Test
    void materialSupersedingUnsentNewStillRendersCompleteFieldBriefRoot() throws Exception {
        ingest(1, "commit-a", false);
        clock.now += 1000;
        ingest(2, "commit-b", false);
        clock.now += 60001;
        assertEquals("SENT", dispatch());
        assertEquals(1, attempts.size());
        assertNull(attempts.get(0).thread());
        assertTrue(attempts.get(0).payload().contains("Observed failure"));
        assertTrue(attempts.get(0).payload().contains("Check first"));
        assertTrue(attempts.get(0).payload().contains("New investigation"));
    }

    @Test
    void aiSupersedingUnsentNewStillRendersCompleteFieldBriefRoot() throws Exception {
        var policies = List.of(new NotificationEngine.DestinationPolicy(destination, 1, true, true));
        var original = input(job, 1, "commit-a", false);
        caseId = engine.ingestConfigured(original, policies, clock.now).investigationId();
        clock.now += 1000;
        var display = original.display();
        ((ObjectNode) display.get("ai"))
                .put("state", "AI_COMPLETE")
                .put("summary", "Inspect the changed declaration before modifying the call.");
        var revised = new NotificationObservation(
                original.observationId() + "-early-ai",
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
        engine.ingestConfigured(revised, policies, clock.now);
        clock.now += 60001;
        assertEquals("SENT", dispatch());
        assertEquals(1, attempts.size());
        assertNull(attempts.get(0).thread());
        assertTrue(attempts.get(0).payload().contains("Observed failure"));
        assertTrue(attempts.get(0).payload().contains("Check first"));
        assertTrue(attempts.get(0).payload().contains("Inspect the changed declaration"));
    }

    @Test
    void recoveryChildWaitsForInFlightRootReceipt() throws Exception {
        initial();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var sending = new SlackOutbox(clock, budget, (token, workspace, channel, payload, thread) -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(30, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
            return result(SlackTransport.Status.SENT);
        });
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var root = pool.submit(
                    () -> sending.dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> true));
            assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
            clock.now += 1000;
            ingest(2, "commit-b", true);
            clock.now += 6000;
            assertEquals("PARENT_PENDING", dispatch());
            assertTrue(attempts.isEmpty());
            assertEquals(1, calls.get());
            release.countDown();
            assertEquals("SENT", root.get());
            assertEquals("SENT", dispatch());
            assertEquals(1, attempts.size());
            assertEquals("1234.000001", attempts.get(0).thread());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}

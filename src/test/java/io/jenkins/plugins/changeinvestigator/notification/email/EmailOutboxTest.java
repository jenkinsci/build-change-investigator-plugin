package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.*;
import io.jenkins.plugins.changeinvestigator.notification.persistence.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EmailOutboxTest {
    @TempDir
    Path directory;

    final UUID job = UUID.randomUUID(), destination = UUID.randomUUID(), controller = UUID.randomUUID();
    final MutableClock clock = new MutableClock();
    NotificationEngine engine;
    EmailSubmissionBudget budget;
    UUID caseId;
    final List<String> sent = new ArrayList<>();
    EmailTransport.Status outcome = EmailTransport.Status.SENT;

    @BeforeEach
    void setup() throws Exception {
        engine = new NotificationEngine(directory.resolve("job"), job);
        engine.arm(0);
        budget = new EmailSubmissionBudget(directory.resolve("controller"), controller);
        clock.now = 1000;
    }

    EmailOutbox.Target target() {
        return new EmailOutbox.Target(
                1,
                "bci@example.invalid",
                "triage@example.invalid",
                URI.create("https://jenkins.example.invalid/"),
                new EmailTransport.Settings("127.0.0.1", 2525, EmailTransport.TlsMode.PLAIN_INTERNAL, true, null, null),
                true,
                true);
    }

    EmailOutbox outbox() {
        return new EmailOutbox(clock, budget, (settings, recipient, mime) -> {
            try {
                assertTrue(state().intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.LEASED));
                assertTrue(
                        state().submissions().stream().anyMatch(s -> !s.chunks().isEmpty()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            sent.add(mime);
            return new EmailTransport.Outcome(outcome, "SYNTHETIC");
        });
    }

    NotificationInvestigationRecord.DestinationState state() throws Exception {
        return engine.records().stream()
                .filter(r -> r.investigationId().equals(caseId))
                .findFirst()
                .orElseThrow()
                .destinations()
                .stream()
                .filter(d -> d.destinationId().equals(destination))
                .findFirst()
                .orElseThrow();
    }

    void ingest(int order, String candidate, boolean recovered) throws Exception {
        var r = engine.ingestConfigured(
                EmailFixtures.input(job, order, candidate, recovered),
                List.of(new NotificationEngine.DestinationPolicy(destination, 1, true, true, "EMAIL")),
                clock.now);
        if (r != null) caseId = r.investigationId();
    }

    void initial() throws Exception {
        ingest(1, "commit-a", false);
        clock.now += 20000;
    }

    void material() throws Exception {
        clock.now += 1000000;
        ingest(2, "commit-b", false);
        clock.now += 60001;
    }

    void recovery() throws Exception {
        clock.now += 1000000;
        ingest(3, "commit-b", true);
        clock.now += 6000;
    }

    String dispatch() throws Exception {
        return outbox().dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> true);
    }

    jakarta.mail.internet.MimeMessage mime(int index) throws Exception {
        return new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(Base64.getDecoder().decode(sent.get(index))));
    }

    @Test
    void rootMaterialRecoveryRetainThreadAndSuppressRepeatedSuccess() throws Exception {
        initial();
        assertEquals("SENT", dispatch());
        material();
        assertEquals("SENT", dispatch());
        recovery();
        assertEquals("SENT", dispatch());
        clock.now += 10000;
        ingest(4, "commit-b", true);
        dispatch();
        assertEquals(3, sent.size());
        String root = mime(0).getMessageID();
        assertNull(mime(0).getHeader("In-Reply-To"));
        assertEquals(root, mime(1).getHeader("In-Reply-To", null));
        assertEquals(root, mime(2).getHeader("In-Reply-To", null));
        assertEquals("Re: " + mime(0).getSubject(), mime(2).getSubject());
        assertTrue(state().submissions().stream().allMatch(s -> s.chunks().isEmpty()));
        assertEquals("EMAIL", state().transport());
    }

    @Test
    void retryFreezesExactMimeAcrossReloadAndDoesNotRegenerateMessageId() throws Exception {
        initial();
        outcome = EmailTransport.Status.RETRYABLE;
        assertEquals("RETRYABLE", dispatch());
        String frozen = sent.get(0);
        long next = state().intents().get(0).nextAttemptAt();
        engine = new NotificationEngine(directory.resolve("job"), job);
        assertEquals(next, state().intents().get(0).nextAttemptAt());
        clock.now = next + 1000;
        outcome = EmailTransport.Status.SENT;
        assertEquals("SENT", dispatch());
        assertEquals(frozen, sent.get(1));
        assertEquals(mime(0).getMessageID(), mime(1).getMessageID());
    }

    @Test
    void uncertainRootQuarantinesChildrenAndNeverResends() throws Exception {
        initial();
        outcome = EmailTransport.Status.UNKNOWN_OUTCOME;
        assertEquals("UNKNOWN_OUTCOME", dispatch());
        engine = new NotificationEngine(directory.resolve("job"), job);
        clock.now += 100000;
        dispatch();
        material();
        assertEquals("THREAD_UNAVAILABLE", dispatch());
        assertEquals(1, sent.size());
        assertEquals(
                OutboxIntent.State.UNKNOWN_OUTCOME, state().intents().get(0).state());
    }

    @Test
    void uncertainReplyAlsoBlocksLaterRecovery() throws Exception {
        initial();
        dispatch();
        material();
        outcome = EmailTransport.Status.UNKNOWN_OUTCOME;
        dispatch();
        recovery();
        assertEquals("THREAD_UNAVAILABLE", dispatch());
        assertEquals(2, sent.size());
    }

    @Test
    void expiredLeaseBecomesUnknownWhileControllerPaused() throws Exception {
        initial();
        engine.updateDestination(
                caseId,
                destination,
                d -> new NotificationInvestigationRecord.DestinationState(
                        d.destinationId(),
                        d.generation(),
                        d.policy(),
                        List.of(d.intents().get(0).lease(clock.now, 1000)),
                        d.submissions(),
                        d.transport()),
                "TEST_LEASE",
                clock.now);
        clock.now += 2000;
        assertEquals(
                "CONTROLLER_PAUSED",
                outbox().dispatch(engine, caseId, destination, () -> Optional.of(target()), () -> false));
        assertEquals(
                OutboxIntent.State.UNKNOWN_OUTCOME, state().intents().get(0).state());
        assertTrue(sent.isEmpty());
    }

    @Test
    void recoveryBeforeInitialBecomesSingleCurrentClosureRoot() throws Exception {
        initial();
        recovery();
        assertEquals("SENT", dispatch());
        assertEquals(1, sent.size());
        assertNull(mime(0).getHeader("In-Reply-To"));
        assertTrue(mime(0).getContentType().startsWith("multipart/alternative"));
        assertTrue(((jakarta.mail.Multipart) mime(0).getContent())
                .getBodyPart(0)
                .getContent()
                .toString()
                .contains("First bad not verified"));
        assertTrue(state().intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.CANCELLED));
    }

    @Test
    void revocationBeforeLeaseCancelsWithoutNetwork() throws Exception {
        initial();
        assertEquals(
                "DESTINATION_REVOKED", outbox().dispatch(engine, caseId, destination, Optional::empty, () -> true));
        assertTrue(sent.isEmpty());
        assertEquals(OutboxIntent.State.CANCELLED, state().intents().get(0).state());
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
        assertTrue(sent.isEmpty());
    }

    @Test
    void permanentFailureDoesNotRetry() throws Exception {
        initial();
        outcome = EmailTransport.Status.PERMANENT_FAILURE;
        dispatch();
        clock.now += 1000000;
        dispatch();
        assertEquals(1, sent.size());
        assertEquals(
                OutboxIntent.State.FAILED_PERMANENT, state().intents().get(0).state());
    }

    @Test
    void acceptedRootMetadataSurvivesRestartWithoutBodyRetentionOrResend() throws Exception {
        initial();
        dispatch();
        String id = mime(0).getMessageID();
        engine = new NotificationEngine(directory.resolve("job"), job);
        dispatch();
        assertEquals(1, sent.size());
        material();
        dispatch();
        assertEquals(id, mime(1).getHeader("In-Reply-To", null));
        assertTrue(state().submissions().stream().allMatch(s -> s.chunks().isEmpty()));
    }

    @Test
    void independentRecipientAcceptanceNeverResendsAcceptedRecipient() throws Exception {
        UUID other = UUID.randomUUID();
        var r = engine.ingestConfigured(
                EmailFixtures.input(job, 1, "commit-a", false),
                List.of(
                        new NotificationEngine.DestinationPolicy(destination, 1, true, false, "EMAIL"),
                        new NotificationEngine.DestinationPolicy(other, 1, true, false, "EMAIL")),
                clock.now);
        caseId = r.investigationId();
        clock.now += 20000;
        assertEquals("SENT", dispatch());
        AtomicInteger rejected = new AtomicInteger();
        var failing = new EmailOutbox(clock, budget, (s, recipient, m) -> {
            rejected.incrementAndGet();
            return new EmailTransport.Outcome(EmailTransport.Status.PERMANENT_FAILURE, "REJECTED");
        });
        var b = new EmailOutbox.Target(
                1,
                "bci@example.invalid",
                "other@example.invalid",
                target().jenkinsRoot(),
                target().settings(),
                true,
                false);
        failing.dispatch(engine, caseId, other, () -> Optional.of(b), () -> true);
        clock.now += 100000;
        dispatch();
        failing.dispatch(engine, caseId, other, () -> Optional.of(b), () -> true);
        assertEquals(1, sent.size());
        assertEquals(1, rejected.get());
        assertFalse(new String(Base64.getDecoder().decode(sent.get(0)), java.nio.charset.StandardCharsets.UTF_8)
                .contains("other@example.invalid"));
    }

    @Test
    void historicalTransportAndChunksDefaultSafelyAndWrongDispatcherDoesNothing() throws Exception {
        initial();
        var json = new ObjectMapper().valueToTree(state());
        ((ObjectNode) json).remove("transport");
        var old = new ObjectMapper().treeToValue(json, NotificationInvestigationRecord.DestinationState.class);
        assertEquals("SLACK", old.transport());
        var slack = new io.jenkins.plugins.changeinvestigator.notification.slack.SlackOutbox(
                clock,
                new io.jenkins.plugins.changeinvestigator.notification.slack.SlackSubmissionBudget(
                        directory, controller),
                (a, b, c, d, e) -> {
                    fail("Wrong transport called");
                    return null;
                });
        assertEquals("WRONG_TRANSPORT", slack.dispatch(engine, caseId, destination, Optional::empty, () -> true));
        assertEquals(OutboxIntent.State.QUEUED, state().intents().get(0).state());
        var legacy = new ObjectMapper()
                .readValue(
                        "{\"deliveryId\":\"" + UUID.randomUUID()
                                + "\",\"payload\":\"{}\",\"routing\":\"ROOT\",\"reservedAt\":0}",
                        DeliverySnapshot.class);
        assertTrue(legacy.chunks().isEmpty());
    }

    @Test
    void optedInAiUsesOneReplyAndNoRepeatedCompletion() throws Exception {
        initial();
        dispatch();
        clock.now += 1000000;
        var original = EmailFixtures.input(job, 1, "commit-a", false);
        var display = original.display();
        ((ObjectNode) display.get("ai"))
                .put("state", "AI_COMPLETE")
                .put("summary", "Check the changed source declaration.")
                .put("suggestedCheck", "Compare declaration and reference.");
        var revised = new NotificationObservation(
                original.observationId() + "-ai",
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
        var policies = List.of(new NotificationEngine.DestinationPolicy(destination, 1, true, true, "EMAIL"));
        engine.ingestConfigured(revised, policies, clock.now);
        clock.now += 60001;
        assertEquals("SENT", dispatch());
        engine.ingestConfigured(revised, policies, clock.now);
        dispatch();
        assertEquals(2, sent.size());
        assertEquals(mime(0).getMessageID(), mime(1).getHeader("In-Reply-To", null));
        var parts = (jakarta.mail.Multipart) mime(1).getContent();
        assertTrue(parts.getBodyPart(0).getContent().toString().contains("Check the changed source declaration."));
    }

    @Test
    void safeRetryCannotRerouteFrozenMimeUnderSameGeneration() throws Exception {
        initial();
        outcome = EmailTransport.Status.RETRYABLE;
        dispatch();
        clock.now = state().intents().get(0).nextAttemptAt() + 1000;
        var changed = new EmailOutbox.Target(
                1, target().sender(), "other@example.invalid", target().jenkinsRoot(), target().settings(), true, true);
        assertEquals(
                "DESTINATION_CHANGED",
                outbox().dispatch(engine, caseId, destination, () -> Optional.of(changed), () -> true));
        assertEquals(1, sent.size());
    }

    @Test
    void escapedSmtpPasswordIsRejectedBeforeMimePersistenceOrNetwork() throws Exception {
        String password = "synthetic<secret>&\"'";
        var original = EmailFixtures.input(job, 1, "commit-a", false);
        var display = original.display();
        ((ObjectNode) display.get("ai"))
                .put("state", "AI_COMPLETE")
                .put("summary", "Inspect " + password)
                .put("suggestedCheck", "Compare declaration and reference.");
        var observation = new NotificationObservation(
                original.observationId(),
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
        caseId = engine.ingestConfigured(
                        observation,
                        List.of(new NotificationEngine.DestinationPolicy(destination, 1, true, true, "EMAIL")),
                        clock.now)
                .investigationId();
        clock.now += 20000;
        var settings = new EmailTransport.Settings(
                "smtp.example.invalid",
                587,
                EmailTransport.TlsMode.STARTTLS_REQUIRED,
                false,
                "synthetic-user",
                password);
        var approved = new EmailOutbox.Target(
                1, target().sender(), target().recipient(), target().jenkinsRoot(), settings, true, true);
        assertEquals(
                "RENDER_REJECTED",
                outbox().dispatch(engine, caseId, destination, () -> Optional.of(approved), () -> true));
        assertTrue(sent.isEmpty());
        assertTrue(state().submissions().isEmpty());
        assertEquals(
                OutboxIntent.State.FAILED_PERMANENT, state().intents().get(0).state());
    }

    static final class MutableClock extends Clock {
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

    private io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackResult review(
            io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action action,
            UUID confirmation)
            throws Exception {
        var record = engine.records().stream()
                .filter(r -> r.investigationId().equals(caseId))
                .findFirst()
                .orElseThrow();
        var latest = record.events().get(record.events().size() - 1).snapshot();
        var command = new io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest(
                action,
                UUID.randomUUID(),
                record.caseRevision(),
                "",
                record.evidenceRevision(),
                action
                                == io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action
                                        .CONFIRM_RESOLUTION
                        ? latest.path("recovery").path("build").path("runId").asText()
                        : "",
                "Corrected the source reference.",
                "The affected compilation step passed and the change was reviewed.",
                "",
                "Private audit reason",
                confirmation,
                List.of(),
                0);
        return engine.feedback(
                caseId,
                command,
                new io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackActor(
                        "synthetic-reviewer", "Synthetic reviewer"),
                () -> true,
                text -> false,
                clock.now);
    }

    @Test
    void confirmationBeforeRecoveryAttemptCoalescesOneClosure() throws Exception {
        initial();
        assertEquals("SENT", dispatch());
        clock.now += 1000000;
        ingest(2, "commit-b", true);
        review(
                io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action.CONFIRM_RESOLUTION,
                null);
        clock.now += 6000;
        assertEquals("SENT", dispatch());
        assertEquals(2, sent.size());
        dispatch();
        assertEquals(2, sent.size());
        assertTrue(state().policy().recoveryAccepted());
        assertTrue(state().policy().confirmationAccepted());
    }

    @Test
    void deliveredConfirmationRevocationUsesOneCriticalCorrectionEvenMuted() throws Exception {
        initial();
        assertEquals("SENT", dispatch());
        clock.now += 1000000;
        ingest(2, "commit-b", true);
        clock.now += 6000;
        assertEquals("SENT", dispatch());
        var confirmed = review(
                io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action.CONFIRM_RESOLUTION,
                null);
        clock.now += 6000;
        assertEquals("SENT", dispatch());
        assertEquals(3, sent.size());
        review(io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action.MUTE, null);
        review(
                io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action.REVOKE_CONFIRMATION,
                confirmed.recordId());
        clock.now += 6000;
        assertEquals("SENT", dispatch());
        assertEquals(4, sent.size());
        dispatch();
        assertEquals(4, sent.size());
        assertTrue(state().policy().lastCorrectionAt() > 0);
    }

    @Test
    void muteAfterLeaseFencesSubmissionAndUnmuteDoesNotReplay() throws Exception {
        initial();
        AtomicInteger authorizations = new AtomicInteger();
        String result = outbox().dispatch(
                        engine,
                        caseId,
                        destination,
                        () -> {
                            if (authorizations.incrementAndGet() == 2) {
                                try {
                                    review(
                                            io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest
                                                    .Action.MUTE,
                                            null);
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                            return Optional.of(target());
                        },
                        () -> true);
        assertEquals("REVOKED_BEFORE_SUBMISSION", result);
        assertEquals(OutboxIntent.State.CANCELLED, state().intents().get(0).state());
        assertEquals(1, state().intents().get(0).attempts());
        assertEquals(0, sent.size());
        review(io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action.UNMUTE, null);
        clock.now += 1000000;
        dispatch();
        assertEquals(0, sent.size());
        material();
        assertEquals("SENT", dispatch());
        assertTrue(state().intents().stream()
                .filter(i -> i.state() == OutboxIntent.State.SENT)
                .allMatch(i -> i.receipt().startsWith("ROOT:")));
        assertEquals(1, sent.size());
    }

    @Test
    void muteCancelsFrozenRetryWithoutReplayingOnUnmute() throws Exception {
        initial();
        outcome = EmailTransport.Status.RETRYABLE;
        assertEquals("RETRYABLE", dispatch());
        assertEquals(1, sent.size());
        review(io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action.MUTE, null);
        review(io.jenkins.plugins.changeinvestigator.notification.feedback.FeedbackRequest.Action.UNMUTE, null);
        clock.now += 1000000;
        outcome = EmailTransport.Status.SENT;
        dispatch();
        assertEquals(1, sent.size());
    }
}

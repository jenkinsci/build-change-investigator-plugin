package io.jenkins.plugins.changeinvestigator.notification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.email.*;
import io.jenkins.plugins.changeinvestigator.notification.feedback.*;
import io.jenkins.plugins.changeinvestigator.notification.identity.*;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.*;
import io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent;
import io.jenkins.plugins.changeinvestigator.notification.slack.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class FeedbackMixedStressTest {
    @TempDir
    Path directory;

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Case(
            int number,
            UUID job,
            UUID id,
            UUID mail,
            UUID slack,
            NotificationEngine engine,
            List<NotificationEngine.DestinationPolicy> policy) {}

    private final AtomicLong now = new AtomicLong(1000);
    private final Clock clock = new Clock() {
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId ignored) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }

        public long millis() {
            return now.get();
        }
    };
    private final Set<UUID> submitted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger mailCalls = new AtomicInteger(), slackCalls = new AtomicInteger();

    @Test
    @Timeout(240)
    void hundredCasesMixEvidenceReviewConfirmationMuteAndIndependentTransports() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(12);
        try {
            var cases = new ArrayList<Case>();
            for (int n = 0; n < 100; n++) {
                UUID job = UUID.randomUUID(), mail = UUID.randomUUID(), slack = UUID.randomUUID();
                var engine = new NotificationEngine(directory.resolve("job-" + n), job);
                engine.arm(0);
                var policy = List.of(
                        new NotificationEngine.DestinationPolicy(mail, 1, true, false, "EMAIL"),
                        new NotificationEngine.DestinationPolicy(slack, 1, true, false, "SLACK"));
                var record = engine.ingestConfigured(input(job, 1, false), policy, now.get());
                cases.add(new Case(n, job, record.investigationId(), mail, slack, engine, policy));
            }
            assertEquals(100, cases.stream().map(Case::id).distinct().count());
            now.addAndGet(20000);
            var tasks = new ArrayList<Callable<Void>>();
            for (var c : cases) {
                tasks.add(() -> {
                    dispatch(c, true);
                    return null;
                });
                tasks.add(() -> {
                    dispatch(c, false);
                    return null;
                });
            }
            run(workers, tasks);
            assertEquals(100, mailCalls.get());
            assertEquals(100, slackCalls.get());
            now.addAndGet(1000000);
            tasks.clear();
            for (var c : cases) {
                var r = current(c);
                var request = command(r, FeedbackRequest.Action.NOT_RELATED, null);
                tasks.add(() -> {
                    try {
                        apply(c, request);
                    } catch (IOException stale) {
                        assertTrue(stale.getMessage().contains("revision"));
                    }
                    return null;
                });
                tasks.add(() -> {
                    c.engine().ingestConfigured(input(c.job(), 2, false), c.policy(), now.get());
                    return null;
                });
                tasks.add(() -> {
                    dispatch(c, true);
                    dispatch(c, false);
                    return null;
                });
            }
            run(workers, tasks);
            now.addAndGet(1000000);
            for (var c : cases) c.engine().ingestConfigured(input(c.job(), 3, true), c.policy(), now.get());
            AtomicInteger winners = new AtomicInteger(), conflicts = new AtomicInteger();
            tasks.clear();
            for (var c : cases) {
                var record = current(c);
                for (int reviewer = 0; reviewer < 2; reviewer++) {
                    var request = command(record, FeedbackRequest.Action.CONFIRM_RESOLUTION, null);
                    tasks.add(() -> {
                        try {
                            apply(c, request);
                            winners.incrementAndGet();
                        } catch (IOException stale) {
                            assertTrue(stale.getMessage().contains("revision"));
                            conflicts.incrementAndGet();
                        }
                        return null;
                    });
                }
            }
            run(workers, tasks);
            assertEquals(100, winners.get());
            assertEquals(100, conflicts.get());
            now.addAndGet(6000);
            tasks.clear();
            for (var c : cases) {
                tasks.add(() -> {
                    apply(c, command(current(c), FeedbackRequest.Action.MUTE, null));
                    return null;
                });
                tasks.add(() -> {
                    dispatch(c, true);
                    return null;
                });
                tasks.add(() -> {
                    dispatch(c, false);
                    return null;
                });
            }
            run(workers, tasks);
            for (var c : cases) {
                var r = current(c);
                assertEquals(LifecycleStatus.CONFIRMED_RESOLUTION, r.lifecycle().status());
                assertEquals(
                        1,
                        r.feedback().records().stream()
                                .filter(f -> f.request().action() == FeedbackRequest.Action.CONFIRM_RESOLUTION)
                                .count());
                assertTrue(r.destinations().stream().allMatch(d -> d.policy().mutedUntil() > now.get()));
                assertEquals(
                        "FAILURE",
                        r.events()
                                .get(0)
                                .snapshot()
                                .path("build")
                                .path("result")
                                .asText());
                assertTrue(r.destinations().stream()
                        .flatMap(d -> d.intents().stream())
                        .noneMatch(i -> i.state() == OutboxIntent.State.LEASED));
                var reloaded = new NotificationEngine(directory.resolve("job-" + c.number()), c.job())
                        .records()
                        .get(0);
                assertEquals(r.feedback(), reloaded.feedback());
                assertEquals(r.destinations(), reloaded.destinations());
                apply(c, command(r, FeedbackRequest.Action.UNMUTE, null));
            }
            int beforeMail = mailCalls.get(), beforeSlack = slackCalls.get();
            now.addAndGet(1000000);
            tasks.clear();
            for (var c : cases)
                tasks.add(() -> {
                    dispatch(c, true);
                    dispatch(c, false);
                    return null;
                });
            run(workers, tasks);
            assertEquals(beforeMail, mailCalls.get());
            assertEquals(beforeSlack, slackCalls.get());
            var first = current(cases.get(0));
            assertTrue(first.destinations().stream()
                    .filter(d -> d.destinationId().equals(cases.get(0).mail()))
                    .flatMap(d -> d.intents().stream())
                    .anyMatch(i -> i.state() == OutboxIntent.State.FAILED_PERMANENT));
            assertTrue(first.destinations().stream()
                    .filter(d -> d.destinationId().equals(cases.get(0).slack()))
                    .flatMap(d -> d.intents().stream())
                    .anyMatch(i -> i.state() == OutboxIntent.State.SENT));
            System.out.println(
                    "FEEDBACK_MIXED_STRESS cases=100 reviewers=200 confirmationWinners=100 staleConflicts=100 workers=12 uniqueSubmissions="
                            + submitted.size() + " mail=" + mailCalls.get() + " slack=" + slackCalls.get()
                            + " replay=0");
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private static void run(ExecutorService workers, List<Callable<Void>> tasks) throws Exception {
        for (var future : workers.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
            assertFalse(future.isCancelled(), "Bounded mixed work timed out");
            future.get();
        }
    }

    private NotificationInvestigationRecord current(Case c) throws IOException {
        return c.engine().records().get(0);
    }

    private FeedbackRequest command(NotificationInvestigationRecord r, FeedbackRequest.Action action, UUID previous) {
        var e = r.events().get(r.events().size() - 1).snapshot();
        return new FeedbackRequest(
                action,
                UUID.randomUUID(),
                r.caseRevision(),
                action == FeedbackRequest.Action.NOT_RELATED
                        ? e.path("topCandidates").path(0).path("candidateId").asText()
                        : "",
                r.evidenceRevision(),
                action == FeedbackRequest.Action.CONFIRM_RESOLUTION
                        ? e.path("recovery").path("build").path("runId").asText()
                        : "",
                "Corrected the source reference.",
                "Affected compilation passed and the outcome was reviewed.",
                "",
                "Synthetic private note",
                previous,
                List.of(),
                0);
    }

    private void apply(Case c, FeedbackRequest request) throws IOException {
        c.engine()
                .feedback(
                        c.id(),
                        request,
                        new FeedbackActor("synthetic-reviewer", "Synthetic reviewer"),
                        () -> true,
                        text -> false,
                        now.get());
    }

    private UUID lease(Case c, UUID destination) {
        try {
            return current(c).destinations().stream()
                    .filter(d -> d.destinationId().equals(destination))
                    .flatMap(d -> d.intents().stream())
                    .filter(i -> i.state() == OutboxIntent.State.LEASED)
                    .findFirst()
                    .orElseThrow()
                    .deliveryId();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void dispatch(Case c, boolean email) throws IOException {
        if (email) {
            var settings = new EmailTransport.Settings(
                    "127.0.0.1", 2525, EmailTransport.TlsMode.PLAIN_INTERNAL, true, null, null);
            var target = new EmailOutbox.Target(
                    1,
                    "bci@example.invalid",
                    "synthetic" + c.number() + "@example.invalid",
                    URI.create("https://jenkins.example.invalid/"),
                    settings,
                    true,
                    false);
            new EmailOutbox(
                            clock,
                            new EmailSubmissionBudget(directory.resolve("mail-budget-" + c.number()), c.job()),
                            (route, recipient, mime) -> {
                                assertTrue(submitted.add(lease(c, c.mail())), "Duplicate email submission");
                                mailCalls.incrementAndGet();
                                return new EmailTransport.Outcome(
                                        c.number() == 0
                                                ? EmailTransport.Status.PERMANENT_FAILURE
                                                : EmailTransport.Status.SENT,
                                        "SYNTHETIC");
                            })
                    .dispatch(c.engine(), c.id(), c.mail(), () -> Optional.of(target), () -> true);
        } else {
            var target = new SlackOutbox.Target(
                    1,
                    "TDEMO123",
                    "CDEMO" + c.number(),
                    URI.create("https://jenkins.example.invalid/"),
                    "synthetic-only",
                    true,
                    false,
                    false,
                    null);
            new SlackOutbox(
                            clock,
                            new SlackSubmissionBudget(directory.resolve("slack-budget-" + c.number()), c.job()),
                            (token, workspace, channel, payload, thread) -> {
                                assertTrue(submitted.add(lease(c, c.slack())), "Duplicate Slack submission");
                                int count = slackCalls.incrementAndGet();
                                return new SlackTransport.Outcome(
                                        SlackTransport.Status.SENT,
                                        "ACCEPTED",
                                        0,
                                        new SlackTransport.Receipt(
                                                workspace,
                                                channel,
                                                "1234." + String.format(Locale.ROOT, "%06d", count)));
                            })
                    .dispatch(c.engine(), c.id(), c.slack(), () -> Optional.of(target), () -> true);
        }
    }

    private static NotificationObservation input(UUID job, int order, boolean recovered) throws Exception {
        String run = UUID.nameUUIDFromBytes((job + ":" + order).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        var context = ExecutionContextV1.create(
                job.toString(),
                List.of(new ExecutionContextV1.ScmSource("primary", "synthetic-repository", "BRANCH", "main", null)),
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
                run);
        String commit = "commit-" + order;
        var facts = new MaterialFacts(
                "",
                List.of(new MaterialFacts.Candidate("repo", "src/Trade.java", commit, "STRONG", List.of("SAME_FILE"))),
                List.of(commit),
                "",
                "",
                "");
        ObjectNode display;
        try (var stream = FeedbackMixedStressTest.class.getResourceAsStream(
                "/io/jenkins/plugins/changeinvestigator/notification/events-v1.json")) {
            display = (ObjectNode) JSON.readTree(stream).path("specific");
        }
        display.putObject("boundary")
                .putNull("lastKnownGood")
                .putNull("firstBad")
                .put("firstBadVerified", false)
                .put("proofStatus", "UNKNOWN");
        ((ObjectNode) display.path("build"))
                .put("runId", run)
                .put("number", order)
                .put("result", recovered ? "SUCCESS" : "FAILURE");
        display.withArray("topCandidates")
                .addObject()
                .put("candidateId", IdentityCanonicalizer.digest(commit))
                .put("path", "src/Trade.java")
                .put("commit", commit)
                .put("authorLabel", "Synthetic author")
                .put("strength", "STRONG")
                .put("relationship", "Failure names this changed file.")
                .put("limitation", "Exact changed line is not established.")
                .put("nextCheck", "Inspect the source diff.")
                .putArray("evidenceRefs")
                .add("changed-file");
        return new NotificationObservation(
                run,
                run,
                order,
                recovered ? "SUCCESS" : "FAILURE",
                true,
                true,
                context,
                signature,
                "compile:synthetic",
                facts,
                recovered
                        ? new CoverageEvidence(
                                "compiler-task", 1, "compile:synthetic", run, context.digest(), true, true)
                        : CoverageEvidence.unknown(),
                List.of(),
                display);
    }
}

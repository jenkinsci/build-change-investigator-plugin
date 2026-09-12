package io.jenkins.plugins.changeinvestigator.notification.feedback;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.NotificationInvestigationRecord;
import io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.RestartableJenkinsRule;

class FeedbackRestartTest {
    private static FeedbackRequest request(
            NotificationInvestigationRecord record,
            FeedbackRequest.Action action,
            String candidate,
            String recovery,
            String note) {
        return new FeedbackRequest(
                action,
                UUID.randomUUID(),
                record.caseRevision(),
                candidate,
                record.evidenceRevision(),
                recovery,
                action == FeedbackRequest.Action.CONFIRM_RESOLUTION ? "Corrected the missing variable reference" : "",
                action == FeedbackRequest.Action.CONFIRM_RESOLUTION
                        ? "The affected compiler task passed with verified coverage"
                        : "",
                "",
                note,
                null,
                List.of(),
                action == FeedbackRequest.Action.MUTE ? -1 : 0);
    }

    @Test
    void restartPreservesHumanHistoryConfirmationMuteAndReplayWithoutDelivery() throws Throwable {
        var restart = new RestartableJenkinsRule();
        var previousJenkins = new AtomicReference<Object>();
        var previousLoader = new AtomicReference<Object>();
        var caseId = new AtomicReference<UUID>();
        var recorded = new AtomicReference<String>();
        var acknowledge = new AtomicReference<FeedbackRequest>();
        var actor = new FeedbackActor("reviewer", "Synthetic reviewer");
        // Bridge the harness's restart rule into this Jupiter test; both steps run real Jenkins lifecycles.
        var steps = new org.junit.runners.model.Statement() {
            @Override
            public void evaluate() {
                restart.then(j -> {
                    previousJenkins.set(j.jenkins);
                    var job = j.createFreeStyleProject("synthetic-feedback-restart");
                    UUID jobId = UUID.fromString(StableIdentities.jobId(job));
                    var engine = new NotificationEngine(
                            job.getRootDir().toPath(),
                            jobId,
                            j.jenkins.getRootDir().toPath(),
                            UUID.fromString(StableIdentities.controllerId()));
                    engine.arm(0);
                    var policies = List.of(
                            new NotificationEngine.DestinationPolicy(UUID.randomUUID(), 1, true, false, "SLACK"),
                            new NotificationEngine.DestinationPolicy(UUID.randomUUID(), 1, true, false, "EMAIL"));
                    var record = engine.ingestConfigured(
                            FeedbackHttpFixtures.input(jobId, 1, "commit-a", false), policies, 1000);
                    caseId.set(record.investigationId());
                    acknowledge.set(request(record, FeedbackRequest.Action.ACKNOWLEDGE, "", "", "Reviewing this case"));
                    engine.feedback(caseId.get(), acknowledge.get(), actor, () -> true, text -> false, 2000);
                    record = FeedbackAccess.read(job, caseId.get()).orElseThrow();
                    String candidate = record.events()
                            .get(0)
                            .snapshot()
                            .path("topCandidates")
                            .get(0)
                            .path("candidateId")
                            .asText();
                    engine.feedback(
                            caseId.get(),
                            request(
                                    record,
                                    FeedbackRequest.Action.NOT_RELATED,
                                    candidate,
                                    "",
                                    "Ruled out during review"),
                            actor,
                            () -> true,
                            text -> false,
                            3000);
                    engine.ingestConfigured(FeedbackHttpFixtures.input(jobId, 2, "commit-a", true), policies, 4000);
                    record = FeedbackAccess.read(job, caseId.get()).orElseThrow();
                    String recovery = record.events()
                            .get(record.events().size() - 1)
                            .snapshot()
                            .path("recovery")
                            .path("build")
                            .path("runId")
                            .asText();
                    engine.feedback(
                            caseId.get(),
                            request(
                                    record,
                                    FeedbackRequest.Action.CONFIRM_RESOLUTION,
                                    "",
                                    recovery,
                                    "Reviewed correction"),
                            actor,
                            () -> true,
                            text -> false,
                            5000);
                    record = FeedbackAccess.read(job, caseId.get()).orElseThrow();
                    engine.feedback(
                            caseId.get(),
                            request(record, FeedbackRequest.Action.MUTE, "", "", "Paused during review"),
                            actor,
                            () -> true,
                            text -> false,
                            6000);
                    record = FeedbackAccess.read(job, caseId.get()).orElseThrow();
                    assertEquals(4, record.feedback().records().size());
                    assertTrue(record.feedback().currentConfirmation().isPresent());
                    assertTrue(record.destinations().stream()
                            .allMatch(d -> d.policy().mutedUntil() == Long.MAX_VALUE));
                    recorded.set(Files.readString(
                            job.getRootDir().toPath().resolve("bci-notifications/cases/" + caseId.get() + ".json")));
                    j.jenkins
                            .getExtensionList(NotificationRuntime.class)
                            .get(0)
                            .deliveryGuard()
                            .approve();
                });
                restart.then(j -> {
                    assertNotSame(previousJenkins.get(), j.jenkins);
                    assertNotSame(previousLoader.get(), j.jenkins.getPluginManager().uberClassLoader);
                    var job = j.jenkins.getItemByFullName("synthetic-feedback-restart", FreeStyleProject.class);
                    assertNotNull(job);
                    String current = Files.readString(
                            job.getRootDir().toPath().resolve("bci-notifications/cases/" + caseId.get() + ".json"));
                    assertEquals(
                            recorded.get(), current, "Restart must not mutate feedback or emit another notification");
                    var record = FeedbackAccess.read(job, caseId.get()).orElseThrow();
                    assertEquals(4, record.feedback().records().size());
                    assertEquals(
                            ConfirmationRecord.Kind.RESOLUTION,
                            record.feedback()
                                    .currentConfirmation()
                                    .orElseThrow()
                                    .kind());
                    assertTrue(record.destinations().stream()
                            .allMatch(d -> d.policy().mutedUntil() == Long.MAX_VALUE));
                    assertFalse(j.jenkins
                            .getExtensionList(NotificationRuntime.class)
                            .get(0)
                            .deliveryGuard()
                            .canDispatch());
                    var engine = new NotificationEngine(
                            job.getRootDir().toPath(),
                            record.jobId(),
                            j.jenkins.getRootDir().toPath(),
                            UUID.fromString(StableIdentities.controllerId()));
                    assertTrue(engine.feedback(caseId.get(), acknowledge.get(), actor, () -> true, text -> false, 7000)
                            .duplicate());
                    assertEquals(
                            recorded.get(),
                            Files.readString(job.getRootDir()
                                    .toPath()
                                    .resolve("bci-notifications/cases/" + caseId.get() + ".json")));
                });
            }
        };
        restart.apply(
                        steps,
                        new org.junit.runners.model.FrameworkMethod(getClass()
                                .getDeclaredMethod(
                                        "restartPreservesHumanHistoryConfirmationMuteAndReplayWithoutDelivery")),
                        this)
                .evaluate();
    }
}

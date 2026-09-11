package io.jenkins.plugins.changeinvestigator.notification.slack;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ExtensionList;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.NotificationObservation;
import io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime;
import io.jenkins.plugins.changeinvestigator.notification.identity.ControllerDeliveryGuard;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.CoverageEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts;
import io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent;
import io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackConfiguration;
import io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackDestination;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackDispatcherRuntimeTest {
    private static final String TOKEN = "xoxb-synthetic-runtime-only";

    @Test
    void hundredActualFailedJobsRemainBoundedFairAndPausedUntilExplicitBootApproval(JenkinsRule j) throws Exception {
        SlackDispatcher.shutdown();
        NotificationRuntime.shutdown();
        j.jenkins.setNumExecutors(12);
        JenkinsLocationConfiguration.get().setUrl(j.getURL().toString());
        var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
        var jobs = new ArrayList<FreeStyleProject>();
        var futures = new ArrayList<hudson.model.queue.QueueTaskFuture<FreeStyleBuild>>();
        for (int n = 0; n < 100; n++) {
            var job = j.createFreeStyleProject("synthetic-slack-" + n);
            job.getBuildersList().add(new CompilerFailure());
            jobs.add(job);
            futures.add(job.scheduleBuild2(0));
        }
        for (var future : futures) j.assertBuildStatus(Result.FAILURE, future.get(90, TimeUnit.SECONDS));
        var config = SlackConfiguration.get();
        config.setEnabled(true);
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "slack-runtime-token",
                        "Synthetic Slack fixture",
                        Secret.fromString(TOKEN)));
        var destinations = new ArrayList<SlackDestination>();
        for (int n = 0; n < 100; n++)
            destinations.add(new SlackDestination(
                    null,
                    "Synthetic destination " + n,
                    "Synthetic workspace",
                    "TDEMO123",
                    "CDEMO" + n,
                    "slack-runtime-token",
                    jobs.get(n).getFullName(),
                    true));
        config.replaceDestinations(destinations);
        var verification = SlackDestination.class.getDeclaredField("verified");
        verification.setAccessible(true);
        for (var destination : config.getDestinations()) verification.setBoolean(destination, true);
        config.save();
        var clock = new MutableClock(System.currentTimeMillis() + 1000000);
        var engines = new ArrayList<NotificationEngine>();
        UUID controllerId = UUID.fromString(StableIdentities.controllerId());
        for (int n = 0; n < jobs.size(); n++) {
            var job = jobs.get(n);
            UUID jobId = UUID.fromString(StableIdentities.jobId(job));
            var engine = new NotificationEngine(
                    job.getRootDir().toPath(), jobId, j.jenkins.getRootDir().toPath(), controllerId);
            engine.arm(0);
            Files.writeString(job.getRootDir().toPath().resolve("bci-notifications/core-armed"), "0");
            runtime.activateApproved(job);
            var destination = config.getDestinations().get(n);
            engine.ingestConfigured(
                    input(job, jobId),
                    List.of(new NotificationEngine.DestinationPolicy(
                            destination.identity(), destination.getGeneration(), true, false)),
                    clock.now);
            engines.add(engine);
        }
        clock.now += 20000;
        var sentChannels = ConcurrentHashMap.<String>newKeySet();
        var calls = new AtomicInteger();
        var active = new AtomicInteger();
        var maximumActive = new AtomicInteger();
        var dispatcher = new SlackDispatcher(clock, (token, workspace, channel, payload, thread) -> {
            assertEquals(TOKEN, token);
            assertNull(thread);
            assertTrue(sentChannels.add(channel), "Duplicate root submission for one seeded investigation");
            int concurrent = active.incrementAndGet();
            maximumActive.accumulateAndGet(concurrent, Math::max);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                active.decrementAndGet();
            }
            calls.incrementAndGet();
            return new SlackTransport.Outcome(
                    SlackTransport.Status.SENT,
                    "ACCEPTED",
                    0,
                    new SlackTransport.Receipt(workspace, channel, "1234.000001"));
        });
        try {
            assertFalse(runtime.deliveryGuard().canDispatch());
            dispatcher.execute(TaskListener.NULL);
            for (int n = 0; n < 1000; n++) dispatcher.schedule(jobs.get(0).getFullName());
            assertTrue(dispatcher.getQueuedWork() <= 250);
            awaitIdle(dispatcher);
            assertEquals(0, calls.get());
            assertEquals(0, dispatcher.getRejectedWork());
            runtime.deliveryGuard().approve();
            assertTrue(runtime.deliveryGuard().canDispatch());
            dispatcher.execute(TaskListener.NULL);
            for (int n = 0; n < 1000; n++) dispatcher.schedule(jobs.get(0).getFullName());
            int observedQueue = dispatcher.getQueuedWork();
            assertTrue(observedQueue <= 250);
            awaitIdle(dispatcher);
            assertEquals(100, calls.get());
            assertEquals(100, sentChannels.size());
            assertTrue(maximumActive.get() <= 2);
            dispatcher.execute(TaskListener.NULL);
            awaitIdle(dispatcher);
            assertEquals(100, calls.get());
            for (int n = 0; n < 100; n++) {
                assertEquals(Result.FAILURE, jobs.get(n).getLastBuild().getResult());
                assertEquals(
                        OutboxIntent.State.SENT,
                        engines.get(n)
                                .records()
                                .get(0)
                                .destinations()
                                .get(0)
                                .intents()
                                .get(0)
                                .state());
            }
            var restoredGuard =
                    new ControllerDeliveryGuard(j.jenkins.getRootDir().toPath(), controllerId, UUID.randomUUID());
            assertFalse(restoredGuard.canDispatch());
            restoredGuard.approve();
            assertTrue(restoredGuard.canDispatch());
            assertFalse(runtime.deliveryGuard().canDispatch(), "A second boot approval invalidates the original boot");
            System.out.println("SLACK_RUNTIME_STRESS jobs=100 executors=12 roots=" + calls.get()
                    + " observedQueue=" + observedQueue + " maxWorkers=" + maximumActive.get()
                    + " rejected=" + dispatcher.getRejectedWork());
        } finally {
            dispatcher.stop();
        }
    }

    @Test
    void operationalStatusPageRequiresAdministratorAndDoesNotSend(JenkinsRule j) throws Exception {
        SlackDispatcher.shutdown();
        NotificationRuntime.shutdown();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader"));
        var reader = j.createWebClient().login("reader");
        reader.getOptions().setThrowExceptionOnFailingStatusCode(false);
        assertEquals(403, reader.goTo("bci-slack-delivery/").getWebResponse().getStatusCode());
        var admin = j.createWebClient().login("admin");
        var page = admin.goTo("bci-slack-delivery/");
        assertEquals(200, page.getWebResponse().getStatusCode());
        String html = page.getWebResponse().getContentAsString();
        assertTrue(html.contains("Slack"));
        assertFalse(html.contains(TOKEN));
        assertFalse(html.contains("Oops"));
    }

    @Test
    void approvedPolicyAutomaticallyCapturesFailedBuildAndQueuesIntentWithoutManualArm(JenkinsRule j) throws Exception {
        SlackDispatcher.shutdown();
        var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
        var job = j.createFreeStyleProject("synthetic-approved-activation");
        job.getBuildersList().add(new CompilerFailure());
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "slack-runtime-token",
                        "Synthetic Slack fixture",
                        Secret.fromString(TOKEN)));
        var config = SlackConfiguration.get();
        config.setEnabled(true);
        var destination = new SlackDestination(
                null,
                "Synthetic activation",
                "Synthetic workspace",
                "TDEMO123",
                "CDEMO123",
                "slack-runtime-token",
                job.getFullName(),
                true);
        config.replaceDestinations(List.of(destination));
        var verification = SlackDestination.class.getDeclaredField("verified");
        verification.setAccessible(true);
        verification.setBoolean(config.getDestinations().get(0), true);
        config.save();
        assertFalse(Files.exists(job.getRootDir().toPath().resolve("bci-slack-active")));
        var build = job.scheduleBuild2(0).get(90, TimeUnit.SECONDS);
        j.assertBuildStatus(Result.FAILURE, build);
        assertTrue(Files.isRegularFile(job.getRootDir().toPath().resolve("bci-slack-active")));
        assertTrue(Files.isRegularFile(job.getRootDir().toPath().resolve("bci-notification-job-id")));
        var engine = new NotificationEngine(
                job.getRootDir().toPath(),
                UUID.fromString(StableIdentities.jobId(job)),
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (engine.records().isEmpty() && System.nanoTime() < end) Thread.sleep(25);
        var records = engine.records();
        assertEquals(1, records.size());
        assertEquals(1, records.get(0).events().size());
        assertEquals(1, records.get(0).destinations().size());
        assertEquals(
                destination.identity(), records.get(0).destinations().get(0).destinationId());
        assertEquals(
                OutboxIntent.State.QUEUED,
                records.get(0).destinations().get(0).intents().get(0).state());
        assertFalse(runtime.deliveryGuard().canDispatch());
        assertEquals(Result.FAILURE, build.getResult());
    }

    private static void awaitIdle(SlackDispatcher dispatcher) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!dispatcher.idle() && System.nanoTime() < end) Thread.sleep(25);
        assertTrue(dispatcher.idle(), "Bounded dispatcher must drain without deadlock");
    }

    private static NotificationObservation input(FreeStyleProject job, UUID jobId) throws Exception {
        String run = StableIdentities.runId(job.getLastBuild());
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
                        "src/CollateralTrade.java",
                        "diagnosticKind",
                        "cannot-find-symbol",
                        "discriminant",
                        "isPortolioIM"),
                FailureSignatureV1.ContextFields.empty(),
                run);
        ObjectNode display;
        try (var stream = SlackDispatcherRuntimeTest.class.getResourceAsStream(
                "/io/jenkins/plugins/changeinvestigator/notification/events-v1.json")) {
            display = (ObjectNode) new ObjectMapper().readTree(stream).get("specific");
        }
        display.putObject("boundary")
                .putNull("lastKnownGood")
                .putNull("firstBad")
                .put("firstBadVerified", false)
                .put("proofStatus", "UNKNOWN");
        ((ObjectNode) display.get("build"))
                .put("runId", run)
                .put("number", 1)
                .put("result", "FAILURE")
                .put("url", Jenkins.get().getRootUrl() + job.getLastBuild().getUrl());
        return new NotificationObservation(
                run,
                run,
                1,
                "FAILURE",
                true,
                true,
                context,
                signature,
                "compile:demo",
                MaterialFacts.empty(),
                CoverageEvidence.unknown(),
                List.of(),
                display);
    }

    private static final class CompilerFailure extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger().println("src/CollateralTrade.java:853:24: error: cannot find symbol");
            listener.getLogger().println("symbol: variable isPortolioIM");
            return false;
        }
    }

    private static final class MutableClock extends Clock {
        volatile long now;

        MutableClock(long now) {
            this.now = now;
        }

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
}

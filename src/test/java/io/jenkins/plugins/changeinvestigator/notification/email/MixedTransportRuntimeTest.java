package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.*;
import hudson.ExtensionList;
import hudson.model.*;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.notification.*;
import io.jenkins.plugins.changeinvestigator.notification.email.config.*;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.notification.slack.*;
import io.jenkins.plugins.changeinvestigator.notification.slack.config.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.*;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class MixedTransportRuntimeTest {
    @Test
    void hundredJobsKeepTransportQueuesIndependentAndIdentitiesUnique(JenkinsRule j) throws Exception {
        SlackDispatcher.shutdown();
        EmailDispatcher.shutdown();
        NotificationRuntime.shutdown();
        j.jenkins.setNumExecutors(12);
        JenkinsLocationConfiguration.get().setUrl(j.getURL().toString());
        var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
        var jobs = new ArrayList<FreeStyleProject>();
        var builds = new ArrayList<hudson.model.queue.QueueTaskFuture<FreeStyleBuild>>();
        for (int n = 0; n < 100; n++) {
            var job = j.createFreeStyleProject("synthetic-mixed-" + n);
            job.getBuildersList().add(new SyntheticFailure());
            jobs.add(job);
            builds.add(job.scheduleBuild2(0));
        }
        for (var build : builds) j.assertBuildStatus(Result.FAILURE, build.get(90, TimeUnit.SECONDS));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "synthetic-slack",
                        "Synthetic",
                        Secret.fromString("synthetic-mixed-token")));
        var slack = SlackConfiguration.get();
        slack.setEnabled(true);
        var sd = new ArrayList<SlackDestination>();
        var mail = EmailConfiguration.get();
        mail.setEnabled(true);
        var profile = new EmailMailProfile(null, "Synthetic", "127.0.0.1", 2525, "PLAIN_INTERNAL", true, "");
        var ed = new ArrayList<EmailDestination>();
        for (int n = 0; n < 100; n++) {
            sd.add(new SlackDestination(
                    null,
                    "Synthetic " + n,
                    "Synthetic",
                    "TDEMO123",
                    "CDEMO" + n,
                    "synthetic-slack",
                    jobs.get(n).getFullName(),
                    true));
            ed.add(new EmailDestination(
                    null,
                    "Synthetic " + n,
                    "bci@example.invalid",
                    "triage" + n + "@example.invalid",
                    profile.getId(),
                    jobs.get(n).getFullName(),
                    true));
        }
        slack.replaceDestinations(sd);
        mail.replaceConfiguration(List.of(profile), ed);
        var sv = SlackDestination.class.getDeclaredField("verified");
        sv.setAccessible(true);
        for (var d : slack.getDestinations()) sv.setBoolean(d, true);
        var ev = EmailDestination.class.getDeclaredField("verified");
        ev.setAccessible(true);
        for (var d : mail.getDestinations()) ev.setBoolean(d, true);
        var clock = new EmailOutboxTest.MutableClock();
        clock.now = System.currentTimeMillis() + 1000000;
        var engines = new ArrayList<NotificationEngine>();
        var policies = new ArrayList<List<NotificationEngine.DestinationPolicy>>();
        var controller = UUID.fromString(StableIdentities.controllerId());
        for (int n = 0; n < 100; n++) {
            var job = jobs.get(n);
            var id = UUID.fromString(StableIdentities.jobId(job));
            var engine = new NotificationEngine(
                    job.getRootDir().toPath(), id, j.jenkins.getRootDir().toPath(), controller);
            engine.arm(0);
            Files.writeString(job.getRootDir().toPath().resolve("bci-notifications/core-armed"), "0");
            runtime.activateApproved(job);
            var a = slack.getDestinations().get(n);
            var b = mail.getDestinations().get(n);
            var policy = List.of(
                    new NotificationEngine.DestinationPolicy(a.identity(), a.getGeneration(), true, false),
                    new NotificationEngine.DestinationPolicy(b.identity(), b.getGeneration(), true, false, "EMAIL"));
            policies.add(policy);
            engine.ingestConfigured(EmailFixtures.input(id, 1, "commit-a", false), policy, clock.now);
            engines.add(engine);
        }
        clock.now += 20000;
        runtime.deliveryGuard().approve();
        var emailGate = new CountDownLatch(1);
        var slackGate = new CountDownLatch(1);
        var phase = new AtomicInteger(1);
        var emailCalls = new AtomicInteger();
        var slackCalls = new AtomicInteger();
        var emailActive = new AtomicInteger();
        var slackActive = new AtomicInteger();
        var emailMax = new AtomicInteger();
        var slackMax = new AtomicInteger();
        var emailIds = ConcurrentHashMap.<String>newKeySet();
        var slackIds = ConcurrentHashMap.<String>newKeySet();
        EmailOutbox.Sender emailSender = (settings, recipient, mime) -> {
            int active = emailActive.incrementAndGet();
            emailMax.accumulateAndGet(active, Math::max);
            try {
                if (phase.get() == 1) assertTrue(emailGate.await(120, TimeUnit.SECONDS));
                var message = new jakarta.mail.internet.MimeMessage(
                        jakarta.mail.Session.getInstance(new Properties()),
                        new java.io.ByteArrayInputStream(Base64.getDecoder().decode(mime)));
                assertTrue(emailIds.add(message.getMessageID()));
                emailCalls.incrementAndGet();
                return new EmailTransport.Outcome(
                        recipient.equals("triage0@example.invalid") && phase.get() == 1
                                ? EmailTransport.Status.PERMANENT_FAILURE
                                : EmailTransport.Status.SENT,
                        "SYNTHETIC");
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                emailActive.decrementAndGet();
            }
        };
        SlackOutbox.Sender slackSender = (token, workspace, channel, payload, thread) -> {
            int active = slackActive.incrementAndGet();
            slackMax.accumulateAndGet(active, Math::max);
            try {
                if (phase.get() == 2) assertTrue(slackGate.await(120, TimeUnit.SECONDS));
                assertTrue(slackIds.add(channel + ":" + phase.get()));
                slackCalls.incrementAndGet();
                return new SlackTransport.Outcome(
                        SlackTransport.Status.SENT,
                        "ACCEPTED",
                        0,
                        new SlackTransport.Receipt(workspace, channel, "1234.00000" + phase.get()));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                slackActive.decrementAndGet();
            }
        };
        var ctor = SlackDispatcher.class.getDeclaredConstructor(Clock.class, SlackOutbox.Sender.class);
        ctor.setAccessible(true);
        var slackWorker = ctor.newInstance(clock, slackSender);
        var emailWorker = new EmailDispatcher(clock, emailSender);
        int emailQueue = 0, slackQueue = 0;
        try {
            for (var job : jobs) {
                emailWorker.schedule(job.getFullName());
                slackWorker.schedule(job.getFullName());
            }
            emailQueue = emailWorker.getQueuedWork();
            slackQueue = slackWorker.getQueuedWork();
            for (int n = 0; n < 1000; n++) {
                emailWorker.schedule(jobs.get(0).getFullName());
                slackWorker.schedule(jobs.get(0).getFullName());
            }
            await(() -> slackCalls.get() == 100, 90);
            assertEquals(0, emailCalls.get(), "Email stall must not hold Slack workers");
            emailGate.countDown();
            await(() -> emailCalls.get() == 100, 90);
            await(() -> idle(slackWorker) && emailWorker.idle(), 30);
            phase.set(2);
            clock.now += 1000000;
            for (int n = 0; n < 100; n++)
                engines.get(n)
                        .ingestConfigured(
                                EmailFixtures.input(
                                        UUID.fromString(StableIdentities.jobId(jobs.get(n))), 2, "commit-b", false),
                                policies.get(n),
                                clock.now);
            clock.now += 60001;
            for (var job : jobs) {
                slackWorker.schedule(job.getFullName());
                emailWorker.schedule(job.getFullName());
            }
            await(() -> emailCalls.get() == 199, 90);
            assertEquals(100, slackCalls.get(), "Slack stall must not hold email workers");
            slackGate.countDown();
            await(() -> slackCalls.get() == 200, 90);
            await(() -> idle(slackWorker) && emailWorker.idle(), 30);
            for (var job : jobs) {
                slackWorker.schedule(job.getFullName());
                emailWorker.schedule(job.getFullName());
                assertEquals(Result.FAILURE, job.getLastBuild().getResult());
            }
            await(() -> idle(slackWorker) && emailWorker.idle(), 30);
            assertEquals(199, emailCalls.get());
            assertEquals(200, slackCalls.get());
            assertTrue(emailQueue <= 250 && slackQueue <= 250);
            assertTrue(emailMax.get() <= 2 && slackMax.get() <= 2);
            assertEquals(0, emailWorker.getRejectedWork());
            assertEquals(0, slackWorker.getRejectedWork());
            assertEquals(
                    1,
                    engines.get(0).records().get(0).events().stream()
                            .filter(e -> e.snapshot().path("eventType").asText().equals("INVESTIGATION_OPENED"))
                            .count());
            System.out.println("MIXED_RUNTIME_STRESS jobs=100 executors=12 slackAttempts=" + slackCalls.get()
                    + " emailAttempts=" + emailCalls.get() + " slackQueue=" + slackQueue + " emailQueue=" + emailQueue
                    + " slackWorkers=" + slackMax.get() + " emailWorkers=" + emailMax.get() + " rejected=0");
        } finally {
            emailGate.countDown();
            slackGate.countDown();
            emailWorker.stop();
            var stop = SlackDispatcher.class.getDeclaredMethod("stop");
            stop.setAccessible(true);
            stop.invoke(slackWorker);
        }
    }

    private static final class SyntheticFailure extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, hudson.Launcher launcher, BuildListener listener) {
            listener.getLogger().println("Synthetic compiler failure");
            return false;
        }
    }

    private static boolean idle(SlackDispatcher dispatcher) {
        try {
            var method = SlackDispatcher.class.getDeclaredMethod("idle");
            method.setAccessible(true);
            return (boolean) method.invoke(dispatcher);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void await(java.util.function.BooleanSupplier done, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!done.getAsBoolean() && System.nanoTime() < until) Thread.sleep(25);
        assertTrue(done.getAsBoolean(), "Bounded dispatch did not finish");
    }
}

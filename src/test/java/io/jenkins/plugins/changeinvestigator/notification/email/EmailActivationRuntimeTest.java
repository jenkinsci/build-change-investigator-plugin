package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import hudson.ExtensionList;
import hudson.model.*;
import io.jenkins.plugins.changeinvestigator.notification.*;
import io.jenkins.plugins.changeinvestigator.notification.email.config.*;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jenkins.model.JenkinsLocationConfiguration;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.*;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EmailActivationRuntimeTest {
    @Test
    void approvedEmailAutomaticallyCapturesFirstFailedBuild(JenkinsRule j) throws Exception {
        SlackDispatcher.shutdown();
        EmailDispatcher.shutdown();
        JenkinsLocationConfiguration.get().setUrl(j.getURL().toString());
        var job = j.createFreeStyleProject("synthetic-email-activation");
        job.getBuildersList().add(new CompilerFailure());
        approve(job);
        var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
        assertFalse(Files.exists(job.getRootDir().toPath().resolve("bci-email-active")));
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0).get(90, TimeUnit.SECONDS));
        var engine = engine(j, job);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (engine.records().isEmpty() && System.nanoTime() < end) Thread.sleep(25);
        assertFalse(engine.records().isEmpty());
        assertEquals("0", Files.readString(job.getRootDir().toPath().resolve("bci-email-active")));
        assertEquals(1, engine.records().get(0).destinations().size());
        assertEquals("EMAIL", engine.records().get(0).destinations().get(0).transport());
        assertFalse(runtime.deliveryGuard().canDispatch());
    }

    @Test
    void firstEmailApprovalOnArmedCoreExcludesCompletedBacklog(JenkinsRule j) throws Exception {
        SlackDispatcher.shutdown();
        EmailDispatcher.shutdown();
        NotificationRuntime.shutdown();
        JenkinsLocationConfiguration.get().setUrl(j.getURL().toString());
        var job = j.createFreeStyleProject("synthetic-email-backlog");
        job.getBuildersList().add(new CompilerFailure());
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0).get(90, TimeUnit.SECONDS));
        var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
        runtime.arm(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0).get(90, TimeUnit.SECONDS));
        approve(job);
        runtime.activateApproved(job);
        assertEquals("2", Files.readString(job.getRootDir().toPath().resolve("bci-email-active")));
        var process = NotificationRuntime.class.getDeclaredMethod("process", String.class);
        process.setAccessible(true);
        process.invoke(runtime, job.getFullName());
        var engine = engine(j, job);
        assertFalse(engine.records().isEmpty());
        assertTrue(engine.records().stream().allMatch(r -> r.destinations().isEmpty()));
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0).get(90, TimeUnit.SECONDS));
        process.invoke(runtime, job.getFullName());
        assertTrue(engine.records().stream()
                .anyMatch(r ->
                        r.destinations().stream().anyMatch(d -> d.transport().equals("EMAIL"))));
        assertEquals("2", Files.readString(job.getRootDir().toPath().resolve("bci-email-active")));
    }

    static void approve(FreeStyleProject job) throws Exception {
        var config = EmailConfiguration.get();
        config.setEnabled(true);
        var profile = new EmailMailProfile(null, "Synthetic", "127.0.0.1", 2525, "PLAIN_INTERNAL", true, "");
        config.replaceConfiguration(
                List.of(profile),
                List.of(new EmailDestination(
                        null,
                        "Synthetic",
                        "bci@example.invalid",
                        "triage@example.invalid",
                        profile.getId(),
                        job.getFullName(),
                        true)));
        var field = EmailDestination.class.getDeclaredField("verified");
        field.setAccessible(true);
        field.setBoolean(config.getDestinations().get(0), true);
        config.save();
    }

    static NotificationEngine engine(JenkinsRule j, FreeStyleProject job) throws Exception {
        return new NotificationEngine(
                job.getRootDir().toPath(),
                UUID.fromString(StableIdentities.jobId(job)),
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
    }

    private static final class CompilerFailure extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, hudson.Launcher launcher, BuildListener listener) {
            listener.getLogger().println("src/CollateralTrade.java:853:24: error: cannot find symbol");
            listener.getLogger().println("symbol: variable isPortolioIM");
            return false;
        }
    }
}

package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import hudson.ExtensionList;
import hudson.model.*;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime;
import io.jenkins.plugins.changeinvestigator.notification.identity.NotificationRunIdentity;
import io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EmailSecuredActivationRuntimeTest {
    @Test
    void approvedBackgroundCollectionWorksWhenAnonymousCannotReadJobs(JenkinsRule j) throws Exception {
        secure(j);
        var job = j.createFreeStyleProject("secured-approved-notification");
        job.getBuildersList().add(new CompilerFailure());
        EmailActivationRuntimeTest.approve(job);
        var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
        assertFalse(Files.exists(job.getRootDir().toPath().resolve("bci-notifications/core-armed")));
        try (var ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertNull(j.jenkins.getItemByFullName(job.getFullName()));
        }
        var run = nativeFailedBuild(j, job);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (run.getAction(NotificationRunIdentity.class) == null && System.nanoTime() < until) Thread.sleep(25);
        assertNotNull(
                run.getAction(NotificationRunIdentity.class), "Approved background work must see the secured job");
        var engine = EmailActivationRuntimeTest.engine(j, job);
        while (engine.records().isEmpty() && System.nanoTime() < until) Thread.sleep(25);
        assertEquals(1, engine.records().size());
        var record = engine.records().get(0);
        assertEquals(1, record.destinations().size());
        assertEquals("EMAIL", record.destinations().get(0).transport());
        assertEquals(1, record.destinations().get(0).intents().size());
        assertFalse(runtime.deliveryGuard().canDispatch(), "Collection must not approve external delivery");
        assertEquals(Result.FAILURE, run.getResult());
        assertNotNull(run.getAction(InvestigationAction.class));
        var process = NotificationRuntime.class.getDeclaredMethod("process", String.class);
        process.setAccessible(true);
        try (var ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            var caller = Jenkins.getAuthentication2();
            process.invoke(runtime, job.getFullName());
            assertSame(caller, Jenkins.getAuthentication2(), "Scoped background authentication must be restored");
            assertNull(j.jenkins.getItemByFullName(job.getFullName()));
        }
    }

    @Test
    void backgroundPrivilegeDoesNotActivateAnUnapprovedJob(JenkinsRule j) throws Exception {
        secure(j);
        var approved = j.createFreeStyleProject("other-approved-notification");
        EmailActivationRuntimeTest.approve(approved);
        var job = j.createFreeStyleProject("secured-unapproved-notification");
        job.getBuildersList().add(new CompilerFailure());
        var run = nativeFailedBuild(j, job);
        var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
        assertTrue(runtime.schedule(job.getFullName()));
        var workers = NotificationRuntime.class.getDeclaredField("workers");
        workers.setAccessible(true);
        ((java.util.concurrent.ThreadPoolExecutor) workers.get(runtime))
                .submit(() -> {})
                .get(15, TimeUnit.SECONDS);
        assertFalse(Files.exists(job.getRootDir().toPath().resolve("bci-notifications/core-armed")));
        assertNull(run.getAction(NotificationRunIdentity.class));
        assertEquals(Result.FAILURE, run.getResult());
        assertFalse(runtime.deliveryGuard().canDispatch());
    }

    private static void secure(JenkinsRule j) throws Exception {
        SlackDispatcher.shutdown();
        EmailDispatcher.shutdown();
        JenkinsLocationConfiguration.get().setUrl(j.getURL().toString());
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("builder")
                .grant(Item.READ, Item.BUILD)
                .everywhere()
                .to("builder"));
    }

    private static FreeStyleBuild nativeFailedBuild(JenkinsRule j, FreeStyleProject job) throws Exception {
        try (var client = j.createWebClient().login("builder")) {
            var request = new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"), HttpMethod.POST);
            client.addCrumb(request);
            client.getPage(request);
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while ((job.getLastBuild() == null || job.getLastBuild().isBuilding()) && System.nanoTime() < until)
            Thread.sleep(25);
        var run = job.getLastBuild();
        assertNotNull(run);
        assertFalse(run.isBuilding());
        assertEquals(Result.FAILURE, run.getResult());
        return run;
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

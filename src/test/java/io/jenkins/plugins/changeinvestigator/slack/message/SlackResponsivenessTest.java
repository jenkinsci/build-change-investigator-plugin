package io.jenkins.plugins.changeinvestigator.slack.message;

import static org.junit.jupiter.api.Assertions.*;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty;
import io.jenkins.plugins.changeinvestigator.slack.lifecycle.SlackRuntime;
import io.jenkins.plugins.changeinvestigator.slack.transport.ConnectionResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackResponsivenessTest {
    static CountDownLatch entered;
    static CountDownLatch release;
    static AtomicInteger calls;

    @Test
    void blockedSlackDoesNotBlockCompletionOrInvestigationRendering(JenkinsRule j) throws Exception {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        calls = new AtomicInteger();
        var extensions = hudson.ExtensionList.lookup(SlackTransport.class);
        java.util.List.copyOf(extensions).stream()
                .filter(t -> t.getClass() == SlackTransport.class)
                .forEach(extensions::remove);
        var config = SlackConfiguration.get();
        config.setCredentialId("synthetic");
        config.setDefaultChannel("C12345678");
        config.setEnabled(true);
        var job = j.createFreeStyleProject("responsiveness-demo");
        job.addProperty(new SlackJobProperty(true, false, ""));
        job.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                listener.getLogger().println("[ERROR] src/main/java/Trade.java:[8,2] cannot find symbol");
                listener.getLogger().println("symbol: variable missing");
                return false;
            }
        });
        try {
            var build = job.scheduleBuild2(0).get(20, TimeUnit.SECONDS);
            assertEquals(Result.FAILURE, build.getResult());
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            long before = System.nanoTime();
            new SlackRuntime.Completed().onFinalized(build);
            long callbackMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - before);
            assertTrue(callbackMicros < 100000, "Callback must only enqueue work");
            before = System.nanoTime();
            String page = j.createWebClient()
                    .goTo(build.getUrl() + "change-investigation/")
                    .asNormalizedText();
            long pageMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertTrue(page.contains("cannot find symbol"));
            assertEquals(1, calls.get(), "Page rendering must not invoke Slack");
            assertEquals(1, release.getCount(), "Page rendered while Slack was still blocked");
            System.out.println("Slack responsiveness: callbackMicros=" + callbackMicros + ", pageMillis=" + pageMillis);
        } finally {
            release.countDown();
        }
    }

    @TestExtension
    public static final class SlowTransport extends SlackTransport {
        @Override
        public ConnectionResult checkConnection(String credential, String channel) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ConnectionResult(false, "Synthetic unavailable receiver", "", "", null);
        }
    }
}

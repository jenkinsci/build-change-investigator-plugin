package io.jenkins.plugins.changeinvestigator.slack.message;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty;
import io.jenkins.plugins.changeinvestigator.slack.lifecycle.EpisodeEngine;
import io.jenkins.plugins.changeinvestigator.slack.lifecycle.EpisodeStore;
import io.jenkins.plugins.changeinvestigator.slack.transport.ConnectionResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.DeliveryResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackRoute;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackStatusActionTest {
    @Test
    void ordinaryGetRendersForReaderWithoutSlackRequests(JenkinsRule j) throws Exception {
        SlackConfiguration.get().setEnabled(false);
        var project = j.createFreeStyleProject("status-demo");
        project.addProperty(new SlackJobProperty(true, false, ""));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader"));
        CountingTransport transport =
                j.jenkins.getExtensionList(SlackTransport.class).get(CountingTransport.class);
        transport.calls.set(0);
        HtmlPage page = j.createWebClient().login("reader").getPage(project, "bci-slack/");
        assertEquals(200, page.getWebResponse().getStatusCode());
        assertTrue(page.asNormalizedText().contains("No investigation notification yet."));
        assertEquals(0, transport.calls.get());
    }

    @Test
    void unreadableJobCannotExposeStatusEvenThroughDirectGetter(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("private-status-demo");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("outsider"));
        SlackStatusAction action = new SlackStatusAction(project);
        try (var ignored = ACL.as2(User.getById("outsider", true).impersonate2())) {
            assertThrows(org.springframework.security.access.AccessDeniedException.class, action::getStatus);
            assertThrows(org.springframework.security.access.AccessDeniedException.class, action::getJob);
        }
    }

    @Test
    void unknownStatusDoesNotExposeOrMutateFrozenPayload(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("uncertain-status-demo");
        EpisodeEngine.State state = new EpisodeEngine.State();
        EpisodeEngine.failure(state, 1, "signature", "material", null, "C12345678", "private payload marker", 0);
        state.active.deliveries.get(0).status = "UNKNOWN_OUTCOME";
        EpisodeStore store = new EpisodeStore(project);
        store.save(state);
        var path = project.getRootDir().toPath().resolve("bci-slack-episodes.json");
        byte[] before = Files.readAllBytes(path);
        String status = new SlackStatusAction(project).getStatus();
        assertTrue(status.contains("avoid duplicate messages"));
        assertFalse(status.contains("private payload marker"));
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test
    void corruptStateHasSafeStatusAndIsNotRewritten(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("corrupt-status-demo");
        var path = project.getRootDir().toPath().resolve("bci-slack-episodes.json");
        String corrupt = "{malformed-private-marker";
        Files.writeString(path, corrupt);
        assertEquals(
                "Notification status is unavailable. The investigation remains available.",
                new SlackStatusAction(project).getStatus());
        assertEquals(corrupt, Files.readString(path));
    }

    @TestExtension
    public static final class CountingTransport extends SlackTransport {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ConnectionResult checkConnection(String credential, String channel) {
            calls.incrementAndGet();
            return new ConnectionResult(false, "Synthetic test failure", "", "", null);
        }

        @Override
        public DeliveryResult post(String credential, SlackRoute route, String payload, String thread, String id) {
            calls.incrementAndGet();
            return new DeliveryResult(DeliveryResult.Outcome.PERMANENT, "", 0, "Synthetic test failure");
        }
    }
}

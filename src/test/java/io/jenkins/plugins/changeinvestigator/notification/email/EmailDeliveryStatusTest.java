package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class EmailDeliveryStatusTest {
    @Test
    void administratorSeesEmailRowsWithoutSlackRowsOrRecipientMetadata(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic-status");
        UUID jobId = UUID.fromString(StableIdentities.jobId(job));
        var engine = new NotificationEngine(
                job.getRootDir().toPath(),
                jobId,
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
        engine.arm(0);
        Files.writeString(job.getRootDir().toPath().resolve("bci-email-active"), "0");
        engine.ingestConfigured(
                EmailFixtures.input(jobId, 1, "commit-a", false),
                List.of(
                        new NotificationEngine.DestinationPolicy(UUID.randomUUID(), 1, true, false, "EMAIL"),
                        new NotificationEngine.DestinationPolicy(UUID.randomUUID(), 1, true, false, "SLACK")),
                System.currentTimeMillis());
        var status = new EmailDeliveryStatus();
        var rows = status.getRows();
        assertEquals(1, rows.size(), "Only the email destination should appear");
        assertEquals(job.getFullName(), rows.get(0).getJob());
        assertFalse(rows.get(0).getStatus().contains("@"));
        var page = j.createWebClient().goTo("bci-email-delivery/");
        assertTrue(page.asNormalizedText().contains("synthetic-status"));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.CONFIGURE)
                .everywhere()
                .to("configurer"));
        try (var ignored = ACL.as2(User.getById("configurer", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, status::getTarget);
            assertThrows(AccessDeniedException.class, status::getRows);
        }
    }
}

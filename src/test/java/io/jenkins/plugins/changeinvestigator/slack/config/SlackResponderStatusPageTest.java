package io.jenkins.plugins.changeinvestigator.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.changeinvestigator.slack.transport.ConnectionResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackRoute;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackResponderStatusPageTest {
    @Test
    void workspaceAndLocalStatusesKeepHealthyRowsQuiet(JenkinsRule j) throws Exception {
        var config = SlackConfiguration.get();
        var unverified = new ResponderMapping("Unverified", "U12345678");
        config.setResponderMappings(List.of(unverified));
        var client = j.createWebClient();
        var unverifiedPage = client.goTo("manage/bci-slack-responders/");
        assertTrue(unverifiedPage.asNormalizedText().contains("Workspace: Not verified yet"));
        var validationPage = j.submit(unverifiedPage.getFormByName("validate-" + unverified.getId()));
        assertTrue(validationPage
                .asNormalizedText()
                .contains("Verify the Slack connection in System configuration before validating mappings."));
        config.setResponderMappings(List.of());
        config.setCredentialId("synthetic-status-bot");
        config.setDefaultChannel("#alerts");
        config.doTestConnection("synthetic-status-bot", "#alerts");
        var verified = ResponderMapping.verified(
                new ResponderMapping("Alex", "U12345678"), "T12345678", "Demo workspace", "alex");
        var alias = ResponderMapping.verified(
                new ResponderMapping("Alex alternate", "U12345678"), "T12345678", "Demo workspace", "@alex");
        var pending = new ResponderMapping("Pending", "U23456789");
        var invalid = new ResponderMapping("Legacy", "invalid");
        var foreign = ResponderMapping.verified(
                new ResponderMapping("Elsewhere", "U34567890"), "T87654321", "Other workspace", "other");
        config.setResponderMappings(List.of(verified, alias, pending, invalid, foreign));
        var page = client.goTo("manage/bci-slack-responders/");
        assertTrue(page.asNormalizedText().contains("Workspace: Demo workspace"));
        assertEquals(
                2,
                page.getByXPath("//table[@id='responder-mappings']/tbody/tr/td[3][text()='✓ Verified']")
                        .size());
        assertEquals(
                2,
                page.getByXPath("//table[@id='responder-mappings']/tbody/tr/td[2]/div[text()='@alex']")
                        .size());
        assertFalse(page.asNormalizedText().contains("@@alex"));
        assertEquals("#mapping-editor", page.getAnchorByText("New mapping").getHrefAttribute());
        assertFalse(page.getByXPath("//h1/following-sibling::*[1]/a[@id='mapping-add']")
                .isEmpty());
        assertTrue(page.getByXPath("//form[@name='validate-" + verified.getId() + "']")
                .isEmpty());
        assertTrue(page.getByXPath("//form[@name='validate-" + alias.getId() + "']")
                .isEmpty());
        assertNotNull(page.getFormByName("validate-" + pending.getId()));
        assertNotNull(page.getFormByName("validate-" + foreign.getId()));
        assertTrue(page.asNormalizedText().contains("Needs validation"));
        assertTrue(page.asNormalizedText().contains("Invalid"));
        assertTrue(page.asNormalizedText().contains("Workspace mismatch"));
        assertTrue(page.asNormalizedText().contains("Revalidate"));
        assertFalse(page.asNormalizedText().contains("T12345678"));
        assertFalse(page.asNormalizedText().contains("T87654321"));
    }

    @TestExtension
    public static final class WorkspaceTransport extends SlackTransport {
        @Override
        public String credentialFingerprint(String credential) {
            return "synthetic-status-proof";
        }

        @Override
        public ConnectionResult checkConnection(String credential, String channel) {
            return new ConnectionResult(
                    true,
                    "Ready",
                    "Demo workspace",
                    "#alerts",
                    new SlackRoute("T12345678", "C12345678"),
                    0,
                    "synthetic-status-proof");
        }
    }
}

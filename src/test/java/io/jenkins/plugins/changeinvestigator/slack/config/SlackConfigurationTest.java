package io.jenkins.plugins.changeinvestigator.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleProject;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackConfigurationTest {
    @Test
    void togglingGlobalIntegrationPreservesCancellationRevision(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setEnabled(true);
        long original = config.getRevision();
        long activated = config.getEnabledAtMillis();
        config.setEnabled(false);
        config.setEnabled(true);
        var restored = new SlackConfiguration();
        assertEquals(original + 2, restored.getRevision());
        assertTrue(restored.getEnabledAtMillis() >= activated);
        restored.setEnabled(true);
        assertEquals(original + 2, restored.getRevision());
    }

    @Test
    void nativeGlobalFormSavesWithoutOptingInJobs(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject();
        SlackConfiguration.get().setResponderMappings(List.of(new ResponderMapping("Alex", "U12345678")));
        var form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("enabled").setChecked(true);
        form.getInputByName("_.defaultChannel").setValue("#jenkins-alerts");
        j.submit(form);
        var restored = new SlackConfiguration();
        assertTrue(restored.isEnabled());
        assertEquals("#jenkins-alerts", restored.getDefaultChannel());
        assertEquals(List.of(new ResponderMapping("Alex", "U12345678")), restored.getResponderMappings());
        assertFalse(restored.isTagResponders());
        assertNull(job.getProperty(SlackJobProperty.class));
        assertFalse(SlackJobProperty.eligible(j.buildAndAssertSuccess(job)));
    }

    @Test
    void mappingsSurviveReloadAndAmbiguityNeverMentions(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setResponderMappings(List.of(new ResponderMapping("Alex", "U12345678")));
        assertNull(config.mappedSlackUser("Alex"));
        config.setTagResponders(true);
        assertNull(new SlackConfiguration().mappedSlackUser("Alex", "T12345678"));
        config.setResponderMappings(
                List.of(new ResponderMapping("Alex", "U12345678"), new ResponderMapping("alex", "U87654321")));
        assertNull(config.mappedSlackUser("Alex"));
        config.setResponderMappings(List.of(new ResponderMapping("Alex", "<!channel>")));
        assertNull(config.mappedSlackUser("Alex"));
        assertNull(config.mappedSlackUser("<@U12345678>"));
    }

    @Test
    void invalidInputsAreBoundedAndTestConnectionFailsSafely(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setEnabled(true);
        config.setCredentialId("x".repeat(1000));
        config.setDefaultChannel("https://example.invalid/hook");
        assertEquals("", config.getCredentialId());
        assertFalse(config.isConfigured());
        assertEquals(FormValidation.Kind.ERROR, config.doTestConnection("", "#jenkins-alerts").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckDefaultChannel("https://example.invalid/").kind);
        assertEquals("", new ResponderMapping("a\nsecret", "x".repeat(100)).getIdentity());
    }

    @Test
    void testConnectionRejectsGetRequests(JenkinsRule j) throws Exception {
        var client = j.createWebClient();
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        var page = client.goTo("descriptorByName/" + SlackConfiguration.class.getName() + "/testConnection");
        assertEquals(405, page.getWebResponse().getStatusCode());
    }

    @Test
    void nonAdministratorsCannotReadCredentialsOrChangeSettings(JenkinsRule j) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .toEveryone());
        var config = SlackConfiguration.get();
        try (ACLContext ignored = ACL.as2(jenkins.model.Jenkins.ANONYMOUS2)) {
            assertThrows(
                    org.springframework.security.access.AccessDeniedException.class, config::doFillCredentialIdItems);
            assertThrows(
                    org.springframework.security.access.AccessDeniedException.class, () -> config.setEnabled(true));
            assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> config.doTestConnection("secret", "#alerts"));
        }
    }
}

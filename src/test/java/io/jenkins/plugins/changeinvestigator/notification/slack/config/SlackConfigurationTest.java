package io.jenkins.plugins.changeinvestigator.notification.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class SlackConfigurationTest {
    static final class ConnectionConfiguration extends SlackConfiguration {
        int responseMode;

        @Override
        protected io.jenkins.plugins.changeinvestigator.notification.slack.SlackTransport.ConnectionResult
                checkConnection(String token, String channelId) {
            if (responseMode == 2) throw new LinkageError("sensitive fixture must not escape");
            return new io.jenkins.plugins.changeinvestigator.notification.slack.SlackTransport.ConnectionResult(
                    true, "VERIFIED", responseMode == 1 ? "TOTHER123" : "TDEMO123", channelId);
        }
    }

    @Test
    void connectionVerificationIsServerOwnedAndLinkageFailureIsSafe(JenkinsRule j) throws Exception {
        var config = new ConnectionConfiguration();
        var d = destination(null, "CDEMO123", "slack-token", "synthetic", true);
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "slack-token",
                        "Synthetic",
                        Secret.fromString("xoxb-synthetic-fixture-secret")));
        config.replaceDestinations(List.of(d));
        config.responseMode = 1;
        assertEquals(FormValidation.Kind.ERROR, config.doTestConnection(d.getId()).kind);
        assertFalse(config.getDestinations().get(0).isVerified());
        config.responseMode = 2;
        var failed = config.doTestConnection(d.getId());
        assertEquals(FormValidation.Kind.ERROR, failed.kind);
        assertFalse(failed.getMessage().contains("sensitive"));
        config.responseMode = 0;
        assertEquals(FormValidation.Kind.OK, config.doTestConnection(d.getId()).kind);
        assertTrue(config.getDestinations().get(0).isVerified());
        config.replaceDestinations(List.of());
        config.replaceDestinations(List.of(d));
        assertEquals(2, config.getDestinations().get(0).getGeneration());
        assertFalse(config.getDestinations().get(0).isVerified());
    }

    private static SlackDestination destination(
            String id, String channel, String credential, String scope, boolean enabled) {
        return new SlackDestination(
                id, "Synthetic triage", "Demo workspace", "TDEMO123", channel, credential, scope, enabled);
    }

    static void verified(SlackConfiguration config, SlackDestination destination) throws Exception {
        config.setEnabled(true);
        config.replaceDestinations(List.of(destination));
        var field = SlackConfiguration.class.getDeclaredField("destinations");
        field.setAccessible(true);
        field.set(config, List.of(config.getDestinations().get(0).verifiedCopy()));
        config.save();
    }

    @Test
    void scopeGenerationRevocationAndCredentialRotation(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic");
        var other = j.createFreeStyleProject("other");
        var config = SlackConfiguration.get();
        var d = destination(null, "CDEMO123", "slack-token", "synthetic", true);
        verified(config, d);
        assertEquals(1, config.resolvedDestinations(job).size());
        assertTrue(config.resolvedDestinations(other).isEmpty());
        config.replaceDestinations(List.of(destination(d.getId(), "CDEMO123", "rotated-token", "synthetic", true)));
        assertEquals(1, config.getDestinations().get(0).getGeneration());
        assertFalse(config.getDestinations().get(0).isVerified());
        verified(config, config.getDestinations().get(0));
        config.replaceDestinations(List.of(destination(d.getId(), "COTHER123", "rotated-token", "synthetic", true)));
        assertEquals(2, config.getDestinations().get(0).getGeneration());
        assertTrue(config.authorize(job, d.identity(), 1).isEmpty());
        verified(config, config.getDestinations().get(0));
        config.replaceDestinations(List.of(destination(d.getId(), "COTHER123", "rotated-token", "synthetic", false)));
        assertEquals(3, config.getDestinations().get(0).getGeneration());
        assertTrue(config.authorize(job, d.identity(), 2).isEmpty());
        config.load();
        assertFalse(config.getDestinations().get(0).isEnabled());
    }

    @Test
    void inheritedCustomAndOffNeverAuthorizeForgedIds(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic");
        var config = SlackConfiguration.get();
        assertTrue(config.policy(job).destinationIds().isEmpty());
        var d = destination(null, "CDEMO123", "slack-token", "synthetic", true);
        verified(config, d);
        assertTrue(config.policy(job).recovery());
        assertFalse(config.policy(job).aiOnly());
        assertFalse(config.policy(job).mentions());
        job.addProperty(new SlackJobProperty("OFF", "", true, false, false));
        assertTrue(config.policy(job).destinationIds().isEmpty());
        job.removeProperty(SlackJobProperty.class);
        job.addProperty(new SlackJobProperty("CUSTOM", UUID.randomUUID().toString(), true, true, true));
        assertTrue(config.policy(job).destinationIds().isEmpty());
        job.removeProperty(SlackJobProperty.class);
        job.addProperty(new SlackJobProperty("CUSTOM", d.getId(), false, true, false));
        assertEquals(List.of(d.identity()), config.policy(job).destinationIds());
        assertFalse(config.policy(job).recovery());
        assertTrue(config.policy(job).aiOnly());
        j.configRoundtrip(job);
        assertEquals(d.getId(), job.getProperty(SlackJobProperty.class).getDestinationIds());
        String jobXml = Files.readString(job.getConfigFile().getFile().toPath());
        assertFalse(jobXml.contains("credentialId"));
        assertFalse(jobXml.contains("channelId"));
        var page = j.createWebClient().getPage(job, "configure");
        var form = page.getFormByName("config");
        var selections = form.<org.htmlunit.html.HtmlSelect>getByXPath(
                ".//section[div[contains(@class, 'jenkins-section__title') and contains(normalize-space(.), 'Build Change Investigator Slack policy')]]//select[@name='destinationIds']");
        assertEquals(1, selections.size(), "The Slack property must have one approved-destination selector");
        var selection = selections.get(0);
        assertEquals(
                List.of(d.getId()),
                selection.getSelectedOptions().stream()
                        .map(org.htmlunit.html.HtmlOption::getValueAttribute)
                        .toList(),
                "The forged request must modify the saved Slack selection");
        var forged = (org.htmlunit.html.HtmlOption) page.createElement("option");
        forged.setValueAttribute(UUID.randomUUID().toString());
        forged.setTextContent("Unapproved destination");
        selection.appendChild(forged);
        selection.setSelectedAttribute(forged, true);
        page.getWebClient().getOptions().setThrowExceptionOnFailingStatusCode(false);
        var rejected = j.submit(form);
        assertTrue(rejected.getWebResponse().getStatusCode() >= 400);
        assertEquals(d.getId(), job.getProperty(SlackJobProperty.class).getDestinationIds());
    }

    @Test
    void globalConfigurationRequiresAdministratorAndSecretsStayInCredentials(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic");
        var config = SlackConfiguration.get();
        var d = destination(null, "CDEMO123", "slack-token", "synthetic", true);
        verified(config, d);
        String syntheticSecret = "xoxb-synthetic-fixture-secret";
        var store = SystemCredentialsProvider.getInstance();
        store.getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, "slack-token", "Synthetic", Secret.fromString(syntheticSecret)));
        assertEquals(
                syntheticSecret,
                config.resolveToken(job, config.getDestinations().get(0))
                        .orElseThrow()
                        .getPlainText());
        String xml =
                Files.readString(j.jenkins.getRootDir().toPath().resolve(SlackConfiguration.class.getName() + ".xml"));
        assertFalse(xml.contains(syntheticSecret));
        assertFalse(xml.contains("SlackTransport"));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.CONFIGURE)
                .everywhere()
                .to("configurer"));
        try (var ignored = ACL.as2(User.getById("configurer", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> config.replaceDestinations(List.of()));
            assertThrows(AccessDeniedException.class, () -> config.doTestConnection(d.getId()));
            assertThrows(AccessDeniedException.class, config::doRearmDelivery);
        }
    }

    @Test
    void connectionPostCrumbAndSafeErrors(JenkinsRule j) throws Exception {
        var config = SlackConfiguration.get();
        var d = destination(null, "CDEMO123", "missing-token", "synthetic", true);
        config.replaceDestinations(List.of(d));
        var wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        String path =
                "descriptorByName/" + SlackConfiguration.class.getName() + "/testConnection?destinationId=" + d.getId();
        assertEquals(405, wc.goTo(path, null).getWebResponse().getStatusCode());
        var request = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        assertEquals(403, wc.getPage(request).getWebResponse().getStatusCode());
        wc.addCrumb(request);
        var response = wc.getPage(request).getWebResponse();
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getContentAsString().contains("credential is unavailable"));
        assertFalse(response.getContentAsString().contains("Oops"));
        assertEquals(FormValidation.Kind.ERROR, config.doTestConnection("not-an-id").kind);
        assertFalse(config.getDestinations().get(0).isVerified());
    }

    @Test
    void globalFormRendersAndRoundtripsWithoutGrantingVerification(JenkinsRule j) throws Exception {
        var config = SlackConfiguration.get();
        config.replaceDestinations(List.of(destination(null, "CDEMO123", "slack-token", "synthetic", true)));
        String savedDestinationId = config.getDestinations().get(0).getId();
        long savedGeneration = config.getDestinations().get(0).getGeneration();
        var page = j.createWebClient().goTo("configure");
        assertTrue(page.asNormalizedText().contains("Build Change Investigator — Slack"));
        j.submit(page.getFormByName("config"));
        assertEquals(savedDestinationId, config.getDestinations().get(0).getId());
        assertEquals(savedGeneration, config.getDestinations().get(0).getGeneration());
        assertEquals(1, config.getDestinations().size());
        assertFalse(config.getDestinations().get(0).isVerified());
    }

    @Test
    void strictRoutingAndVerifiedMappingBounds(JenkinsRule j) {
        assertThrows(
                hudson.model.Descriptor.FormException.class,
                () -> new SlackJobProperty.DescriptorImpl()
                        .newInstance((org.kohsuke.stapler.StaplerRequest2) null, new net.sf.json.JSONObject()));
        var folder = destination(null, "CDEMO123", "slack-token", "payments/**", true);
        assertTrue(folder.allows("payments/service"));
        assertFalse(folder.allows("payments-other/service"));
        assertFalse(folder.allows("payments"));
        assertThrows(
                IllegalArgumentException.class,
                () -> destination(null, "https://untrusted.invalid", "token", "synthetic", true));
        assertThrows(IllegalArgumentException.class, () -> destination(null, "CDEMO123", "token", "**", true));
        assertThrows(IllegalArgumentException.class, () -> folder.setVerifiedMentionId("<!channel>"));
        folder.setVerifiedMentionId("UDEMO123");
        var changed = folder.reconciled(folder);
        changed.setVerifiedMentionId("SDEMO123");
        assertEquals(2, changed.reconciled(folder).getGeneration());
        assertEquals("SDEMO123", changed.getVerifiedMentionId());
        var config = SlackConfiguration.get();
        assertFalse(config.isEnabled());
        assertThrows(
                IllegalArgumentException.class,
                () -> config.replaceDestinations(java.util.Collections.singletonList(null)));
        config.setEnabled(true);
        config.replaceDestinations(List.of(folder));
        config.setEnabled(false);
        assertEquals(2, config.getDestinations().get(0).getGeneration());
        config.setEnabled(true);
        assertEquals(3, config.getDestinations().get(0).getGeneration());
    }
}

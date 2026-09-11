package io.jenkins.plugins.changeinvestigator.notification.email.config;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.notification.email.EmailTransport;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class EmailConfigurationTest {
    private static final String PROFILE = "ecfe9555-9306-4baf-a6d7-f4c1b3c076d6";

    static final class ConnectionConfiguration extends EmailConfiguration {
        int mode;

        @Override
        protected EmailTransport.ConnectionResult checkConnection(EmailTransport.Settings settings) {
            if (mode == 2) throw new LinkageError("sensitive fixture must not escape");
            return new EmailTransport.ConnectionResult(mode == 0, mode == 0 ? "VERIFIED" : "REJECTED");
        }
    }

    private static EmailMailProfile profile(String host, String credential) {
        return new EmailMailProfile(PROFILE, "Synthetic relay", host, 587, "STARTTLS_REQUIRED", false, credential);
    }

    private static EmailDestination destination(String id, String recipient, String scope, boolean enabled) {
        return new EmailDestination(
                id, "Synthetic triage", "investigation@example.org", recipient, PROFILE, scope, enabled);
    }

    private static void verified(EmailConfiguration config, EmailDestination destination) throws Exception {
        config.setEnabled(true);
        config.replaceConfiguration(List.of(profile("smtp.example.org", "")), List.of(destination));
        var field = EmailConfiguration.class.getDeclaredField("destinations");
        field.setAccessible(true);
        field.set(config, List.of(config.getDestinations().get(0).verifiedCopy()));
        config.save();
    }

    @Test
    void nonpostingVerificationIsServerOwnedAndLinkageFailureIsSafe(JenkinsRule j) throws Exception {
        var config = new ConnectionConfiguration();
        var d = destination(null, "triage@example.org", "synthetic", true);
        config.replaceConfiguration(List.of(profile("smtp.example.org", "")), List.of(d));
        config.mode = 1;
        assertEquals(FormValidation.Kind.ERROR, config.doTestConnection(d.getId()).kind);
        assertFalse(config.getDestinations().get(0).isVerified());
        config.mode = 2;
        var failed = config.doTestConnection(d.getId());
        assertEquals(FormValidation.Kind.ERROR, failed.kind);
        assertFalse(failed.getMessage().contains("sensitive"));
        config.mode = 0;
        assertEquals(FormValidation.Kind.OK, config.doTestConnection(d.getId()).kind);
        assertTrue(config.getDestinations().get(0).isVerified());
        config.replaceDestinations(List.of());
        config.replaceDestinations(List.of(d));
        assertEquals(2, config.getDestinations().get(0).getGeneration());
        assertFalse(config.getDestinations().get(0).isVerified());
    }

    @Test
    void routeScopeAndGlobalToggleRevokeOldIntentWhileRotationDoesNotReroute(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic");
        var other = j.createFreeStyleProject("other");
        var config = EmailConfiguration.get();
        var d = destination(null, "triage@example.org", "synthetic", true);
        verified(config, d);
        assertEquals(1, config.resolvedDestinations(job).size());
        assertTrue(config.resolvedDestinations(other).isEmpty());
        config.replaceConfiguration(List.of(profile("smtp.example.org", "rotated")), config.getDestinations());
        assertEquals(1, config.getDestinations().get(0).getGeneration());
        assertFalse(config.getDestinations().get(0).isVerified());
        config.replaceConfiguration(List.of(profile("new-smtp.example.org", "rotated")), config.getDestinations());
        assertEquals(2, config.getDestinations().get(0).getGeneration());
        config.replaceDestinations(List.of(destination(d.getId(), "other@example.org", "synthetic", true)));
        assertEquals(3, config.getDestinations().get(0).getGeneration());
        assertTrue(config.authorize(job, d.identity(), 1).isEmpty());
        config.setEnabled(false);
        config.setEnabled(true);
        assertEquals(5, config.getDestinations().get(0).getGeneration());
        config.load();
        assertEquals("new-smtp.example.org", config.getProfiles().get(0).getHost());
        assertFalse(config.getDestinations().get(0).isVerified());
    }

    @Test
    void inheritedCustomOffAndNativeFormRejectForgedDestinationIds(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic");
        var config = EmailConfiguration.get();
        assertTrue(config.policy(job).destinationIds().isEmpty());
        var d = destination(null, "triage@example.org", "synthetic", true);
        verified(config, d);
        assertTrue(config.policy(job).recovery());
        assertFalse(config.policy(job).aiOnly());
        job.addProperty(new EmailJobProperty("OFF", "", true, false));
        assertTrue(config.policy(job).destinationIds().isEmpty());
        job.removeProperty(EmailJobProperty.class);
        job.addProperty(new EmailJobProperty("CUSTOM", UUID.randomUUID().toString(), true, true));
        assertTrue(config.policy(job).destinationIds().isEmpty());
        job.removeProperty(EmailJobProperty.class);
        job.addProperty(new EmailJobProperty("CUSTOM", d.getId(), false, true));
        j.configRoundtrip(job);
        assertEquals(List.of(d.identity()), config.policy(job).destinationIds());
        assertFalse(config.policy(job).recovery());
        assertTrue(config.policy(job).aiOnly());
        String xml = Files.readString(job.getConfigFile().getFile().toPath());
        assertFalse(xml.contains("recipient"));
        assertFalse(xml.contains("credentialId"));
        var page = j.createWebClient().getPage(job, "configure");
        var form = page.getFormByName("config");
        var selections = form.<org.htmlunit.html.HtmlSelect>getByXPath(
                ".//section[div[contains(@class, 'jenkins-section__title') and contains(normalize-space(.), 'Build Change Investigator Email policy')]]//select[@name='destinationIds']");
        assertEquals(1, selections.size(), "The Email property must have one approved-destination selector");
        var selection = selections.get(0);
        assertEquals(
                List.of(d.getId()),
                selection.getSelectedOptions().stream()
                        .map(org.htmlunit.html.HtmlOption::getValueAttribute)
                        .toList(),
                "The forged request must modify the saved Email selection");
        var forged = (org.htmlunit.html.HtmlOption) page.createElement("option");
        forged.setValueAttribute(UUID.randomUUID().toString());
        forged.setTextContent("Unapproved destination");
        selection.appendChild(forged);
        selection.setSelectedAttribute(forged, true);
        page.getWebClient().getOptions().setThrowExceptionOnFailingStatusCode(false);
        assertTrue(j.submit(form).getWebResponse().getStatusCode() >= 400);
        assertEquals(d.getId(), job.getProperty(EmailJobProperty.class).getDestinationIds());
    }

    @Test
    void administratorBoundaryAndCredentialReferencePersistence(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic");
        var config = EmailConfiguration.get();
        var d = destination(null, "triage@example.org", "synthetic", true);
        verified(config, d);
        String fixture = "synthetic-mail-fixture-password";
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "smtp-auth", "Synthetic", "synthetic-user", fixture));
        config.replaceConfiguration(List.of(profile("smtp.example.org", "smtp-auth")), config.getDestinations());
        var field = EmailConfiguration.class.getDeclaredField("destinations");
        field.setAccessible(true);
        field.set(config, List.of(config.getDestinations().get(0).verifiedCopy()));
        config.save();
        var settings =
                config.resolveSettings(job, config.getDestinations().get(0)).orElseThrow();
        assertEquals(fixture, settings.password());
        String xml =
                Files.readString(j.jenkins.getRootDir().toPath().resolve(EmailConfiguration.class.getName() + ".xml"));
        assertFalse(xml.contains(fixture));
        assertFalse(xml.contains("EmailTransport"));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.CONFIGURE)
                .everywhere()
                .to("configurer"));
        try (var ignored = ACL.as2(User.getById("configurer", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> config.replaceConfiguration(List.of(), List.of()));
            assertThrows(AccessDeniedException.class, () -> config.setEnabled(false));
            assertThrows(AccessDeniedException.class, () -> config.doTestConnection(d.getId()));
            assertThrows(AccessDeniedException.class, config::doRearmDelivery);
        }
    }

    @Test
    void connectionRequiresPostAndCrumbAndSafeErrors(JenkinsRule j) throws Exception {
        var config = EmailConfiguration.get();
        var d = destination(null, "triage@example.org", "synthetic", true);
        config.replaceConfiguration(List.of(profile("smtp.example.org", "missing-credential")), List.of(d));
        var wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        String path =
                "descriptorByName/" + EmailConfiguration.class.getName() + "/testConnection?destinationId=" + d.getId();
        assertEquals(405, wc.goTo(path, null).getWebResponse().getStatusCode());
        var request = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        assertEquals(403, wc.getPage(request).getWebResponse().getStatusCode());
        wc.addCrumb(request);
        var response = wc.getPage(request).getWebResponse();
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getContentAsString().contains("credential is unavailable"));
        assertFalse(response.getContentAsString().contains("Oops"));
        assertFalse(config.getDestinations().get(0).isVerified());
    }

    @Test
    void globalNativeFormRoundtripCannotGrantVerification(JenkinsRule j) throws Exception {
        var config = EmailConfiguration.get();
        config.replaceConfiguration(
                List.of(profile("smtp.example.org", "")),
                List.of(destination(null, "triage@example.org", "synthetic", true)));
        String savedDestinationId = config.getDestinations().get(0).getId();
        var page = j.createWebClient().goTo("configure");
        assertTrue(page.asNormalizedText().contains("Build Change Investigator — Email"));
        page.getWebClient().getOptions().setThrowExceptionOnFailingStatusCode(false);
        var submitted = j.submit(page.getFormByName("config"));
        assertEquals(
                200,
                submitted.getWebResponse().getStatusCode(),
                "Saved email configuration must roundtrip through the native form");
        assertEquals(PROFILE, config.getProfiles().get(0).getId());
        assertEquals(savedDestinationId, config.getDestinations().get(0).getId());
        assertEquals(1, config.getDestinations().get(0).getGeneration());
        assertEquals(1, config.getProfiles().size());
        assertEquals(1, config.getDestinations().size());
        assertFalse(config.getDestinations().get(0).isVerified());
    }

    @Test
    void strictSingleMailboxHeaderScopeAndProfileValidation(JenkinsRule j) {
        for (String mailbox : List.of(
                "a@example.org,b@example.org",
                "Demo <a@example.org>",
                "a@example.org\r\nBcc: b@example.org",
                "a@example.org\n",
                "a..b@example.org"))
            assertThrows(IllegalArgumentException.class, () -> destination(null, mailbox, "synthetic", true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailDestination(
                        null,
                        "Synthetic",
                        "a@example.org\r\nX: injected",
                        "a@example.org",
                        PROFILE,
                        "synthetic",
                        true));
        var folder = destination(null, "triage@example.org", "payments/**", true);
        assertTrue(folder.allows("payments/service"));
        assertFalse(folder.allows("payments-other/service"));
        assertFalse(folder.allows("payments"));
        assertThrows(IllegalArgumentException.class, () -> destination(null, "triage@example.org", "**", true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailMailProfile(PROFILE, "Demo", "smtp.example.org", 25, "PLAIN_INTERNAL", false, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailMailProfile(PROFILE, "Demo", "127.0.0.1", 25, "PLAIN_INTERNAL", true, "auth"));
        assertThrows(IllegalArgumentException.class, () -> profile("smtp.example.org\r\nQUIT", ""));
        assertThrows(
                hudson.model.Descriptor.FormException.class,
                () -> new EmailJobProperty.DescriptorImpl()
                        .newInstance((org.kohsuke.stapler.StaplerRequest2) null, new net.sf.json.JSONObject()));
        var config = EmailConfiguration.get();
        assertThrows(IllegalArgumentException.class, () -> config.replaceConfiguration(List.of(), List.of(folder)));
        assertThrows(
                IllegalArgumentException.class,
                () -> config.replaceConfiguration(java.util.Collections.singletonList(null), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> config.replaceConfiguration(
                        List.of(profile("smtp.example.org", "")), java.util.Collections.singletonList(null)));
        assertTrue(config.getProfiles().isEmpty());
    }
}

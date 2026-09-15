package io.jenkins.plugins.changeinvestigator.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class SlackJobPropertyTest {
    @Test
    void readOnlyUserCannotReconfigureOrValidateChannelOverride(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject();
        var property = new SlackJobProperty(true, true, "#approved-channel");
        job.addProperty(property);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader"));
        var descriptor = j.jenkins.getDescriptorByType(SlackJobProperty.DescriptorImpl.class);
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertTrue(job.hasPermission(Item.READ));
            assertFalse(job.hasPermission(Item.CONFIGURE));
            assertThrows(
                    AccessDeniedException.class,
                    () -> property.reconfigure((org.kohsuke.stapler.StaplerRequest2) null, new JSONObject()));
            assertThrows(AccessDeniedException.class, () -> descriptor.doCheckChannel(job, "#other-channel"));
        }
        assertSame(property, job.getProperty(SlackJobProperty.class));
        assertTrue(property.isEnabled());
        assertEquals("#approved-channel", property.getChannel());
    }

    @Test
    void optInExcludesHistoricalBuildsAndNativeCopyDefaultsOff(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("source");
        var old = j.buildAndAssertSuccess(job);
        job.addProperty(new SlackJobProperty(true, false, ""));
        assertFalse(SlackJobProperty.eligible(old));
        assertTrue(SlackJobProperty.eligible(j.buildAndAssertSuccess(job)));
        var copy = (hudson.model.FreeStyleProject) j.jenkins.copy((hudson.model.TopLevelItem) job, "copy");
        assertFalse(copy.getProperty(SlackJobProperty.class).isEnabled());
        copy.setDisabled(false);
        j.configRoundtrip(copy);
        assertTrue(copy.isBuildable());
        assertFalse(SlackJobProperty.eligible(j.buildAndAssertSuccess(copy)));
    }

    @Test
    void nativeJobFormAndReloadPreserveWatermarkAndOverride(JenkinsRule j) throws Exception {
        SlackConfiguration.get().setDefaultChannel("#global-alerts");
        var job = j.createFreeStyleProject();
        var prior = j.buildAndAssertSuccess(job);
        var form = j.createWebClient().getPage(job, "configure").getFormByName("config");
        form.getInputByName("enabled").setChecked(true);
        form.getInputByName("useChannelOverride").setChecked(true);
        form.getInputByName("_.channel").setValue("#payments-alerts");
        j.submit(form);
        assertTrue(job.getProperty(SlackJobProperty.class).isEnabled());
        assertEquals(prior.getNumber(), job.getProperty(SlackJobProperty.class).getEnabledAfterBuild());
        assertEquals("#payments-alerts", SlackJobProperty.effectiveChannel(job));
        j.configRoundtrip(job);
        assertFalse(SlackJobProperty.eligible(prior));
        assertTrue(SlackJobProperty.eligible(j.buildAndAssertSuccess(job)));
        job.doReload();
        assertEquals("#payments-alerts", SlackJobProperty.effectiveChannel(job));
    }

    @Test
    void absentGlobalConfigExplainsSetupAndDefaultChannelIsInherited(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject();
        var client = j.createWebClient();
        var page = client.getPage(job, "configure");
        org.htmlunit.html.HtmlCheckBoxInput enabled =
                page.getFormByName("config").getInputByName("enabled");
        assertFalse(enabled.isChecked());
        enabled.click();
        client.waitForBackgroundJavaScript(1000);
        assertTrue(enabled.isChecked());
        assertTrue(page.asNormalizedText().contains("Slack is not configured yet."));
        SlackConfiguration.get().setDefaultChannel("#global-alerts");
        job.addProperty(new SlackJobProperty(true, false, "#ignored"));
        assertEquals("#global-alerts", SlackJobProperty.effectiveChannel(job));
    }
}

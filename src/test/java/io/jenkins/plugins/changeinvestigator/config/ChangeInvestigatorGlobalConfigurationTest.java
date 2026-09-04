package io.jenkins.plugins.changeinvestigator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.ai.provider.AiProviderConfig;
import io.jenkins.plugins.changeinvestigator.ai.provider.AnthropicProviderConfig;
import io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ChangeInvestigatorGlobalConfigurationTest {

    @Test
    void configureFormSubmissionPersistsProviderSelectionInOneBulkSave(JenkinsRule jenkins) throws Exception {
        // Exercises the real Jelly form (f:optionalBlock + f:dropdownDescriptorSelector +
        // BulkChange-backed configure()), not just direct setter calls.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        org.htmlunit.html.HtmlPage page = wc.goTo("configure");
        org.htmlunit.html.HtmlForm form = page.getFormByName("config");

        org.htmlunit.html.HtmlCheckBoxInput aiEnabled = form.getInputByName("aiEnabled");
        aiEnabled.setChecked(true);
        form.getInputByName("_.temperature").setValue("0.55");
        form.getInputByName("_.timeoutSeconds").setValue("45");
        form.getInputByName("_.maxLogContextChars").setValue("1234");
        jenkins.submit(form);

        ChangeInvestigatorGlobalConfiguration reloaded = new ChangeInvestigatorGlobalConfiguration();
        assertTrue(reloaded.isAiEnabled());
        assertEquals(45, reloaded.getTimeoutSeconds());
        assertEquals(0.55, reloaded.getTemperature(), 0.0001);
        assertEquals(1234, reloaded.getMaxLogContextChars());
    }

    /**
     * Regression guard for a bug where the "AI Provider" label was rendered twice: an outer
     * {@code f:entry title="AI Provider"} wrapped an {@code f:dropdownDescriptorSelector} that
     * already renders its own titled row. Counts the label text in the raw form markup rather
     * than a specific CSS selector, so it stays meaningful even if Jenkins core changes exactly
     * how {@code f:entry}/{@code f:dropdownDescriptorSelector} render their row markup.
     */
    @Test
    void aiProviderLabelAppearsExactlyOnce(JenkinsRule jenkins) throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        org.htmlunit.html.HtmlPage page = wc.goTo("configure");
        String formHtml = page.getFormByName("config").asXml();

        int occurrences = formHtml.split("AI Provider", -1).length - 1;
        assertEquals(1, occurrences, "\"AI Provider\" label must appear exactly once in the config form");
    }

    @Test
    void settingsSurviveAFreshLoadFromDisk(JenkinsRule jenkins) {
        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        config.setProviderConfig(new AnthropicProviderConfig("claude-sonnet-5", "some-credential-id"));
        config.setMaxLogContextChars(1234);
        config.setTimeoutSeconds(45);
        config.setTemperature(0.55);

        // A brand-new instance loads from the same on-disk config file, independent of the
        // singleton's in-memory state, proving the settings (including the polymorphic
        // provider config) were actually persisted and restored to the correct concrete type.
        ChangeInvestigatorGlobalConfiguration reloaded = new ChangeInvestigatorGlobalConfiguration();

        assertTrue(reloaded.isAiEnabled());
        assertInstanceOf(AnthropicProviderConfig.class, reloaded.getProviderConfig());
        AnthropicProviderConfig providerConfig = (AnthropicProviderConfig) reloaded.getProviderConfig();
        assertEquals("claude-sonnet-5", providerConfig.getModel());
        assertEquals("some-credential-id", providerConfig.getCredentialsId());
        assertEquals(1234, reloaded.getMaxLogContextChars());
        assertEquals(45, reloaded.getTimeoutSeconds());
        assertEquals(0.55, reloaded.getTemperature(), 0.0001);
    }

    @Test
    void defaultsAreSensibleWhenNothingConfigured(JenkinsRule jenkins) {
        ChangeInvestigatorGlobalConfiguration config = new ChangeInvestigatorGlobalConfiguration();
        assertFalse(config.isAiEnabled());
        assertNull(config.getProviderConfig());
        assertEquals(
                ChangeInvestigatorGlobalConfiguration.DEFAULT_MAX_LOG_CONTEXT_CHARS, config.getMaxLogContextChars());
        assertEquals(ChangeInvestigatorGlobalConfiguration.DEFAULT_TIMEOUT_SECONDS, config.getTimeoutSeconds());
        assertEquals(ChangeInvestigatorGlobalConfiguration.DEFAULT_TEMPERATURE, config.getTemperature(), 0.0001);
    }

    @Test
    void doTestConnectionErrorsClearlyWhenNoProviderConfigured(JenkinsRule jenkins) {
        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        config.setProviderConfig(null);
        FormValidation result = config.doTestConnection();
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    /**
     * The critical backward-compatibility guarantee: a config.xml saved by a pre-multi-provider
     * release of this plugin (flat baseUrl/model/credentialsId fields directly on this class,
     * no providerConfig element at all) must still load correctly after upgrading to the
     * provider-abstraction architecture, with those fields mapped onto an equivalent
     * OpenAiCompatibleProviderConfig - the closest match to what those flat fields always meant.
     * No secret is touched (credentialsId is a non-secret reference, carried over as-is), and
     * loading performs no network call.
     */
    @Test
    void migratesPreMultiProviderConfigXmlOnLoad(JenkinsRule jenkins) throws Exception {
        String legacyConfigXml = """
                <io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration>
                  <aiEnabled>true</aiEnabled>
                  <baseUrl>https://legacy.example.test/v1</baseUrl>
                  <model>legacy-model</model>
                  <credentialsId>legacy-credential-id</credentialsId>
                  <maxLogContextChars>5000</maxLogContextChars>
                  <timeoutSeconds>20</timeoutSeconds>
                  <temperature>0.3</temperature>
                </io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration>
                """;

        java.io.File configFile = new java.io.File(
                jenkins.jenkins.getRootDir(), ChangeInvestigatorGlobalConfiguration.class.getName() + ".xml");
        java.nio.file.Files.writeString(configFile.toPath(), legacyConfigXml);

        ChangeInvestigatorGlobalConfiguration migrated = new ChangeInvestigatorGlobalConfiguration();

        assertTrue(migrated.isAiEnabled(), "aiEnabled must survive migration unchanged");
        assertEquals(5000, migrated.getMaxLogContextChars(), "global advanced settings survive migration unchanged");
        assertEquals(20, migrated.getTimeoutSeconds());
        assertEquals(0.3, migrated.getTemperature(), 0.0001);

        AiProviderConfig providerConfig = migrated.getProviderConfig();
        assertNotNull(providerConfig, "the flat legacy fields must be migrated into a providerConfig, not dropped");
        assertInstanceOf(
                OpenAiCompatibleProviderConfig.class,
                providerConfig,
                "an arbitrary baseUrl/model/credential always meant a Generic OpenAI-compatible endpoint");
        OpenAiCompatibleProviderConfig compatible = (OpenAiCompatibleProviderConfig) providerConfig;
        assertEquals("https://legacy.example.test/v1", compatible.getBaseUrl());
        assertEquals("legacy-model", compatible.getModel());
        assertEquals(
                "legacy-credential-id",
                compatible.getCredentialsId(),
                "the credential ID reference is carried over unchanged - the secret it points to was never touched, "
                        + "since it lives in the Jenkins Credentials store independent of this config file");
    }

    @Test
    void migrationIsANoOpWhenNoLegacyFieldsPresent(JenkinsRule jenkins) throws Exception {
        // A config.xml already in the new format (or a brand-new installation with nothing
        // saved yet) must not have readResolve() invent a providerConfig out of nothing.
        ChangeInvestigatorGlobalConfiguration config = new ChangeInvestigatorGlobalConfiguration();
        assertNull(config.getProviderConfig());
    }
}

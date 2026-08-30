package io.jenkins.plugins.changeinvestigator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.changeinvestigator.ai.AiProviderConfig;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ChangeInvestigatorGlobalConfigurationTest {

    @Test
    void settingsSurviveAFreshLoadFromDisk(JenkinsRule jenkins) {
        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        config.setBaseUrl("https://example.test/v1");
        config.setModel("gpt-test-model");
        config.setCredentialsId("some-credential-id");
        config.setMaxLogContextChars(1234);
        config.setTimeoutSeconds(45);
        config.setTemperature(0.55);

        // A brand-new instance loads from the same on-disk config file, independent of the
        // singleton's in-memory state, proving the settings were actually persisted.
        ChangeInvestigatorGlobalConfiguration reloaded = new ChangeInvestigatorGlobalConfiguration();

        assertTrue(reloaded.isAiEnabled());
        assertEquals("https://example.test/v1", reloaded.getBaseUrl());
        assertEquals("gpt-test-model", reloaded.getModel());
        assertEquals("some-credential-id", reloaded.getCredentialsId());
        assertEquals(1234, reloaded.getMaxLogContextChars());
        assertEquals(45, reloaded.getTimeoutSeconds());
        assertEquals(0.55, reloaded.getTemperature(), 0.0001);
    }

    @Test
    void defaultsAreSensibleWhenNothingConfigured(JenkinsRule jenkins) {
        ChangeInvestigatorGlobalConfiguration config = new ChangeInvestigatorGlobalConfiguration();
        assertFalse(config.isAiEnabled());
        assertEquals(ChangeInvestigatorGlobalConfiguration.DEFAULT_MAX_LOG_CONTEXT_CHARS, config.getMaxLogContextChars());
        assertEquals(ChangeInvestigatorGlobalConfiguration.DEFAULT_TIMEOUT_SECONDS, config.getTimeoutSeconds());
    }

    @Test
    void toProviderConfigCarriesSettingsThroughAndOmitsUnresolvedToken(JenkinsRule jenkins) {
        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        config.setBaseUrl("https://example.test/v1");
        config.setModel("m");
        config.setTimeoutSeconds(45);
        config.setTemperature(0.55);
        config.setMaxLogContextChars(1234);

        AiProviderConfig providerConfig = config.toProviderConfig();

        assertEquals("https://example.test/v1", providerConfig.baseUrl());
        assertEquals("m", providerConfig.model());
        assertEquals(45, providerConfig.timeoutSeconds());
        assertEquals(0.55, providerConfig.temperature(), 0.0001);
        assertEquals(1234, providerConfig.maxLogContextChars());
        assertFalse(providerConfig.hasApiToken(), "no credential configured, so no token should resolve");
    }
}

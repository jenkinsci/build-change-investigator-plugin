package io.jenkins.plugins.changeinvestigator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.changeinvestigator.ai.AiProviderConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class ChangeInvestigatorGlobalConfigurationTest {

    @Test
    void configureFormSubmissionPersistsOptionalBlockFieldsInOneBulkSave(JenkinsRule jenkins) throws Exception {
        // Exercises the real Jelly form (f:optionalBlock + BulkChange-backed configure()), not
        // just direct setter calls: proves inline="true" keeps the wrapped fields flat in the
        // submitted JSON (rather than nested under "aiEnabled"), and that the whole submission
        // still persists correctly through the single BulkChange-deferred save().
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        org.htmlunit.html.HtmlPage page = wc.goTo("configure");
        org.htmlunit.html.HtmlForm form = page.getFormByName("config");

        org.htmlunit.html.HtmlCheckBoxInput aiEnabled = form.getInputByName("aiEnabled");
        aiEnabled.setChecked(true);
        form.getInputByName("_.baseUrl").setValue("https://example.test/v1");
        form.getInputByName("_.model").setValue("gpt-test-model");
        form.getInputByName("_.maxLogContextChars").setValue("1234");
        form.getInputByName("_.timeoutSeconds").setValue("45");
        form.getInputByName("_.temperature").setValue("0.55");
        jenkins.submit(form);

        ChangeInvestigatorGlobalConfiguration reloaded = new ChangeInvestigatorGlobalConfiguration();
        assertTrue(reloaded.isAiEnabled());
        assertEquals("https://example.test/v1", reloaded.getBaseUrl());
        assertEquals("gpt-test-model", reloaded.getModel());
        assertEquals(1234, reloaded.getMaxLogContextChars());
        assertEquals(45, reloaded.getTimeoutSeconds());
        assertEquals(0.55, reloaded.getTemperature(), 0.0001);
    }

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
        assertEquals(
                ChangeInvestigatorGlobalConfiguration.DEFAULT_MAX_LOG_CONTEXT_CHARS, config.getMaxLogContextChars());
        assertEquals(ChangeInvestigatorGlobalConfiguration.DEFAULT_TIMEOUT_SECONDS, config.getTimeoutSeconds());
    }

    @Test
    void toProviderConfigCarriesNonSecretSettingsThrough(JenkinsRule jenkins) {
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
    }

    @Test
    void resolveApiTokenIsNullWhenNoCredentialConfigured(JenkinsRule jenkins) {
        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        assertEquals(null, config.resolveApiToken(), "no credential configured, so no token should resolve");
    }

    @Test
    void doCheckBaseUrlIsHarmlessForUsersWithoutAdminister(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("readOnlyUser"));

        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();

        try (ACLContext ctx = ACL.as2(User.getById("readOnlyUser", true).impersonate2())) {
            // Even a value that would normally fail validation must come back ok() for a
            // non-administrator: doCheck* must never leak "is this a valid-looking URL?"
            // information, and must never throw, to a caller without Jenkins.ADMINISTER.
            assertEquals(FormValidation.Kind.OK, config.doCheckBaseUrl("not-a-url").kind);
            assertEquals(FormValidation.Kind.OK, config.doCheckBaseUrl("").kind);
            assertEquals(FormValidation.Kind.OK, config.doCheckBaseUrl(null).kind);
        }
    }

    @Test
    void doCheckBaseUrlValidatesNormallyForAdministrators(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("adminUser"));

        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();

        try (ACLContext ctx = ACL.as2(User.getById("adminUser", true).impersonate2())) {
            assertEquals(FormValidation.Kind.ERROR, config.doCheckBaseUrl("not-a-url").kind);
            assertEquals(FormValidation.Kind.WARNING, config.doCheckBaseUrl("").kind);
            assertEquals(FormValidation.Kind.OK, config.doCheckBaseUrl("https://api.openai.com/v1").kind);
        }
    }

    @Test
    void doCheckBaseUrlMakesNoNetworkCallForAdministrator(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("adminUser"));

        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();

        try (ACLContext ctx = ACL.as2(User.getById("adminUser", true).impersonate2())) {
            // ".invalid" is reserved by RFC 2606 to never resolve. If doCheckBaseUrl ever
            // attempted to connect to the value being validated, this would hang or fail slowly
            // instead of returning immediately from pure string checks.
            long startNanos = System.nanoTime();
            FormValidation result = config.doCheckBaseUrl("https://this-host-should-never-be-contacted.invalid");
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            assertEquals(FormValidation.Kind.OK, result.kind);
            assertTrue(
                    elapsedMillis < 2000,
                    "doCheckBaseUrl must not attempt a network call; took " + elapsedMillis + "ms");
        }
    }

    @Test
    void doCheckBaseUrlCsrfSuppressionIsNarrowlyScoped() throws java.io.IOException {
        // @SuppressWarnings has SOURCE retention (discarded by javac), which is exactly what
        // the Jenkins CodeQL security scan needs since it analyzes source, not bytecode - but
        // it also means this can't be verified via reflection at runtime like a normal
        // annotation. Scan the source text itself instead.
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of(
                        "src/main/java/io/jenkins/plugins/changeinvestigator/config/ChangeInvestigatorGlobalConfiguration.java"));

        java.util.regex.Matcher matches = java.util.regex.Pattern.compile(
                        "@SuppressWarnings\\(\"lgtm\\[jenkins/csrf]\"\\)\\s*\\n\\s*public FormValidation doCheckBaseUrl")
                .matcher(source);
        assertTrue(
                matches.find(),
                "doCheckBaseUrl must be immediately preceded by @SuppressWarnings(\"lgtm[jenkins/csrf]\")");

        long suppressWarningsOccurrences = java.util.regex.Pattern.compile("@SuppressWarnings")
                .matcher(source)
                .results()
                .count();
        assertEquals(
                1,
                suppressWarningsOccurrences,
                "exactly one @SuppressWarnings should exist in this file - on doCheckBaseUrl only");
    }

    @Test
    void doFillCredentialsIdItemsDeniesUsersWithoutAdminister(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("readOnlyUser"));

        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();

        try (ACLContext ctx = ACL.as2(User.getById("readOnlyUser", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> config.doFillCredentialsIdItems(""));
        }
    }

    @Test
    void doFillCredentialsIdItemsListsCredentialIdsWithoutExposingSecretValueForAdministrators(JenkinsRule jenkins)
            throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("adminUser"));

        String secretValue = "s3cr3t-token-value";
        StringCredentialsImpl credentials = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "my-cred-id", "description", hudson.util.Secret.fromString(secretValue));
        CredentialsProvider.lookupStores(jenkins.jenkins)
                .iterator()
                .next()
                .addCredentials(com.cloudbees.plugins.credentials.domains.Domain.global(), credentials);

        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();

        try (ACLContext ctx = ACL.as2(User.getById("adminUser", true).impersonate2())) {
            ListBoxModel items = config.doFillCredentialsIdItems("");

            boolean foundCredentialId = false;
            for (ListBoxModel.Option option : items) {
                assertFalse(
                        option.value.contains(secretValue),
                        "doFillCredentialsIdItems must never expose the secret value, only the credential id");
                assertFalse(
                        option.name.contains(secretValue),
                        "doFillCredentialsIdItems must never expose the secret value, only the credential id");
                if ("my-cred-id".equals(option.value)) {
                    foundCredentialId = true;
                }
            }
            assertTrue(foundCredentialId, "expected the configured credential id to appear in the dropdown");
        }
    }

    @Test
    void doFillCredentialsIdItemsAcceptsPostAndDoesNotExposeSecretValueForAdministrator(JenkinsRule jenkins)
            throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("adminUser"));

        String secretValue = "s3cr3t-token-value";
        StringCredentialsImpl credentials = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "my-cred-id", "description", hudson.util.Secret.fromString(secretValue));
        CredentialsProvider.lookupStores(jenkins.jenkins)
                .iterator()
                .next()
                .addCredentials(com.cloudbees.plugins.credentials.domains.Domain.global(), credentials);

        String fillUrl =
                "descriptorByName/" + ChangeInvestigatorGlobalConfiguration.class.getName() + "/fillCredentialsIdItems";
        JenkinsRule.WebClient wc = jenkins.createWebClient().login("adminUser");
        org.htmlunit.WebRequest request =
                new org.htmlunit.WebRequest(new java.net.URL(jenkins.getURL(), fillUrl), org.htmlunit.HttpMethod.POST);
        wc.addCrumb(request);

        org.htmlunit.Page page = wc.getPage(request);
        assertEquals(200, page.getWebResponse().getStatusCode(), "a real POST fill request must still work");
        String body = page.getWebResponse().getContentAsString();
        assertTrue(body.contains("my-cred-id"), "the dropdown must still list the configured credential id");
        assertFalse(
                body.contains(secretValue),
                "the fill response must never contain the secret value, only the credential id");
    }

    @Test
    void doFillCredentialsIdItemsRejectsPlainGetRequests(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("adminUser"));

        String fillUrl =
                "descriptorByName/" + ChangeInvestigatorGlobalConfiguration.class.getName() + "/fillCredentialsIdItems";
        JenkinsRule.WebClient wc = jenkins.createWebClient().login("adminUser");
        wc.getOptions().setThrowExceptionOnFailingStatusCode(true);

        org.htmlunit.FailingHttpStatusCodeException ex = assertThrows(
                org.htmlunit.FailingHttpStatusCodeException.class,
                () -> wc.getPage(new java.net.URL(jenkins.getURL(), fillUrl)),
                "a plain GET must be rejected now that the endpoint requires @POST");
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void doFillCredentialsIdItemsDeniesUnauthorizedUserOverHttpEvenWithPost(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("readOnlyUser"));

        String fillUrl =
                "descriptorByName/" + ChangeInvestigatorGlobalConfiguration.class.getName() + "/fillCredentialsIdItems";
        JenkinsRule.WebClient wc = jenkins.createWebClient().login("readOnlyUser");
        wc.getOptions().setThrowExceptionOnFailingStatusCode(true);
        org.htmlunit.WebRequest request =
                new org.htmlunit.WebRequest(new java.net.URL(jenkins.getURL(), fillUrl), org.htmlunit.HttpMethod.POST);
        wc.addCrumb(request);

        org.htmlunit.FailingHttpStatusCodeException ex = assertThrows(
                org.htmlunit.FailingHttpStatusCodeException.class,
                () -> wc.getPage(request),
                "@POST alone must not bypass the Jenkins.ADMINISTER permission check");
        assertEquals(403, ex.getStatusCode());
    }

    @Test
    void aiProviderConfigCarriesNoSecretMaterial(JenkinsRule jenkins) {
        // Structural guardrail complementing AiProviderConfigTest (which runs without a
        // JenkinsRule): the object actually produced by this descriptor's toProviderConfig()
        // must be free of the api token, since it is a record and would otherwise leak the
        // token through its generated toString()/equals()/hashCode().
        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        config.setCredentialsId("some-credential-id");

        AiProviderConfig providerConfig = config.toProviderConfig();

        assertEquals(5, AiProviderConfig.class.getRecordComponents().length);
        assertFalse(providerConfig.toString().toLowerCase(java.util.Locale.ROOT).contains("token"));
    }
}

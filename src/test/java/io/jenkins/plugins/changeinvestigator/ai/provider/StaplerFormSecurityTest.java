package io.jenkins.plugins.changeinvestigator.ai.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.RootAction;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import java.net.URL;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.access.AccessDeniedException;

/**
 * Adversarial coverage of the Jenkins/Stapler integration layer around AI provider
 * configuration: permission enforcement on the endpoints the config UI calls, and safe handling
 * of a submitted form payload that does not match what the real client-side JS would ever
 * produce (a stale or hand-crafted request rather than a real browser submission).
 */
@WithJenkins
class StaplerFormSecurityTest {

    /**
     * Test-only endpoint that exercises the exact {@code req.bindJSON} databinding path Jenkins
     * itself uses for a submitted {@code f:dropdownDescriptorSelector} field - the same call
     * {@code ChangeInvestigatorGlobalConfiguration#configure} makes for {@code providerConfig},
     * and the same mechanism every {@code Describable} form in Jenkins core relies on - without
     * needing to reverse-engineer or drive the client-side JS that normally builds this JSON.
     * This lets the tests below submit a hand-crafted JSON payload, exactly as a raw HTTP client
     * bypassing the browser entirely could (with a valid crumb), and observe precisely what
     * {@code bindJSON} does with it.
     */
    @TestExtension
    public static final class BindProbe implements RootAction {
        static volatile Object lastResult;
        static volatile Throwable lastFailure;

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return "bci-bind-probe";
        }

        @POST
        public void doBind(StaplerRequest2 req) {
            lastResult = null;
            lastFailure = null;
            try {
                Class<?> target = Class.forName(req.getParameter("targetClass"));
                JSONObject json = JSONObject.fromObject(req.getParameter("rawJson"));
                lastResult = req.bindJSON(target, json);
            } catch (Throwable t) {
                lastFailure = t;
            }
        }
    }

    private void postBind(JenkinsRule jenkins, Class<?> targetClass, String rawJson) throws Exception {
        BindProbe.lastResult = null;
        BindProbe.lastFailure = null;
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        WebRequest request = new WebRequest(new URL(jenkins.getURL(), "bci-bind-probe/bind"), HttpMethod.POST);
        request.setRequestParameters(List.of(
                new NameValuePair("targetClass", targetClass.getName()), new NameValuePair("rawJson", rawJson)));
        wc.addCrumb(request);
        wc.getPage(request);
    }

    // --- Provider type / field-shape mismatch (stale dropdown state, or a crafted request) ---

    @Test
    void mismatchedProviderClassAndFieldsDoesNotCrossContaminateOrCrash(JenkinsRule jenkins) throws Exception {
        // Simulates a crafted raw HTTP request: the "$class" hidden field (see
        // lib/form/class-entry.jelly in Jenkins core) says AWS Bedrock, as if the dropdown were
        // still on that selection, but the actual submitted fields are shaped like an OpenAI
        // submission (model/credentialsId/baseUrl) with no "region" at all - e.g. a user switched
        // the provider dropdown client-side and a stale/replayed request still names the old
        // class, or an attacker crafts this directly with a valid crumb.
        String json = JSONObject.fromObject(Map.of(
                        "$class", BedrockProviderConfig.class.getName(),
                        "model", "gpt-4o-mini",
                        "credentialsId", "some-openai-cred",
                        "baseUrl", "https://sneaky.example.test"))
                .toString();

        postBind(jenkins, AiProviderConfig.class, json);

        assertNull(
                BindProbe.lastFailure,
                "a provider/field-shape mismatch must never surface as a server error: " + BindProbe.lastFailure);
        assertInstanceOf(
                BedrockProviderConfig.class,
                BindProbe.lastResult,
                "the $class hint must still win - no cross-provider hybrid object");
        BedrockProviderConfig bound = (BedrockProviderConfig) BindProbe.lastResult;
        assertNull(
                bound.getRegion(),
                "the required Bedrock field genuinely missing from the payload must bind to null, "
                        + "not silently borrow an OpenAI-shaped field");
        assertEquals(
                "gpt-4o-mini",
                bound.getModel(),
                "the coincidentally-named 'model' field is legitimately shared across provider shapes");
        assertNull(
                bound.getEndpointUrl(),
                "the unmatched 'baseUrl' key (Bedrock has no such setter) must be silently ignored");

        // The resulting (deliberately-incomplete) config must fail *safely* when used, not throw -
        // proving downstream code (Test Connection) tolerates this shape too.
        FormValidation result = bound.testConnection(new ObjectMapper(), 5);
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    // --- Blank optional fields must deserialize to null/blank, not some Stapler sentinel ---

    @Test
    void blankOptionalBedrockFieldsBindToBlankNotASentinel(JenkinsRule jenkins) throws Exception {
        JSONObject json = new JSONObject();
        json.put("$class", BedrockProviderConfig.class.getName());
        json.put("region", "us-east-1");
        json.put("model", "anthropic.claude-3-sonnet");
        json.put("endpointUrl", ""); // exactly what a blank text input submits
        json.put("roleArn", "");
        json.put("credentialsId", "");

        postBind(jenkins, AiProviderConfig.class, json.toString());

        assertNull(BindProbe.lastFailure, "blank optional fields must never fail binding: " + BindProbe.lastFailure);
        BedrockProviderConfig bound = (BedrockProviderConfig) BindProbe.lastResult;

        // Whichever representation Stapler produces (null or ""), the provider's own blank
        // checks (BedrockProviderConfig#resolveCredentialsProvider, BedrockProvider's endpoint
        // handling) must treat it as absent - checked structurally rather than pinning an exact
        // null-vs-empty-string choice that is a Stapler implementation detail.
        assertTrue(bound.getEndpointUrl() == null || bound.getEndpointUrl().isBlank());
        assertTrue(bound.getRoleArn() == null || bound.getRoleArn().isBlank());
        assertNull(
                bound.resolveCredentialsProvider(),
                "a blank credentialsId must resolve to null, not attempt a lookup for an empty ID");

        assertDoesNotThrow(() -> bound.createProvider(new ObjectMapper(), 5));
    }

    // --- Malformed numeric field must not surface as an uncontrolled server error ---

    @Test
    void malformedNumericTimeoutFieldDoesNotCrashBinding(JenkinsRule jenkins) throws Exception {
        // Deliberately submits through the real "/configure" form (not the generic BindProbe
        // above) - ChangeInvestigatorGlobalConfiguration#configure's req.bindJSON(this, json) is
        // a different call site (instance-bind) than BindProbe's req.bindJSON(Class, json)
        // (fresh-instance construction), and only the former is what Jenkins itself actually
        // invokes when this singleton's config form is saved, so this is the only way to
        // exercise ChangeInvestigatorGlobalConfiguration's own bindJSON try/catch for real.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        org.htmlunit.html.HtmlPage page = wc.goTo("configure");
        org.htmlunit.html.HtmlForm form = page.getFormByName("config");
        form.getInputByName("_.timeoutSeconds").setValue("not-a-number");

        org.htmlunit.html.HtmlPage result = jenkins.submit(form);

        // Whatever Stapler/JSON-lib does with a non-numeric value bound to a primitive int
        // field, it must not be an opaque failure that surfaces to the admin as Jenkins' generic
        // error page - the exact class of bug (AWS Bedrock's Test Connection crash) this
        // hardening pass exists to close off.
        int status = result.getWebResponse().getStatusCode();
        assertTrue(
                status < 500,
                "submitting a non-numeric timeout value must not crash the server (got HTTP " + status + ")");
    }

    // --- Permission enforcement: doTestConnection ---

    @Test
    void nonAdminCannotCallDoTestConnection(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("restrictedUser"));

        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        config.setProviderConfig(new OpenAiCompatibleProviderConfig("http://localhost:1/v1", "m", null));

        try (ACLContext ctx =
                ACL.as2(hudson.model.User.getById("restrictedUser", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, config::doTestConnection);
        }
    }

    // --- Permission enforcement: doFillCredentialsIdItems, all 7 providers ---

    @Test
    void nonAdminCannotFillAnyProviderCredentialsDropdown(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("restrictedUser"));

        try (ACLContext ctx =
                ACL.as2(hudson.model.User.getById("restrictedUser", true).impersonate2())) {
            assertThrows(
                    AccessDeniedException.class,
                    () -> new OpenAiProviderConfig.DescriptorImpl().doFillCredentialsIdItems(null),
                    "OpenAI");
            assertThrows(
                    AccessDeniedException.class,
                    () -> new OpenAiCompatibleProviderConfig.DescriptorImpl().doFillCredentialsIdItems(null),
                    "Generic OpenAI-compatible");
            assertThrows(
                    AccessDeniedException.class,
                    () -> new AnthropicProviderConfig.DescriptorImpl().doFillCredentialsIdItems(null),
                    "Anthropic");
            assertThrows(
                    AccessDeniedException.class,
                    () -> new AzureOpenAiProviderConfig.DescriptorImpl().doFillCredentialsIdItems(null),
                    "Azure OpenAI");
            assertThrows(
                    AccessDeniedException.class,
                    () -> new GeminiProviderConfig.DescriptorImpl().doFillCredentialsIdItems(null),
                    "Gemini");
            assertThrows(
                    AccessDeniedException.class,
                    () -> new OllamaProviderConfig.DescriptorImpl().doFillCredentialsIdItems(null),
                    "Ollama");
            assertThrows(
                    AccessDeniedException.class,
                    () -> new BedrockProviderConfig.DescriptorImpl().doFillCredentialsIdItems(null),
                    "Bedrock");
        }
    }

    // --- doCheck* fail safely (never leak validation detail) for a non-admin, validate for an admin ---

    @Test
    void nonAdminGetsSilentOkFromDoCheckRegardlessOfValueButAdminGetsRealValidation(JenkinsRule jenkins)
            throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("restrictedUser")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("adminUser"));

        BedrockProviderConfig.DescriptorImpl bedrockDescriptor = new BedrockProviderConfig.DescriptorImpl();

        try (ACLContext ctx =
                ACL.as2(hudson.model.User.getById("restrictedUser", true).impersonate2())) {
            // A non-admin gets a silent "ok" no matter how invalid the value is - this must never
            // leak validation detail (or even confirm/deny that a field is wrong) to someone who
            // cannot configure the plugin in the first place.
            assertEquals(FormValidation.Kind.OK, bedrockDescriptor.doCheckRegion("").kind);
            assertEquals(FormValidation.Kind.OK, bedrockDescriptor.doCheckRegion(null).kind);
        }

        try (ACLContext ctx =
                ACL.as2(hudson.model.User.getById("adminUser", true).impersonate2())) {
            assertEquals(
                    FormValidation.Kind.ERROR,
                    bedrockDescriptor.doCheckRegion("").kind,
                    "an admin must see the real validation result for a blank required field");
            assertEquals(FormValidation.Kind.OK, bedrockDescriptor.doCheckRegion("us-east-1").kind);
        }
    }

    // --- doCheck* never throws for a plausible malformed input, across every declared validator ---

    @Test
    void doCheckMethodsNeverThrowForMalformedInput(JenkinsRule jenkins) {
        String[] plausibleMalformedValues = {
            null, "", "   ", "not a url", "javascript:alert(1)", "http://", "ftp://evil.example.test", "a".repeat(5000)
        };

        BedrockProviderConfig.DescriptorImpl bedrock = new BedrockProviderConfig.DescriptorImpl();
        AzureOpenAiProviderConfig.DescriptorImpl azure = new AzureOpenAiProviderConfig.DescriptorImpl();
        OllamaProviderConfig.DescriptorImpl ollama = new OllamaProviderConfig.DescriptorImpl();
        OpenAiCompatibleProviderConfig.DescriptorImpl compatible = new OpenAiCompatibleProviderConfig.DescriptorImpl();

        for (String value : plausibleMalformedValues) {
            assertDoesNotThrow(() -> bedrock.doCheckRegion(value));
            assertDoesNotThrow(() -> bedrock.doCheckEndpointUrl(value));
            assertDoesNotThrow(() -> bedrock.doCheckRoleArn(value));
            assertDoesNotThrow(() -> azure.doCheckEndpoint(value));
            assertDoesNotThrow(() -> ollama.doCheckBaseUrl(value));
            assertDoesNotThrow(() -> compatible.doCheckBaseUrl(value));
        }
    }
}

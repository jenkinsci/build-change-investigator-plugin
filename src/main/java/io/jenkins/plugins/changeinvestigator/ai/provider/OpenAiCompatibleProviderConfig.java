package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Any endpoint that speaks the OpenAI "chat completions" HTTP shape - OpenRouter, LiteLLM,
 * vLLM, LM Studio, and similar gateways/proxies not otherwise listed as a first-class provider.
 * This is the direct successor to the plugin's original (pre-multi-provider) configuration:
 * an arbitrary base URL, a free-text model, and a credential.
 *
 * <p>Unlike {@link OpenAiProviderConfig}, the credential here is optional: many self-hosted
 * OpenAI-compatible servers (a local proxy with no auth in front of it, for instance) never
 * check the {@code Authorization} header at all. Existing saved configurations always had a
 * credential set, so this relaxation is additive and does not change their behavior - see
 * {@link io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration}
 * for the migration that produces instances of this class from the plugin's pre-1.x config
 * format.
 */
public class OpenAiCompatibleProviderConfig extends AiProviderConfig {

    private static final long serialVersionUID = 1L;

    private final String baseUrl;
    private final String model;
    private final String credentialsId;

    @DataBoundConstructor
    public OpenAiCompatibleProviderConfig(String baseUrl, String model, String credentialsId) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.credentialsId = credentialsId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public String getModel() {
        return model;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @Override
    public AiProvider createProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        String token = resolveApiToken();
        return new OpenAiChatCompletionsProvider(
                "the configured OpenAI-compatible endpoint", baseUrl, model, token, timeoutSeconds, objectMapper);
    }

    /** See {@link OpenAiProviderConfig#resolveApiToken()} - identical guarantee, no secret retained. */
    String resolveApiToken() {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(credentialsId));
        return credentials == null ? null : credentials.getSecret().getPlainText();
    }

    @Extension
    public static final class DescriptorImpl extends AiProviderConfigDescriptor {
        @Override
        public String getDisplayName() {
            return "Generic OpenAI-compatible";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins jenkins = Jenkins.get();
            jenkins.checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2, jenkins, StringCredentials.class, List.of(), CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        // Read-only form validation: makes no network request, mutates no state. Same
        // GET-style-with-permission-fallback pattern as the plugin's other doCheck methods.
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckBaseUrl(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Base URL is required.");
            }
            return (value.startsWith("http://") || value.startsWith("https://"))
                    ? FormValidation.ok()
                    : FormValidation.error("Must be a full URL starting with http:// or https://.");
        }
    }
}

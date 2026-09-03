package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Anthropic Claude, via the native Messages API - see {@link AnthropicProvider} for the wire
 * contract. Not routed through the OpenAI-compatible adapter.
 */
public class AnthropicProviderConfig extends AiProviderConfig {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_MODEL = "claude-sonnet-5";

    private final String model;
    private final String credentialsId;

    @DataBoundConstructor
    public AnthropicProviderConfig(String model, String credentialsId) {
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        this.credentialsId = credentialsId;
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
        return new AnthropicProvider(model, resolveApiKey(), timeoutSeconds, objectMapper);
    }

    /** See {@link OpenAiProviderConfig#resolveApiToken()} - identical guarantee, no secret retained. */
    String resolveApiKey() {
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
            return "Anthropic Claude";
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
    }
}

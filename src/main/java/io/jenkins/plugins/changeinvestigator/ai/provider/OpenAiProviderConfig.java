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
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * OpenAI itself. Deliberately minimal - just a model and a credential - with the API base URL
 * defaulted internally to {@code https://api.openai.com/v1} rather than asked of every user, on
 * the theory that someone choosing "OpenAI" from the provider dropdown almost always means the
 * real OpenAI API. An advanced override is still available for edge cases (a private OpenAI
 * network endpoint, a regional routing layer, etc.) without cluttering the common path.
 */
public class OpenAiProviderConfig extends AiProviderConfig {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final String model;
    private final String credentialsId;
    private String baseUrl;

    @DataBoundConstructor
    public OpenAiProviderConfig(String model, String credentialsId) {
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

    public String getBaseUrl() {
        return baseUrl;
    }

    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    private String effectiveBaseUrl() {
        return (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
    }

    @Override
    public AiProvider createProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        String token = resolveApiToken();
        return new OpenAiChatCompletionsProvider(
                "OpenAI", effectiveBaseUrl(), model, token, timeoutSeconds, objectMapper);
    }

    /**
     * Resolves the configured credential to a plaintext token. Never logs or persists the
     * value; the result is passed straight into {@link OpenAiChatCompletionsProvider} and never
     * retained here, matching the guarantee the plugin already made before this class existed.
     */
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
            return "OpenAI";
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

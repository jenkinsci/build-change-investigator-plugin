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
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Azure OpenAI, using the classic dated-{@code api-version} REST surface. See
 * {@link AzureOpenAiProvider} for the wire contract and why the generic OpenAI-compatible
 * adapter cannot express it.
 */
public class AzureOpenAiProviderConfig extends AiProviderConfig {

    private static final long serialVersionUID = 1L;

    /** Last GA (non-preview) dated api-version for chat completions as of this plugin release. */
    public static final String DEFAULT_API_VERSION = "2024-10-21";

    private final String endpoint;
    private final String deploymentName;
    private final String credentialsId;
    private String apiVersion;

    @DataBoundConstructor
    public AzureOpenAiProviderConfig(String endpoint, String deploymentName, String credentialsId) {
        this.endpoint = endpoint;
        this.deploymentName = deploymentName;
        this.credentialsId = credentialsId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    @DataBoundSetter
    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    private String effectiveApiVersion() {
        return (apiVersion == null || apiVersion.isBlank()) ? DEFAULT_API_VERSION : apiVersion;
    }

    /** The deployment name doubles as the model identifier for display, since it determines the model on Azure's side. */
    @Override
    public String getModel() {
        return deploymentName;
    }

    @Override
    public AiProvider createProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        return new AzureOpenAiProvider(
                endpoint, deploymentName, effectiveApiVersion(), resolveApiKey(), timeoutSeconds, objectMapper);
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
            return "Azure OpenAI";
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

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckEndpoint(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Endpoint is required, e.g. https://<resource>.openai.azure.com");
            }
            return (value.startsWith("http://") || value.startsWith("https://"))
                    ? FormValidation.ok()
                    : FormValidation.error("Must be a full URL starting with http:// or https://.");
        }
    }
}

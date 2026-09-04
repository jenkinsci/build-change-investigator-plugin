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
 * A local (or self-hosted) Ollama instance, via its OpenAI-compatibility layer
 * ({@code {baseUrl}/chat/completions}). No Jenkins credential is required - Ollama itself does
 * not check the {@code Authorization} header - but one may optionally be selected for a
 * reverse-proxied Ollama setup that adds its own auth in front, in which case it is sent as a
 * Bearer token exactly like every other OpenAI-compatible provider in this plugin.
 *
 * <p>There is deliberately no hardcoded default of {@code http://localhost:11434/v1}: on a
 * containerized Jenkins controller or agent, "localhost" inside the container is <b>not</b> the
 * Docker host running Ollama, and defaulting to it would silently fail in exactly the setup
 * most users running Ollama actually have. The base URL is required input instead, with
 * guidance in the README on the container-networking values that typically work (e.g.
 * {@code http://host.docker.internal:11434/v1} for Docker Desktop, or the host's LAN IP).
 */
public class OllamaProviderConfig extends AiProviderConfig {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_MODEL = "llama3.1";

    private final String baseUrl;
    private final String model;
    private String credentialsId;

    @DataBoundConstructor
    public OllamaProviderConfig(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
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

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @Override
    public AiProvider createProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        return new OpenAiChatCompletionsProvider(
                "Ollama", baseUrl, model, resolveApiToken(), timeoutSeconds, objectMapper);
    }

    /** See {@link OpenAiProviderConfig#resolveApiToken()} - identical guarantee, no secret retained. Optional here: {@code null}/blank means no Authorization header is sent, matching Ollama's own no-auth-required behavior. */
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
            return "Ollama";
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

        // Read-only; same GET-style-with-permission-fallback pattern as the plugin's other
        // doCheck methods (see OpenAiCompatibleProviderConfig.DescriptorImpl#doCheckBaseUrl).
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckBaseUrl(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error(
                        "Base URL is required - e.g. http://host.docker.internal:11434/v1 for Docker Desktop, "
                                + "or http://<ollama-host>:11434/v1 for a remote/LAN instance. \"localhost\" "
                                + "refers to the Jenkins controller/agent container, not necessarily where "
                                + "Ollama is running.");
            }
            return (value.startsWith("http://") || value.startsWith("https://"))
                    ? FormValidation.ok()
                    : FormValidation.error("Must be a full URL starting with http:// or https://.");
        }
    }
}

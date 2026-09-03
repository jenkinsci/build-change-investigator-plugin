package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * A local (or self-hosted) Ollama instance, via its OpenAI-compatibility layer
 * ({@code {baseUrl}/chat/completions}). No Jenkins credential is required or offered - Ollama
 * does not check the {@code Authorization} header at all, so requiring one here would only add
 * friction with no security benefit.
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

    @Override
    public AiProvider createProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        return new OpenAiChatCompletionsProvider("Ollama", baseUrl, model, null, timeoutSeconds, objectMapper);
    }

    @Extension
    public static final class DescriptorImpl extends AiProviderConfigDescriptor {
        @Override
        public String getDisplayName() {
            return "Ollama";
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

package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.io.Serializable;

/**
 * One selectable AI provider in the "AI Provider" dropdown (rendered via
 * {@code f:dropdownDescriptorSelector}), holding exactly the non-secret, provider-specific
 * configuration that provider needs (endpoint, deployment, region, model, credential ID, ...).
 * Cross-cutting settings that apply the same way to every provider - temperature, timeout,
 * max log context characters - live once on {@code ChangeInvestigatorGlobalConfiguration}
 * instead of being duplicated onto each subclass.
 *
 * <p>Never holds a resolved secret: subclasses store only a Jenkins credential ID (a
 * non-secret reference) and resolve it to plaintext at call time in {@link #createProvider},
 * the same pattern the plugin already used before this provider abstraction existed.
 */
public abstract class AiProviderConfig extends AbstractDescribableImpl<AiProviderConfig> implements Serializable {

    private static final long serialVersionUID = 1L;

    static final String TEST_SYSTEM_PROMPT = "Respond with exactly: {\"ok\":true}";
    static final String TEST_USER_PROMPT = "Respond with exactly: {\"ok\":true}";

    /** The model identifier this configuration will request, for display purposes. */
    public abstract String getModel();

    /**
     * Builds a provider bound to this configuration's resolved settings, ready to make one (or
     * more) {@link AiProvider#chatCompletion} calls. Resolving the credential happens inside
     * this method, not before it, so the plaintext secret's lifetime is limited to the call
     * that actually needs it.
     *
     * @param objectMapper shared Jackson mapper for building/parsing request and response JSON
     * @param timeoutSeconds administrator-configured connect/request timeout (a global,
     *     not per-provider, setting - see {@link
     *     io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration})
     */
    public abstract AiProvider createProvider(ObjectMapper objectMapper, int timeoutSeconds);

    /**
     * Sends a minimal, low-cost request to verify this configuration works. Deliberately does
     * <b>not</b> send any real build evidence - only a fixed instruction-only exchange - so
     * testing a connection never depends on (or leaks) actual investigation data.
     */
    public FormValidation testConnection(ObjectMapper objectMapper, int timeoutSeconds) {
        try {
            createProvider(objectMapper, timeoutSeconds)
                    .chatCompletion(new AiAnalysisRequest(TEST_SYSTEM_PROMPT, TEST_USER_PROMPT, 0.0));
            return FormValidation.ok("Connection succeeded.");
        } catch (AiAnalysisException e) {
            return FormValidation.error("Connection failed (" + e.getKind() + "): " + e.getMessage());
        } catch (RuntimeException e) {
            return FormValidation.error("Connection failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public abstract static class AiProviderConfigDescriptor extends Descriptor<AiProviderConfig> {}
}

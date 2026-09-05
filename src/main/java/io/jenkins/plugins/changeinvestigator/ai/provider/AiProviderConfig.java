package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import io.jenkins.plugins.changeinvestigator.ai.SafeAiFailure;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(AiProviderConfig.class.getName());

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
            return FormValidation.error(
                    "Connection failed (" + e.getKind() + "): " + SafeAiFailure.describe(e.getKind()));
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Unexpected Test Connection error ({0})",
                    e.getClass().getName());
            return FormValidation.error(SafeAiFailure.describe(AiAnalysisException.Kind.UNKNOWN_PROVIDER_ERROR));
        } catch (LinkageError e) {
            // This is the outermost Jenkins-facing safety boundary for Test Connection: an Error
            // (not an Exception) from a provider SDK failing to link at runtime - most notably
            // AWS Bedrock's SDK if a conflicting AWS SDK version is visible through another
            // installed plugin's classloader - is a sibling of Exception under Throwable, so it
            // is not caught by either clause above. Without this clause it propagates straight
            // through Stapler and renders Jenkins' generic "Oops" page instead of a safe,
            // in-form validation error.
            LOGGER.log(
                    Level.WARNING,
                    "AI provider failed to load/link correctly during Test Connection ({0})",
                    e.getClass().getName());
            return FormValidation.error(SafeAiFailure.describe(AiAnalysisException.Kind.UNKNOWN_PROVIDER_ERROR));
        }
    }

    public abstract static class AiProviderConfigDescriptor extends Descriptor<AiProviderConfig> {}
}

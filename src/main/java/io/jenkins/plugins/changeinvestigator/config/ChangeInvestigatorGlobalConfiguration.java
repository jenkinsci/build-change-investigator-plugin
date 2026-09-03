package io.jenkins.plugins.changeinvestigator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.BulkChange;
import hudson.Extension;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.ai.provider.AiProviderConfig;
import io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Administrator-controlled, instance-wide settings for AI-assisted regression analysis.
 * Deterministic evidence collection (the "OBSERVED EVIDENCE" section of an investigation)
 * never depends on any setting here; these settings only affect the optional "AI ASSESSMENT"
 * section.
 *
 * <p>Which AI provider is used, and that provider's own settings (endpoint, deployment,
 * region, credential, ...), live on {@link #providerConfig} - one of the
 * {@link AiProviderConfig} subclasses selected from the "AI Provider" dropdown. Settings that
 * apply identically regardless of provider - temperature, timeout, max log context characters -
 * stay here as global "Advanced settings" rather than being duplicated onto every provider.
 */
@Extension
public class ChangeInvestigatorGlobalConfiguration extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(ChangeInvestigatorGlobalConfiguration.class.getName());

    public static final int DEFAULT_MAX_LOG_CONTEXT_CHARS = 8_000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final double DEFAULT_TEMPERATURE = 0.2;

    private boolean aiEnabled;
    private AiProviderConfig providerConfig;
    private int maxLogContextChars = DEFAULT_MAX_LOG_CONTEXT_CHARS;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private double temperature = DEFAULT_TEMPERATURE;

    // Pre-multi-provider configuration format, kept only so XStream can still populate these
    // from a config.xml saved by an older release; readResolve() below migrates them into
    // providerConfig on load and they are never read again afterward. Do not remove without a
    // deprecation cycle - doing so would silently drop AI configuration for every existing
    // installation on upgrade.
    @Deprecated
    private transient String baseUrl;

    @Deprecated
    private transient String model;

    @Deprecated
    private transient String credentialsId;

    public ChangeInvestigatorGlobalConfiguration() {
        load();
    }

    public static ChangeInvestigatorGlobalConfiguration get() {
        return GlobalConfiguration.all().get(ChangeInvestigatorGlobalConfiguration.class);
    }

    /**
     * Migrates a pre-multi-provider {@code config.xml} (flat {@code baseUrl}/{@code model}/
     * {@code credentialsId} fields directly on this class) into an equivalent
     * {@link OpenAiCompatibleProviderConfig} - the closest match to what those flat fields
     * always meant: an arbitrary OpenAI-compatible endpoint. Runs on load, before any AI call
     * could possibly be triggered, and makes no network call itself. The migrated credential ID
     * is carried over unchanged - the secret it points to lives in the Jenkins Credentials
     * store either way and is never touched by this migration.
     */
    protected Object readResolve() {
        if (providerConfig == null && baseUrl != null && !baseUrl.isBlank()) {
            providerConfig = new OpenAiCompatibleProviderConfig(baseUrl, model, credentialsId);
            LOGGER.log(
                    Level.INFO,
                    "Migrated Build Change Investigator AI configuration from the pre-multi-provider format "
                            + "to a Generic OpenAI-compatible provider.");
        }
        return this;
    }

    /**
     * Binding the whole form calls every {@code @DataBoundSetter}, each of which would
     * otherwise {@code save()} on its own - {@link BulkChange} defers all of those into the
     * single {@code save()} that {@code bc.commit()} performs, so submitting this form persists
     * once instead of once per changed field. Individual setter calls made outside form
     * binding (script console, init scripts) are unaffected and still save immediately, since
     * they run with no {@link BulkChange} in scope.
     */
    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        try (BulkChange bc = new BulkChange(this)) {
            req.bindJSON(this, json);
            bc.commit();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save " + getConfigFile(), e);
        }
        return true;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    @DataBoundSetter
    public void setAiEnabled(boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
        save();
    }

    public AiProviderConfig getProviderConfig() {
        return providerConfig;
    }

    @DataBoundSetter
    public void setProviderConfig(AiProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
        save();
    }

    public int getMaxLogContextChars() {
        return maxLogContextChars;
    }

    @DataBoundSetter
    public void setMaxLogContextChars(int maxLogContextChars) {
        this.maxLogContextChars = maxLogContextChars;
        save();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @DataBoundSetter
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        save();
    }

    public double getTemperature() {
        return temperature;
    }

    @DataBoundSetter
    public void setTemperature(double temperature) {
        this.temperature = temperature;
        save();
    }

    /**
     * Delegates to the selected provider's own lightweight validation call - see
     * {@link AiProviderConfig#testConnection}. Never sends any real build evidence, only a
     * fixed instruction-only exchange, regardless of which provider is selected.
     */
    @POST
    public FormValidation doTestConnection() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (providerConfig == null) {
            return FormValidation.error("Select and configure an AI provider first.");
        }
        return providerConfig.testConnection(
                new ObjectMapper(), timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS);
    }
}

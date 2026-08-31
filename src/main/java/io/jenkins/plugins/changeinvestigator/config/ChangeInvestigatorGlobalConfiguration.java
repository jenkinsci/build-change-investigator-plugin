package io.jenkins.plugins.changeinvestigator.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.BulkChange;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiProviderConfig;
import io.jenkins.plugins.changeinvestigator.ai.OpenAiCompatibleClient;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Administrator-controlled, instance-wide settings for AI-assisted regression analysis.
 * Deterministic evidence collection (the "OBSERVED EVIDENCE" section of an investigation)
 * never depends on any setting here; these settings only affect the optional "AI ASSESSMENT"
 * section.
 */
@Extension
public class ChangeInvestigatorGlobalConfiguration extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(ChangeInvestigatorGlobalConfiguration.class.getName());

    public static final int DEFAULT_MAX_LOG_CONTEXT_CHARS = 8_000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final double DEFAULT_TEMPERATURE = 0.2;

    private boolean aiEnabled;
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "gpt-4o-mini";
    private String credentialsId;
    private int maxLogContextChars = DEFAULT_MAX_LOG_CONTEXT_CHARS;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private double temperature = DEFAULT_TEMPERATURE;

    public ChangeInvestigatorGlobalConfiguration() {
        load();
    }

    public static ChangeInvestigatorGlobalConfiguration get() {
        return GlobalConfiguration.all().get(ChangeInvestigatorGlobalConfiguration.class);
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

    public String getBaseUrl() {
        return baseUrl;
    }

    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        save();
    }

    public String getModel() {
        return model;
    }

    @DataBoundSetter
    public void setModel(String model) {
        this.model = model;
        save();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
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
     * Resolves the configured credential to a plaintext token. Never logs or persists the
     * value, and never stores it in {@link AiProviderConfig} or any other record/field with
     * generated {@code toString()}/{@code equals()}/{@code hashCode()} - callers must pass it
     * directly to the one place that needs it ({@link OpenAiCompatibleClient}) and let it go
     * out of scope immediately after.
     *
     * <p>Uses {@link Secret#getPlainText()} rather than the deprecated
     * {@link Secret#toString(Secret)} helper - despite the similar name, {@code Secret}'s own
     * {@code toString()} (and the static helper that wraps it) also returns the plaintext
     * value, not an encrypted/opaque form, which is easy to assume incorrectly.
     */
    public String resolveApiToken() {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), hudson.security.ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(credentialsId));
        return credentials == null ? null : credentials.getSecret().getPlainText();
    }

    /** Non-secret settings only - see {@link AiProviderConfig} for why the token is excluded. */
    public AiProviderConfig toProviderConfig() {
        return new AiProviderConfig(baseUrl, model, timeoutSeconds, temperature, maxLogContextChars);
    }

    /**
     * Populates the credential dropdown.
     *
     * <p>Marked {@code @POST}: the credentials-plugin {@code <c:select>} tag used in
     * {@code config.jelly} does not implement its own AJAX fill mechanism - its Jelly tag
     * definition (confirmed on both {@code master} and the latest released
     * {@code credentials-2.6.2}) renders Jenkins core's own {@code <f:select>} internally for
     * the actual dropdown, and core's fill mechanism ({@code updateListBox} in
     * {@code lib/form/select/select.js}) unconditionally issues the fill request as a
     * crumb-protected HTTP POST - there is no GET path for it. So this annotation matches what
     * the browser already sends; it does not change the dropdown's behavior, only rejects
     * requests that were never coming from it in the first place.
     */
    @POST
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
        Jenkins jenkins = Jenkins.get();
        jenkins.checkPermission(Jenkins.ADMINISTER);
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        hudson.security.ACL.SYSTEM2,
                        jenkins,
                        StringCredentials.class,
                        List.of(),
                        CredentialsMatchers.always())
                .includeCurrentValue(credentialsId);
    }

    @POST
    public FormValidation doTestConnection(
            @QueryParameter String baseUrl,
            @QueryParameter String model,
            @QueryParameter String credentialsId,
            @QueryParameter int timeoutSeconds) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        if (baseUrl == null || baseUrl.isBlank()) {
            return FormValidation.error("Base URL is required.");
        }
        if (credentialsId == null || credentialsId.isBlank()) {
            return FormValidation.error("Select a credential containing the API token first.");
        }

        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), hudson.security.ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(credentialsId));
        if (credentials == null) {
            return FormValidation.error("The selected credential could not be found.");
        }

        AiProviderConfig testConfig = new AiProviderConfig(
                baseUrl,
                (model == null || model.isBlank()) ? "gpt-4o-mini" : model,
                timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds,
                0.0,
                200);
        try {
            new OpenAiCompatibleClient(testConfig, credentials.getSecret().getPlainText(), new ObjectMapper())
                    .chatCompletion("Respond with exactly: {\"ok\":true}", "Respond with exactly: {\"ok\":true}");
            return FormValidation.ok("Connection succeeded.");
        } catch (AiAnalysisException e) {
            LOGGER.log(Level.FINE, "Test connection failed", e);
            return FormValidation.error("Connection failed (" + e.getKind() + "): " + e.getMessage());
        }
    }

    /*
     * doCheckTimeoutSeconds and doCheckMaxLogContextChars were removed: both fields are plain
     * positive integers with no domain-specific rule beyond "> 0", which the Jelly-side
     * clazz="positive-number-required" client-side validation (see config.jelly) now enforces
     * directly - see https://www.jenkins.io/doc/developer/security/form-validation/ and
     * core's lib/form/number.jelly. doCheckBaseUrl is kept below because URL-shape validation
     * ("starts with http:// or https://") is not one of the built-in clazz keywords.
     */

    public FormValidation doCheckBaseUrl(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.warning("Required if AI analysis is enabled.");
        }
        return (value.startsWith("http://") || value.startsWith("https://"))
                ? FormValidation.ok()
                : FormValidation.error("Must be a full URL starting with http:// or https://.");
    }
}

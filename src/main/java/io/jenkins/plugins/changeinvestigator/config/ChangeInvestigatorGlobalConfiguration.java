package io.jenkins.plugins.changeinvestigator.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiProviderConfig;
import io.jenkins.plugins.changeinvestigator.ai.OpenAiCompatibleClient;
import java.util.Arrays;
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
    private String additionalHeaders = "";

    public ChangeInvestigatorGlobalConfiguration() {
        load();
    }

    public static ChangeInvestigatorGlobalConfiguration get() {
        return GlobalConfiguration.all().get(ChangeInvestigatorGlobalConfiguration.class);
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
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

    public String getAdditionalHeaders() {
        return additionalHeaders;
    }

    @DataBoundSetter
    public void setAdditionalHeaders(String additionalHeaders) {
        this.additionalHeaders = additionalHeaders == null ? "" : additionalHeaders;
        save();
    }

    /** Resolves the configured credential to a plaintext token. Never logs or persists the value. */
    public String resolveApiToken() {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), hudson.security.ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(credentialsId));
        return credentials == null ? null : Secret.toString(credentials.getSecret());
    }

    public AiProviderConfig toProviderConfig() {
        List<String> headerLines = Arrays.asList(additionalHeaders.split("\\R"));
        return new AiProviderConfig(
                baseUrl,
                model,
                resolveApiToken(),
                timeoutSeconds,
                temperature,
                AiProviderConfig.parseHeaderLines(headerLines),
                maxLogContextChars);
    }

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
            @QueryParameter int timeoutSeconds,
            @QueryParameter String additionalHeaders) {
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
                Secret.toString(credentials.getSecret()),
                timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds,
                0.0,
                AiProviderConfig.parseHeaderLines(Arrays.asList(
                        additionalHeaders == null ? new String[0] : additionalHeaders.split("\\R"))),
                200);
        try {
            new OpenAiCompatibleClient(testConfig, new ObjectMapper()).chatCompletion(
                    "Respond with exactly: {\"ok\":true}",
                    "Respond with exactly: {\"ok\":true}");
            return FormValidation.ok("Connection succeeded.");
        } catch (AiAnalysisException e) {
            LOGGER.log(Level.FINE, "Test connection failed", e);
            return FormValidation.error("Connection failed (" + e.getKind() + "): " + e.getMessage());
        }
    }

    public FormValidation doCheckTimeoutSeconds(@QueryParameter int value) {
        return value > 0 ? FormValidation.ok() : FormValidation.error("Timeout must be a positive number of seconds.");
    }

    public FormValidation doCheckMaxLogContextChars(@QueryParameter int value) {
        return value > 0 ? FormValidation.ok() : FormValidation.error("Must be a positive number of characters.");
    }

    public FormValidation doCheckBaseUrl(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.warning("Required if AI analysis is enabled.");
        }
        return (value.startsWith("http://") || value.startsWith("https://"))
                ? FormValidation.ok()
                : FormValidation.error("Must be a full URL starting with http:// or https://.");
    }
}

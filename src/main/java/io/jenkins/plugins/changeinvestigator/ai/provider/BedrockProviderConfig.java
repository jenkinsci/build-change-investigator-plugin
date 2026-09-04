package io.jenkins.plugins.changeinvestigator.ai.provider;

import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
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
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * AWS Bedrock, via the Runtime {@code Converse} API - see {@link BedrockProvider} for the wire
 * contract. The AWS credential is optional and deliberately not a plain-text access
 * key/secret pasted into this plugin's own config: when set, it references a Jenkins
 * {@link AmazonWebServicesCredentials} credential (the standard type from the
 * {@code aws-credentials} plugin, backed by Jenkins Credentials storage the same way every
 * other secret in this plugin already is); when left unset, calls fall back to the AWS SDK's
 * default credential provider chain, which is how most enterprise Bedrock setups actually work
 * - an IAM role attached to the Jenkins controller or agent, with no static key material
 * anywhere. An optional Role ARN layers AWS STS {@code AssumeRole} on top of either credential
 * source - see {@link BedrockProvider} for how that's wired without ever hand-signing anything.
 */
public class BedrockProviderConfig extends AiProviderConfig {

    private static final long serialVersionUID = 1L;

    /** e.g. arn:aws:iam::123456789012:role/my-role, arn:aws-us-gov:iam::..., arn:aws-cn:iam::... */
    private static final Pattern ROLE_ARN_PATTERN = Pattern.compile("^arn:aws[a-zA-Z-]*:iam::\\d{12}:role/.+$");

    private final String region;
    private final String model;
    private String credentialsId;
    private String endpointUrl;
    private String roleArn;

    @DataBoundConstructor
    public BedrockProviderConfig(String region, String model) {
        this.region = region;
        this.model = model;
    }

    public String getRegion() {
        return region;
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

    public String getEndpointUrl() {
        return endpointUrl;
    }

    @DataBoundSetter
    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getRoleArn() {
        return roleArn;
    }

    @DataBoundSetter
    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    @Override
    public AiProvider createProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        return new BedrockProvider(region, model, resolveCredentialsProvider(), timeoutSeconds, endpointUrl, roleArn);
    }

    /**
     * Resolves the optional AWS credential to an SDK v2 {@link AwsCredentialsProvider}, or
     * {@code null} to signal "use the SDK's default credential chain" to {@link BedrockProvider}.
     * Never retains the resolved credential itself - only a provider that resolves it lazily at
     * call time, matching the just-in-time resolution pattern used by every other provider
     * config in this package. If {@link #roleArn} is also set, {@link BedrockProvider} uses
     * whatever this returns (or the default chain) only as the source identity for
     * {@code AssumeRole} - the actual Bedrock calls use the resulting temporary credentials.
     */
    AwsCredentialsProvider resolveCredentialsProvider() {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        AmazonWebServicesCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        AmazonWebServicesCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(credentialsId));
        if (credentials == null) {
            return null;
        }
        return () -> credentials.resolveCredentials(null);
    }

    @Extension
    public static final class DescriptorImpl extends AiProviderConfigDescriptor {
        @Override
        public String getDisplayName() {
            return "AWS Bedrock";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins jenkins = Jenkins.get();
            jenkins.checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            jenkins,
                            AmazonWebServicesCredentials.class,
                            List.of(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckRegion(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            return (value == null || value.isBlank())
                    ? FormValidation.error("Region is required, e.g. us-east-1.")
                    : FormValidation.ok();
        }

        /** Optional - blank means "use normal AWS regional endpoint resolution", so blank is valid here. */
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckEndpointUrl(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            return (value.startsWith("http://") || value.startsWith("https://"))
                    ? FormValidation.ok()
                    : FormValidation.error("Must be a full URL starting with http:// or https://.");
        }

        /** Optional - blank means "use the selected credential / default chain directly", so blank is valid here. */
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckRoleArn(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            return ROLE_ARN_PATTERN.matcher(value).matches()
                    ? FormValidation.ok()
                    : FormValidation.error(
                            "Must be a full IAM role ARN, e.g. arn:aws:iam::123456789012:role/my-role-name.");
        }
    }
}

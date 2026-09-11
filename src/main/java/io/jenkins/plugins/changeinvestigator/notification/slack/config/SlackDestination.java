package io.jenkins.plugins.changeinvestigator.notification.slack.config;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import java.util.UUID;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Administrator-approved external disclosure boundary. Contains credential references only. */
public final class SlackDestination extends AbstractDescribableImpl<SlackDestination> {
    private final String id;
    private final String name;
    private final String workspaceLabel;
    private final String workspaceId;
    private final String channelId;
    private final String credentialId;
    private final String scope;
    private final boolean enabled;
    private long generation;
    private boolean verified;
    private String verifiedMentionId = "";

    @DataBoundConstructor
    public SlackDestination(
            String id,
            String name,
            String workspaceLabel,
            String workspaceId,
            String channelId,
            String credentialId,
            String scope,
            boolean enabled) {
        this.id = id == null || id.isBlank()
                ? UUID.randomUUID().toString()
                : UUID.fromString(id).toString();
        this.name = label(name, 80);
        this.workspaceLabel = label(workspaceLabel, 80);
        this.workspaceId = identifier(workspaceId, "T[A-Z0-9]{2,31}");
        this.channelId = identifier(channelId, "[CG][A-Z0-9]{2,31}");
        this.credentialId = label(credentialId, 128);
        this.scope = validScope(scope);
        this.enabled = enabled;
        generation = 1;
    }

    private static String label(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(SafeContent.text(value, max)))
            throw new IllegalArgumentException("Invalid Slack destination field");
        return value;
    }

    private static String identifier(String value, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("Invalid Slack identity");
        return value;
    }

    private static String validScope(String value) {
        String scope = label(value, 512);
        String path = scope.endsWith("/**") ? scope.substring(0, scope.length() - 3) : scope;
        if (path.isBlank()
                || path.startsWith("/")
                || path.endsWith("/")
                || path.contains("//")
                || path.contains("*")
                || path.contains("\\")
                || path.contains("..")) throw new IllegalArgumentException("Use an exact job path or folder/** scope");
        return scope;
    }

    SlackDestination reconciled(SlackDestination previous) {
        SlackDestination copy =
                new SlackDestination(id, name, workspaceLabel, workspaceId, channelId, credentialId, scope, enabled);
        copy.verifiedMentionId = getVerifiedMentionId();
        if (previous != null) {
            boolean semantic = !workspaceId.equals(previous.workspaceId)
                    || !channelId.equals(previous.channelId)
                    || !scope.equals(previous.scope)
                    || enabled != previous.enabled
                    || !getVerifiedMentionId().equals(previous.getVerifiedMentionId());
            copy.generation = semantic ? Math.addExact(previous.generation, 1) : previous.generation;
            copy.verified = !semantic && credentialId.equals(previous.credentialId) && previous.verified;
        }
        return copy;
    }

    SlackDestination verifiedCopy() {
        SlackDestination copy = reconciled(this);
        copy.verified = true;
        return copy;
    }

    SlackDestination afterRetiredGeneration(long retired) {
        SlackDestination copy = reconciled(null);
        copy.generation = Math.addExact(retired, 1);
        return copy;
    }

    SlackDestination nextDisclosureGeneration() {
        SlackDestination copy = reconciled(this);
        copy.generation = Math.addExact(generation, 1);
        return copy;
    }

    public boolean allows(String jobFullName) {
        return scope.endsWith("/**")
                ? jobFullName.startsWith(scope.substring(0, scope.length() - 2))
                : scope.equals(jobFullName);
    }

    @DataBoundSetter
    public void setVerifiedMentionId(String value) {
        verifiedMentionId = value == null || value.isBlank() ? "" : identifier(value, "[UWS][A-Z0-9]{2,31}");
    }

    public String getVerifiedMentionId() {
        return verifiedMentionId == null ? "" : verifiedMentionId;
    }

    public UUID identity() {
        return UUID.fromString(id);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getWorkspaceLabel() {
        return workspaceLabel;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public String getScope() {
        return scope;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getGeneration() {
        return generation;
    }

    public boolean isVerified() {
        return verified;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SlackDestination> {
        @Override
        public String getDisplayName() {
            return "Approved Slack destination";
        }
    }
}

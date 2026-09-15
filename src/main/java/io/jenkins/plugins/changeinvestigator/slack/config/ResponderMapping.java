package io.jenkins.plugins.changeinvestigator.slack.config;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/** An administrator-maintained exact identity mapping with optional workspace verification. */
public final class ResponderMapping extends AbstractDescribableImpl<ResponderMapping> {
    private String id;
    private transient boolean idMigrationNeeded;
    private final String identity;
    private final String slackUser;
    private String workspaceId = "";
    private String workspaceName = "";
    private String displayName = "";
    private String verificationStatus = "NEEDS_VALIDATION";
    private String intendedCredential = "";
    private String intendedFingerprint = "";

    @DataBoundConstructor
    public ResponderMapping(String identity, String slackUser) {
        this.id = java.util.UUID.randomUUID().toString();
        this.identity = bounded(identity, 256);
        this.slackUser = bounded(slackUser, 64);
    }

    public String getId() {
        return id;
    }

    static ResponderMapping update(String id, String identity, String slackUser) {
        ResponderMapping value = new ResponderMapping(identity, slackUser);
        value.id = id;
        return value;
    }

    public String getWorkspaceId() {
        return workspaceId == null ? "" : workspaceId;
    }

    public String getWorkspaceName() {
        return workspaceName == null ? "" : workspaceName;
    }

    public String getDisplayName() {
        return displayName == null ? "" : displayName;
    }

    public String getVerificationStatus() {
        return verificationStatus == null ? "NEEDS_VALIDATION" : verificationStatus;
    }

    String intendedCredential() {
        return intendedCredential == null ? "" : intendedCredential;
    }

    String intendedFingerprint() {
        return intendedFingerprint == null ? "" : intendedFingerprint;
    }

    static ResponderMapping associated(
            ResponderMapping original,
            String workspaceId,
            String workspaceName,
            String name,
            String status,
            String credential,
            String fingerprint) {
        ResponderMapping value = update(original.getId(), original.getIdentity(), original.getSlackUser());
        value.workspaceId = bounded(workspaceId, 64);
        value.workspaceName = bounded(workspaceName, 80);
        value.displayName = bounded(name, 160);
        value.verificationStatus = status;
        value.intendedCredential = bounded(credential, 256);
        value.intendedFingerprint = bounded(fingerprint, 128);
        return value;
    }

    static ResponderMapping verified(ResponderMapping original, String workspaceId, String workspaceName, String name) {
        return associated(
                original,
                workspaceId,
                workspaceName,
                name,
                "VERIFIED",
                original.intendedCredential(),
                original.intendedFingerprint());
    }

    private Object readResolve() {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            id = java.util.UUID.randomUUID().toString();
            idMigrationNeeded = true;
        }
        return this;
    }

    boolean needsIdPersistence() {
        return idMigrationNeeded;
    }

    void markIdPersisted() {
        idMigrationNeeded = false;
    }

    static String bounded(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= limit && trimmed.chars().noneMatch(Character::isISOControl) ? trimmed : "";
    }

    public String getIdentity() {
        return identity == null ? "" : identity;
    }

    public String getSlackUser() {
        return slackUser == null ? "" : slackUser;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResponderMapping mapping
                && identity.equals(mapping.identity)
                && slackUser.equals(mapping.slackUser)
                && getWorkspaceId().equals(mapping.getWorkspaceId())
                && getWorkspaceName().equals(mapping.getWorkspaceName())
                && getDisplayName().equals(mapping.getDisplayName())
                && getVerificationStatus().equals(mapping.getVerificationStatus())
                && intendedCredential().equals(mapping.intendedCredential())
                && intendedFingerprint().equals(mapping.intendedFingerprint());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                identity,
                slackUser,
                getWorkspaceId(),
                getWorkspaceName(),
                getDisplayName(),
                getVerificationStatus(),
                intendedCredential(),
                intendedFingerprint());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ResponderMapping> {
        @Override
        public String getDisplayName() {
            return "Responder mapping";
        }
    }
}

package io.jenkins.plugins.changeinvestigator.slack.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.BulkChange;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Global Slack settings. Individual jobs must separately opt in. */
@Extension
public final class SlackConfiguration extends GlobalConfiguration {
    private boolean enabled;
    private long revision;
    private long mappingRevision;
    private long enabledAtMillis;
    private long modifiedAtMillis = System.currentTimeMillis();
    private String credentialId = "";
    private String verifiedCredentialId = "";
    private String verifiedCredentialFingerprint = "";
    private String verifiedWorkspace = "";
    private String verifiedWorkspaceId = "";
    private long workspaceProofGeneration;
    private String defaultChannel = "";
    private boolean tagResponders;
    private List<ResponderMapping> responderMappings = List.of();
    public static final int MAX_RESPONDER_MAPPINGS = 1000;

    public SlackConfiguration() {
        load();
        if (getResponderMappings().stream().anyMatch(ResponderMapping::needsIdPersistence)) {
            save();
            getResponderMappings().forEach(ResponderMapping::markIdPersisted);
        }
    }

    public static SlackConfiguration get() {
        return GlobalConfiguration.all().get(SlackConfiguration.class);
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized long getRevision() {
        return revision;
    }

    public synchronized long getMappingRevision() {
        return mappingRevision;
    }

    public synchronized long getEnabledAtMillis() {
        return enabledAtMillis;
    }

    public synchronized long getModifiedAtMillis() {
        return modifiedAtMillis;
    }

    public synchronized String getCredentialId() {
        return credentialId == null ? "" : credentialId;
    }

    public synchronized String getDefaultChannel() {
        return defaultChannel == null ? "" : defaultChannel;
    }

    public synchronized String getVerifiedWorkspace() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (verifiedCredentialId == null
                || !verifiedCredentialId.equals(getCredentialId())
                || verifiedCredentialFingerprint == null
                || verifiedCredentialFingerprint.isBlank()) return "";
        String current = SlackTransport.get().credentialFingerprint(getCredentialId());
        return verifiedCredentialFingerprint.equals(current) ? ResponderMapping.bounded(verifiedWorkspace, 80) : "";
    }

    public synchronized String getVerifiedWorkspaceName() {
        return getVerifiedWorkspace();
    }

    public synchronized boolean isWorkspaceVerified() {
        return !getVerifiedWorkspace().isBlank();
    }

    private synchronized boolean recordWorkspaceVerification(
            String submittedCredentialId,
            io.jenkins.plugins.changeinvestigator.slack.transport.ConnectionResult result) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String fingerprint = result.credentialFingerprint();
        if (!result.success()
                || fingerprint == null
                || fingerprint.isBlank()
                || !fingerprint.equals(SlackTransport.get().credentialFingerprint(submittedCredentialId))) return false;
        workspaceProofGeneration++;
        verifiedCredentialId = submittedCredentialId;
        verifiedCredentialFingerprint = fingerprint;
        verifiedWorkspace = ResponderMapping.bounded(result.workspaceName(), 80);
        verifiedWorkspaceId = result.route() == null ? "" : result.route().teamId();
        save();
        scheduleMappingValidation();
        return true;
    }

    public synchronized String getVerifiedWorkspaceId() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return getVerifiedWorkspace().isBlank() || verifiedWorkspaceId == null ? "" : verifiedWorkspaceId;
    }

    public synchronized boolean isMappingWorkspaceCurrent(ResponderMapping mapping) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String current = getVerifiedWorkspaceId();
        return mapping != null && !current.isBlank() && current.equals(mapping.getWorkspaceId());
    }

    public synchronized String getMappingStatus(ResponderMapping mapping) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (mapping == null || !SlackTransport.isValidUserId(mapping.getSlackUser())) return "Invalid";
        String current = getVerifiedWorkspaceId();
        if (!mapping.getWorkspaceId().isBlank()
                && !current.isBlank()
                && !mapping.getWorkspaceId().equals(current)) return "Workspace mismatch";
        if ("INVALID".equals(mapping.getVerificationStatus())) return "Invalid";
        return !current.isBlank()
                        && current.equals(mapping.getWorkspaceId())
                        && "VERIFIED".equals(mapping.getVerificationStatus())
                ? "Verified"
                : "Needs validation";
    }

    /** Explicit administrator validation; no network lookup occurs while holding the configuration lock. */
    public boolean validateMapping(String id) {
        return validateMappingResult(id, false)
                == io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.VERIFIED;
    }

    io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status validateMappingResult(
            String id, boolean automatic) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        final ResponderMapping original;
        final String selectedCredential;
        final String selectedFingerprint;
        final String selectedWorkspace;
        final long selectedProofGeneration;
        synchronized (this) {
            int originalIndex = mappingIndex(id);
            ResponderMapping value = getResponderMappings().get(originalIndex);
            original = ResponderMapping.associated(
                    value,
                    value.getWorkspaceId(),
                    value.getWorkspaceName(),
                    value.getDisplayName(),
                    "NEEDS_VALIDATION",
                    value.intendedCredential(),
                    value.intendedFingerprint());
            selectedCredential = getCredentialId();
            selectedFingerprint = SlackTransport.get().credentialFingerprint(selectedCredential);
            selectedWorkspace = getVerifiedWorkspaceId();
            selectedProofGeneration = workspaceProofGeneration;
            if (automatic
                    && !eligibleForAutomaticValidation(
                            original, selectedCredential, selectedFingerprint, getVerifiedWorkspaceId()))
                return io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.NEEDS_VALIDATION;
            var pending = new java.util.ArrayList<>(getResponderMappings());
            pending.set(originalIndex, original);
            setResponderMappings(pending);
        }
        io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification result;
        try {
            result = SlackTransport.get().verifyMember(selectedCredential, original.getSlackUser());
        } catch (RuntimeException | LinkageError failure) {
            result =
                    new io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification(false, "", "", "", "");
        }
        synchronized (this) {
            if (Thread.currentThread().isInterrupted()
                    || (automatic && MappingValidationWorker.get().isStopping()))
                return io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.NEEDS_VALIDATION;
            if (selectedProofGeneration != workspaceProofGeneration
                    || (automatic && !selectedWorkspace.equals(getVerifiedWorkspaceId()))
                    || !selectedCredential.equals(getCredentialId())
                    || !selectedFingerprint.equals(SlackTransport.get().credentialFingerprint(selectedCredential)))
                return io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.NEEDS_VALIDATION;
            int index;
            try {
                index = mappingIndex(id);
            } catch (IllegalArgumentException removed) {
                return io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.NEEDS_VALIDATION;
            }
            if (!original.equals(getResponderMappings().get(index)))
                return io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.NEEDS_VALIDATION;
            boolean proof = !selectedFingerprint.isBlank()
                    && original.getSlackUser().equals(result.userId())
                    && result.workspaceId() != null
                    && result.workspaceId().matches("T[A-Z0-9]{8,}")
                    && selectedFingerprint.equals(result.credentialFingerprint());
            var status = proof
                    ? result.status()
                    : io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.NEEDS_VALIDATION;
            String targetWorkspace = proof ? result.workspaceId() : original.getWorkspaceId();
            if (automatic
                    && !original.getWorkspaceId().isBlank()
                    && !original.getWorkspaceId().equals(targetWorkspace))
                return io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.NEEDS_VALIDATION;
            rejectDuplicate(
                    original.getIdentity(), original.getId(), targetWorkspace, selectedCredential, selectedFingerprint);
            String workspaceName = targetWorkspace.equals(getVerifiedWorkspaceId())
                    ? getVerifiedWorkspace()
                    : original.getWorkspaceName();
            String name =
                    status == io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status.VERIFIED
                            ? result.friendlyName()
                            : original.getDisplayName();
            var values = new java.util.ArrayList<>(getResponderMappings());
            values.set(
                    index,
                    ResponderMapping.associated(
                            original,
                            targetWorkspace,
                            workspaceName,
                            name,
                            status.name(),
                            selectedCredential,
                            selectedFingerprint));
            setResponderMappings(values);
            return status;
        }
    }

    synchronized boolean validationContextMatches(String credential, String fingerprint, String workspace) {
        return credential.equals(getCredentialId())
                && !fingerprint.isBlank()
                && fingerprint.equals(SlackTransport.get().credentialFingerprint(credential))
                && !workspace.isBlank()
                && workspace.equals(getVerifiedWorkspaceId());
    }

    static boolean eligibleForAutomaticValidation(
            ResponderMapping mapping, String credential, String fingerprint, String workspace) {
        if (workspace.isBlank()) return false;
        if (!mapping.getWorkspaceId().isBlank()) return workspace.equals(mapping.getWorkspaceId());
        return !fingerprint.isBlank()
                && credential.equals(mapping.intendedCredential())
                && fingerprint.equals(mapping.intendedFingerprint());
    }

    private synchronized void scheduleMappingValidation() {
        String workspace = getVerifiedWorkspaceId();
        if (workspace.isBlank()) return;
        String credential = getCredentialId();
        String fingerprint = SlackTransport.get().credentialFingerprint(credential);
        var values = new java.util.ArrayList<>(getResponderMappings());
        for (int i = 0; i < values.size(); i++) {
            ResponderMapping mapping = values.get(i);
            if (eligibleForAutomaticValidation(mapping, credential, fingerprint, workspace)) {
                values.set(
                        i,
                        ResponderMapping.associated(
                                mapping,
                                mapping.getWorkspaceId(),
                                mapping.getWorkspaceName(),
                                mapping.getDisplayName(),
                                "NEEDS_VALIDATION",
                                credential,
                                fingerprint));
            }
        }
        setResponderMappings(values);
        MappingValidationWorker.get().schedule(this, credential, fingerprint, workspace);
    }

    public synchronized boolean isTagResponders() {
        return tagResponders;
    }

    public synchronized List<ResponderMapping> getResponderMappings() {
        return responderMappings == null
                ? List.of()
                : responderMappings.stream()
                        .filter(Objects::nonNull)
                        .limit(MAX_RESPONDER_MAPPINGS)
                        .toList();
    }

    @DataBoundSetter
    public synchronized void setEnabled(boolean value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (enabled != value) {
            enabled = value;
            if (value) enabledAtMillis = System.currentTimeMillis();
            modifiedAtMillis = System.currentTimeMillis();
            revision++;
        }
        save();
    }

    @DataBoundSetter
    public synchronized void setCredentialId(String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String next = ResponderMapping.bounded(value, 256);
        boolean changed = !next.equals(getCredentialId());
        if (changed) {
            credentialId = next;
            modifiedAtMillis = System.currentTimeMillis();
            revision++;
        }
        save();
        if (changed) scheduleMappingValidation();
    }

    @DataBoundSetter
    public synchronized void setDefaultChannel(String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String next = ResponderMapping.bounded(value, 100);
        if (!next.equals(getDefaultChannel())) {
            defaultChannel = next;
            modifiedAtMillis = System.currentTimeMillis();
            revision++;
        }
        save();
    }

    @DataBoundSetter
    public synchronized void setTagResponders(boolean value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (tagResponders != value) {
            tagResponders = value;
            mappingRevision++;
        }
        save();
    }

    public synchronized void setResponderMappings(List<ResponderMapping> value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        List<ResponderMapping> next = value == null
                ? List.of()
                : value.stream()
                        .filter(Objects::nonNull)
                        .limit(MAX_RESPONDER_MAPPINGS)
                        .toList();
        if (!getResponderMappings().equals(next)) {
            responderMappings = next;
            mappingRevision++;
        }
        save();
    }

    public void addMapping(String identity, String slackUser) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ResponderMapping next = validatedMapping(identity, slackUser);
        synchronized (this) {
            if (getResponderMappings().size() >= MAX_RESPONDER_MAPPINGS)
                throw new IllegalArgumentException("The responder mapping limit has been reached.");
            next = intendedMapping(next, null);
            rejectDuplicate(
                    next.getIdentity(),
                    null,
                    next.getWorkspaceId(),
                    next.intendedCredential(),
                    next.intendedFingerprint());
            var values = new java.util.ArrayList<>(getResponderMappings());
            values.add(next);
            setResponderMappings(values);
        }
        validateMapping(next.getId());
    }

    public void updateMapping(String id, String identity, String slackUser) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ResponderMapping next = validatedMapping(identity, slackUser);
        synchronized (this) {
            int index = mappingIndex(id);
            var values = new java.util.ArrayList<>(getResponderMappings());
            ResponderMapping updated = intendedMapping(
                    ResponderMapping.update(id, next.getIdentity(), next.getSlackUser()), values.get(index));
            rejectDuplicate(
                    updated.getIdentity(),
                    id,
                    updated.getWorkspaceId(),
                    updated.intendedCredential(),
                    updated.intendedFingerprint());
            values.set(index, updated);
            setResponderMappings(values);
        }
        validateMapping(id);
    }

    private ResponderMapping intendedMapping(ResponderMapping value, ResponderMapping previous) {
        String workspace = getVerifiedWorkspaceId();
        boolean sameMember = previous != null && previous.getSlackUser().equals(value.getSlackUser());
        String name = sameMember ? previous.getDisplayName() : "";
        String workspaceName = getVerifiedWorkspace();
        if (workspace.isBlank() && sameMember) {
            workspace = previous.getWorkspaceId();
            workspaceName = previous.getWorkspaceName();
        }
        return ResponderMapping.associated(
                value,
                workspace,
                workspaceName,
                name,
                "NEEDS_VALIDATION",
                getCredentialId(),
                SlackTransport.get().credentialFingerprint(getCredentialId()));
    }

    public synchronized void removeMapping(String id) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        int index = mappingIndex(id);
        var values = new java.util.ArrayList<>(getResponderMappings());
        values.remove(index);
        setResponderMappings(values);
    }

    private static ResponderMapping validatedMapping(String identity, String slackUser) {
        ResponderMapping value = new ResponderMapping(identity, slackUser);
        if (value.getIdentity().isBlank())
            throw new IllegalArgumentException("Enter a Git or Jenkins identity of at most 256 characters.");
        if (!SlackTransport.isValidUserId(value.getSlackUser()))
            throw new IllegalArgumentException("Enter a valid Slack user ID.");
        return value;
    }

    private int mappingIndex(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("This responder mapping is unavailable. Refresh the page.");
        var values = getResponderMappings();
        int found = -1;
        for (int i = 0; i < values.size(); i++) {
            if (id.equals(values.get(i).getId())) {
                if (found >= 0)
                    throw new IllegalArgumentException("This responder mapping is ambiguous. Refresh the page.");
                found = i;
            }
        }
        if (found < 0) throw new IllegalArgumentException("This responder mapping is unavailable. Refresh the page.");
        return found;
    }

    private void rejectDuplicate(
            String identity, String ignoredId, String workspace, String credential, String fingerprint) {
        if (getResponderMappings().stream()
                .anyMatch(value -> !Objects.equals(ignoredId, value.getId())
                        && identity.equalsIgnoreCase(value.getIdentity().trim())
                        && (workspace.isBlank()
                                ? value.getWorkspaceId().isBlank()
                                        && credential.equals(value.intendedCredential())
                                        && fingerprint.equals(value.intendedFingerprint())
                                : workspace.equals(value.getWorkspaceId()))))
            throw new IllegalArgumentException(
                    "A responder mapping already exists for this identity in this workspace.");
    }

    public synchronized boolean isConfigured() {
        return enabled && !getCredentialId().isBlank() && SlackTransport.isValidChannel(getDefaultChannel());
    }

    /** Only one exact, valid mapping may introduce a mention. */
    public synchronized String mappedSlackUser(String identity) {
        return mappedSlackUser(identity, getVerifiedWorkspaceId());
    }

    public synchronized String mappedSlackUser(String identity, String workspaceId) {
        if (!tagResponders || identity == null || identity.isBlank()) return null;
        List<ResponderMapping> matches = getResponderMappings().stream()
                .filter(mapping -> identity.trim().equalsIgnoreCase(mapping.getIdentity())
                        && Objects.equals(workspaceId, mapping.getWorkspaceId()))
                .toList();
        return workspaceId != null
                        && !workspaceId.isBlank()
                        && matches.size() == 1
                        && workspaceId.equals(matches.get(0).getWorkspaceId())
                        && "VERIFIED".equals(matches.get(0).getVerificationStatus())
                        && SlackTransport.isValidUserId(matches.get(0).getSlackUser())
                ? matches.get(0).getSlackUser()
                : null;
    }

    @Override
    public synchronized boolean configure(StaplerRequest2 request, JSONObject json) throws FormException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try (BulkChange change = new BulkChange(this)) {
            JSONObject settings = JSONObject.fromObject(json.toString());
            settings.remove("responderMappings");
            request.bindJSON(this, settings);
            change.commit();
        } catch (IOException | IllegalArgumentException failure) {
            throw new FormException("Could not save Slack settings. Check the supplied values.", null);
        }
        return true;
    }

    @RequirePOST
    public ListBoxModel doFillCredentialIdItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2, Jenkins.get(), StringCredentials.class, List.of(), CredentialsMatchers.always());
    }

    @RequirePOST
    public FormValidation doCheckDefaultChannel(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return value == null || value.isBlank() || SlackTransport.isValidChannel(value)
                ? FormValidation.ok()
                : FormValidation.error("Enter a Slack channel name or channel ID.");
    }

    private synchronized FormValidation failedWorkspaceVerification(
            String submitted, String submittedChannel, String safeMessage) {
        if (Objects.equals(verifiedCredentialId, submitted) || Objects.equals(getCredentialId(), submitted))
            workspaceProofGeneration++;
        if (Objects.equals(verifiedCredentialId, submitted)) {
            verifiedCredentialId = "";
            verifiedCredentialFingerprint = "";
            verifiedWorkspace = "";
            verifiedWorkspaceId = "";
            save();
        }
        return FormValidation.errorWithMarkup("<span class=\"bci-slack-verification-failed\" data-credential=\""
                + hudson.Util.escape(submitted) + "\" data-channel=\"" + hudson.Util.escape(submittedChannel)
                + "\">" + hudson.Util.escape(safeMessage) + "</span>");
    }

    @RequirePOST
    public FormValidation doTestConnection(@QueryParameter String credentialId, @QueryParameter String defaultChannel) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            var result = SlackTransport.get()
                    .checkConnection(
                            ResponderMapping.bounded(credentialId, 256), ResponderMapping.bounded(defaultChannel, 100));
            if (!result.success())
                return failedWorkspaceVerification(
                        ResponderMapping.bounded(credentialId, 256),
                        ResponderMapping.bounded(defaultChannel, 100),
                        result.safeMessage());
            String submitted = ResponderMapping.bounded(credentialId, 256);
            if (!recordWorkspaceVerification(submitted, result))
                return failedWorkspaceVerification(
                        submitted,
                        ResponderMapping.bounded(defaultChannel, 100),
                        "The selected credential changed during verification. Test the connection again.");
            return FormValidation.okWithMarkup("<span class=\"bci-slack-verification\" data-workspace=\""
                    + hudson.Util.escape(result.workspaceName()) + "\" data-credential=\""
                    + hudson.Util.escape(submitted)
                    + "\" data-channel=\"" + hudson.Util.escape(ResponderMapping.bounded(defaultChannel, 100))
                    + "\">✓ Connected to " + hudson.Util.escape(result.workspaceName()) + "<br/>✓ Ready to post to "
                    + hudson.Util.escape(result.channelName()) + "</span>");
        } catch (RuntimeException | LinkageError failure) {
            return failedWorkspaceVerification(
                    ResponderMapping.bounded(credentialId, 256),
                    ResponderMapping.bounded(defaultChannel, 100),
                    "Unable to connect to Slack. Check the bot credential and channel.");
        }
    }
}

package io.jenkins.plugins.changeinvestigator.slack.transport;

/** Private verification metadata for an explicitly selected member; the ID remains authoritative. */
public record MemberVerification(
        boolean verified,
        String userId,
        String workspaceId,
        String credentialFingerprint,
        String friendlyName,
        Status status) {
    public enum Status {
        VERIFIED,
        INVALID,
        NEEDS_VALIDATION
    }

    public MemberVerification(
            boolean verified, String userId, String workspaceId, String credentialFingerprint, String friendlyName) {
        this(
                verified,
                userId,
                workspaceId,
                credentialFingerprint,
                friendlyName,
                verified ? Status.VERIFIED : Status.NEEDS_VALIDATION);
    }
}

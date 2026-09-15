package io.jenkins.plugins.changeinvestigator.slack.transport;

/**
 * Private verification metadata for an explicitly selected member; the ID remains authoritative.
 * The authentication digest is SHA-256 of the bot token, never the token itself.
 */
public record MemberVerification(
        boolean verified,
        String userId,
        String workspaceId,
        String authenticationDigest,
        String friendlyName,
        Status status) {
    public enum Status {
        VERIFIED,
        INVALID,
        NEEDS_VALIDATION
    }

    public MemberVerification(
            boolean verified, String userId, String workspaceId, String authenticationDigest, String friendlyName) {
        this(
                verified,
                userId,
                workspaceId,
                authenticationDigest,
                friendlyName,
                verified ? Status.VERIFIED : Status.NEEDS_VALIDATION);
    }
}

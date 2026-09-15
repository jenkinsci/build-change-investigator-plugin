package io.jenkins.plugins.changeinvestigator.slack.transport;

/**
 * Bounded display text and a verified route from a non-posting connection check.
 * The authentication digest is SHA-256 of the bot token, never the token itself.
 */
public record ConnectionResult(
        boolean success,
        String safeMessage,
        String workspaceName,
        String channelName,
        SlackRoute route,
        long retryAfterSeconds,
        String authenticationDigest) {
    public ConnectionResult(
            boolean success, String safeMessage, String workspaceName, String channelName, SlackRoute route) {
        this(success, safeMessage, workspaceName, channelName, route, 0, "");
    }

    public ConnectionResult(
            boolean success,
            String safeMessage,
            String workspaceName,
            String channelName,
            SlackRoute route,
            long retryAfterSeconds) {
        this(success, safeMessage, workspaceName, channelName, route, retryAfterSeconds, "");
    }
}

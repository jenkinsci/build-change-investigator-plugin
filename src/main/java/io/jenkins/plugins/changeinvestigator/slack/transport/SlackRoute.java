package io.jenkins.plugins.changeinvestigator.slack.transport;

/** Resolved workspace and conversation identity, never a token or configurable URL. */
public record SlackRoute(String teamId, String channelId) {}

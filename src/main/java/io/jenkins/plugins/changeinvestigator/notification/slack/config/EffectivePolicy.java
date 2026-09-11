package io.jenkins.plugins.changeinvestigator.notification.slack.config;

import java.util.List;
import java.util.UUID;

/** Resolved job choices; routing and secrets always remain administrator controlled. */
public record EffectivePolicy(List<UUID> destinationIds, boolean recovery, boolean aiOnly, boolean mentions) {
    public EffectivePolicy {
        destinationIds = List.copyOf(destinationIds);
    }
}

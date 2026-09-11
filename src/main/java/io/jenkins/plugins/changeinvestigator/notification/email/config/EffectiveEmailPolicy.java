package io.jenkins.plugins.changeinvestigator.notification.email.config;

import java.util.List;
import java.util.UUID;

/** Resolved job choices; routing and secrets always remain administrator controlled. */
public record EffectiveEmailPolicy(List<UUID> destinationIds, boolean recovery, boolean aiOnly) {
    public EffectiveEmailPolicy {
        destinationIds = List.copyOf(destinationIds);
    }
}

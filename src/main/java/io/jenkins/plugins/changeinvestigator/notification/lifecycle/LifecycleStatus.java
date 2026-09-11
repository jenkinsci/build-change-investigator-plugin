package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

/** Durable investigation disposition; presentation labels are events, not states. */
public enum LifecycleStatus {
    ACTIVE,
    RECOVERY_PENDING,
    RECOVERED,
    CONFIRMED_RESOLUTION,
    CLOSED
}

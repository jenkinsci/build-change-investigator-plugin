package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

/** Recovery attribution never implies a confirmed causal fix. */
public enum RecoveryAssessment {
    NONE,
    LIKELY_RECOVERY_CHANGE,
    FIX_UNKNOWN
}

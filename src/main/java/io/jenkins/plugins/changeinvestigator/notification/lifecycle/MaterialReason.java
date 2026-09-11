package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

/** Stable material reason vocabulary shared with notification event schema version one. */
public enum MaterialReason {
    FIRST_BAD_VERIFIED,
    BOUNDARY_CORRECTED,
    TOP_CANDIDATE_CHANGED,
    EVIDENCE_STRENGTH_CHANGED,
    RELEVANT_CHANGE_ADDED,
    RESPONDER_CHANGED,
    AI_COMPLETED,
    RECOVERY_CANDIDATE_ADDED,
    RECOVERY_VERIFIED,
    HUMAN_CONFIRMATION,
    CORRECTION,
    HISTORY_EXPIRED
}

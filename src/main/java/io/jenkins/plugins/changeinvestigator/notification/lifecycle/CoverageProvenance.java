package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

/** Supplies local, versioned coverage evidence; implementations must not infer it from SUCCESS alone. */
@FunctionalInterface
public interface CoverageProvenance {
    CoverageEvidence evaluate(String runId, String contextDigest, String affectedCheck);
}

package io.jenkins.plugins.changeinvestigator.notification.identity;

import hudson.ExtensionPoint;
import hudson.model.Run;
import java.util.List;
import java.util.Map;

/** Trusted, local provenance adapter. Missing support remains unresolved; callers must not guess from logs. */
public interface TrustedIdentityEvidence extends ExtensionPoint {
    /** Return null when this adapter cannot establish authoritative evidence for the run. */
    Snapshot observe(Run<?, ?> run, String jobId, String observationId);

    record Snapshot(
            ExecutionContextV1 context,
            FailureSignatureV1.Category category,
            Map<String, String> identityFields,
            FailureSignatureV1.ContextFields contextFields,
            String reportedSourcePath,
            String trustedWorkspaceRoot,
            List<String> completeSourceInventory,
            boolean inventoryComplete) {
        public Snapshot {
            identityFields = identityFields == null ? null : Map.copyOf(identityFields);
            completeSourceInventory =
                    completeSourceInventory == null ? List.of() : List.copyOf(completeSourceInventory);
            if (completeSourceInventory.size() > 10000)
                throw new IllegalArgumentException("Source inventory exceeds bound");
        }
    }
}

package io.jenkins.plugins.changeinvestigator.notification.identity;

import java.util.TreeMap;

/** Adapts trusted structured evidence; does not reuse the page model's signature. */
public final class NotificationSignatureAdapter {
    private NotificationSignatureAdapter() {}

    public static FailureSignatureV1 adapt(TrustedIdentityEvidence.Snapshot snapshot, String observationId) {
        if (snapshot == null
                || snapshot.context() == null
                || !snapshot.context().trusted()) {
            return FailureSignatureV1.unresolved(
                    FailureSignatureV1.Category.GENERIC,
                    FailureSignatureV1.ContextFields.empty(),
                    observationId,
                    "MISSING_CONTEXT");
        }
        var category = snapshot.category() == null ? FailureSignatureV1.Category.GENERIC : snapshot.category();
        var context =
                snapshot.contextFields() == null ? FailureSignatureV1.ContextFields.empty() : snapshot.contextFields();
        try {
            if (!snapshot.context().ancestryKnown())
                return FailureSignatureV1.unresolved(category, context, observationId, "MISSING_CONTEXT");
            if (snapshot.identityFields() == null)
                return FailureSignatureV1.unresolved(category, context, observationId, "MISSING_CONTEXT");
            var fields = new TreeMap<>(snapshot.identityFields());
            if (category == FailureSignatureV1.Category.COMPILER) {
                fields.put(
                        "repositoryPath",
                        SourcePathResolver.resolve(
                                snapshot.reportedSourcePath(),
                                snapshot.trustedWorkspaceRoot(),
                                snapshot.completeSourceInventory(),
                                snapshot.inventoryComplete()));
                String slot = fields.get("scmSourceId");
                if (snapshot.context().sources().stream()
                        .noneMatch(s -> s.slotId().equals(slot)))
                    return FailureSignatureV1.unresolved(category, context, observationId, "MISSING_CONTEXT");
            }
            return FailureSignatureV1.create(category, fields, context, observationId);
        } catch (IllegalArgumentException e) {
            String reason = "REDACTED_CRITICAL_FIELD".equals(e.getMessage())
                    ? "REDACTED_CRITICAL_FIELD"
                    : "TRUNCATED_CRITICAL_FIELD".equals(e.getMessage()) ? "TRUNCATED_CRITICAL_FIELD" : "AMBIGUOUS_PATH";
            return FailureSignatureV1.unresolved(category, context, observationId, reason);
        }
    }
}

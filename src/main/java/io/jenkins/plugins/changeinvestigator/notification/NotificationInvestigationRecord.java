package io.jenkins.plugins.changeinvestigator.notification;

import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.InvestigationKeyV1;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.LifecycleReducer;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.SuppressionPolicy;
import io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent;
import java.util.List;
import java.util.UUID;

/** Versioned notification aggregate, independent of the derived investigation page model. */
public record NotificationInvestigationRecord(
        int schemaVersion,
        UUID investigationId,
        UUID jobId,
        ExecutionContextV1 context,
        FailureSignatureV1 signature,
        InvestigationKeyV1 key,
        LifecycleReducer.Snapshot lifecycle,
        long semanticSequence,
        String aiState,
        List<String> processedObservations,
        List<NotificationEvent> events,
        List<DestinationState> destinations,
        List<Audit> audit) {
    public NotificationInvestigationRecord {
        if (schemaVersion != 1
                || investigationId == null
                || jobId == null
                || context == null
                || signature == null
                || key == null
                || lifecycle == null
                || semanticSequence < 1) throw new IllegalArgumentException("Unsupported notification record");
        processedObservations = List.copyOf(processedObservations);
        events = List.copyOf(events);
        destinations = List.copyOf(destinations);
        audit = List.copyOf(audit);
        if (!List.of("AI_NOT_CONFIGURED", "AI_DISABLED", "AI_PENDING", "AI_COMPLETE", "AI_FAILED")
                .contains(aiState)) throw new IllegalArgumentException("Invalid AI projection");
        if (processedObservations.size() > 200 || events.size() > 32 || destinations.size() > 10 || audit.size() > 200)
            throw new IllegalArgumentException("Notification aggregate limit");
        if (!jobId.toString().equals(context.jobId())) throw new IllegalArgumentException("Notification job mismatch");
        if (!key.jobId().equals(jobId.toString())
                || !key.contextDigest().equals(context.digest())
                || !java.util.Objects.equals(key.signatureDigest(), signature.digest()))
            throw new IllegalArgumentException("Notification key mismatch");
        for (NotificationEvent event : events) {
            var snapshot = event.snapshot();
            if (!snapshot.path("jobId").asText().equals(jobId.toString())
                    || !snapshot.path("investigationId").asText().equals(investigationId.toString()))
                throw new IllegalArgumentException("Notification event scope mismatch");
        }
    }

    public record DestinationState(
            UUID destinationId, long generation, SuppressionPolicy.State policy, List<OutboxIntent> intents) {
        public DestinationState {
            intents = List.copyOf(intents);
            if (destinationId == null || generation < 1 || policy == null || intents.size() > 16)
                throw new IllegalArgumentException("Invalid destination state");
        }
    }

    public record Audit(String code, long at, long revision) {
        public Audit {
            if (code == null || !code.matches("[A-Z0-9_]{1,80}") || at < 0 || revision < 1)
                throw new IllegalArgumentException("Invalid audit record");
        }
    }
}

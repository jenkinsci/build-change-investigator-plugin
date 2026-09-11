package io.jenkins.plugins.changeinvestigator.notification.identity;

import java.util.List;

/** Pure eligibility decision. Historical resemblance never authorizes grouping. */
public final class CorrelationClassifier {
    private CorrelationClassifier() {}

    public enum Status {
        SAME,
        NEW,
        SIMILAR_HISTORICAL,
        UNKNOWN
    }

    public record PriorCase(
            String caseId, String jobId, ExecutionContextV1 context, FailureSignatureV1 signature, boolean ended) {}

    public record Continuity(boolean complete, boolean interveningDistinctFailure, boolean incompatibleVersion) {}

    public record Classification(Status status, String caseId, List<String> historicalCaseIds, String reason) {
        public Classification {
            historicalCaseIds = List.copyOf(historicalCaseIds);
        }
    }

    public static Classification classify(
            String jobId,
            ExecutionContextV1 context,
            FailureSignatureV1 signature,
            List<PriorCase> prior,
            Continuity continuity) {
        if (prior == null
                || prior.size() > 100
                || context == null
                || !jobId.equals(context.jobId())
                || !context.trusted()
                || !context.ancestryKnown()
                || signature == null
                || signature.quality() != FailureSignatureV1.Quality.SPECIFIC)
            return unknown("UNTRUSTED_OR_UNRESOLVED");
        if (prior.stream().anyMatch(p -> p == null || p.context() == null || p.signature() == null))
            return unknown("MISSING_PRIOR_IDENTITY");
        if (continuity == null || !continuity.complete() || continuity.incompatibleVersion())
            return unknown("INCOMPLETE_HISTORY_OR_VERSION");
        List<PriorCase> matching = prior.stream()
                .filter(p -> jobId.equals(p.jobId())
                        && p.context().trusted()
                        && p.context().digest().equals(context.digest())
                        && signature.sameIdentity(p.signature()))
                .toList();
        List<String> historical = matching.stream()
                .filter(PriorCase::ended)
                .map(PriorCase::caseId)
                .toList();
        List<PriorCase> active = matching.stream().filter(p -> !p.ended()).toList();
        if (active.size() > 1) return unknown("MULTIPLE_MATCHES");
        if (!active.isEmpty() && !continuity.interveningDistinctFailure())
            return new Classification(
                    Status.SAME, active.get(0).caseId(), historical, "COMPLETE_SAME_SIGNATURE_SEQUENCE");
        return new Classification(Status.NEW, null, historical, historical.isEmpty() ? "NEW_EPISODE" : "RECURRENCE");
    }

    public static Status historicalRelation(FailureSignatureV1 left, FailureSignatureV1 right) {
        return left != null && left.sameIdentity(right) ? Status.SIMILAR_HISTORICAL : Status.UNKNOWN;
    }

    private static Classification unknown(String reason) {
        return new Classification(Status.UNKNOWN, null, List.of(), reason);
    }
}

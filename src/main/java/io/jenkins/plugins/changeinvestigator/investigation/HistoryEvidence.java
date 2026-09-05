package io.jenkins.plugins.changeinvestigator.investigation;

import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Bounded signature-based history proof. Missing or non-failure intermediate results stay unknown. */
public final class HistoryEvidence implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_BUILDS = 100;
    private final int firstBad, similarBuild;
    private final String explanation;

    public HistoryEvidence(int firstBad, int similarBuild, String explanation) {
        this.firstBad = firstBad;
        this.similarBuild = similarBuild;
        this.explanation = explanation;
    }

    public record Observation(int number, String result, String signature) {}

    public static HistoryEvidence analyze(List<Observation> newestFirst, int current, FailureSignal signal) {
        if (!signal.isSpecific())
            return new HistoryEvidence(0, 0, "First bad not yet narrowed: no specific failure signature.");
        int first = 0, similar = 0, expected = current;
        boolean sequence = true, verified = false, pastSuccess = false;
        String reason = "First bad not yet narrowed: retained history did not reach a verified successful baseline.";
        for (Observation item : newestFirst.stream().limit(MAX_BUILDS).toList()) {
            if (item.result().equals("SUCCESS")) pastSuccess = true;
            if (pastSuccess
                    && item.result().equals("FAILURE")
                    && item.number() < current
                    && item.signature().equals(signal.getSignature())
                    && similar == 0) similar = item.number();
            if (sequence) {
                if (item.number() != expected) {
                    sequence = false;
                    reason = "First bad not yet narrowed: build history contains a gap.";
                } else if (item.result().equals("SUCCESS")) {
                    verified = first > 0;
                    sequence = false;
                    reason = verified
                            ? "Verified adjacent success → failures with the same structured signature. No aborted, unstable, missing or not-built run interrupts this window."
                            : "No failed sequence was established.";
                } else if (!item.result().equals("FAILURE") || !item.signature().equals(signal.getSignature())) {
                    sequence = false;
                    reason =
                            "First bad not yet narrowed: an intermediate result or failure signature differs or is unavailable.";
                } else first = item.number();
            }
            expected = item.number() - 1;
        }
        return new HistoryEvidence(verified ? first : 0, similar, reason);
    }

    public static HistoryEvidence collect(Run<?, ?> current, FailureSignal signal) {
        List<Observation> observations = new ArrayList<>();
        Run<?, ?> cursor = current;
        for (int i = 0; i < MAX_BUILDS && cursor != null; i++, cursor = cursor.getPreviousBuild()) {
            InvestigationAction action = cursor.getAction(InvestigationAction.class);
            FailureSignal prior = cursor == current
                    ? signal
                    : action == null
                            ? null
                            : FailureSignal.extract(action.getEvidence().getLogExcerpt());
            Result result = cursor.getResult();
            observations.add(new Observation(
                    cursor.getNumber(),
                    result == null ? "UNKNOWN" : result.toString(),
                    prior == null ? "" : prior.getSignature()));
        }
        return analyze(observations, current.getNumber(), signal);
    }

    public int getFirstBad() {
        return firstBad;
    }

    public boolean isVerified() {
        return firstBad > 0;
    }

    public int getSimilarBuild() {
        return similarBuild;
    }

    public String getExplanation() {
        return explanation;
    }
}

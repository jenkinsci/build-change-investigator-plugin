package io.jenkins.plugins.changeinvestigator.investigation;

import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry;
import java.io.Serializable;
import java.util.List;

/** Immutable view and copy model shared by automatic and explicitly selected comparisons. */
public final class InvestigationCase implements Serializable {
    private static final long serialVersionUID = 1L;
    private final BuildInvestigationEvidence evidence;
    private final FailureSignal signal, baselineSignal;
    private final HistoryEvidence history;
    private final List<ChangeEntry> changes;
    private final List<RankedChange> ranked;
    private final int baseline, target;
    private final boolean comparison;
    private java.util.Map<String, String> beforeValues = new java.util.LinkedHashMap<>(),
            afterValues = new java.util.LinkedHashMap<>();

    public void attachManifests(ManifestEvidence before, ManifestEvidence after) {
        beforeValues =
                before == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(before.getValues());
        afterValues =
                after == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(after.getValues());
    }

    public boolean hasBefore(RankedChange change) {
        return beforeValues != null && beforeValues.containsKey(change.getPath());
    }

    public boolean hasAfter(RankedChange change) {
        return afterValues != null && afterValues.containsKey(change.getPath());
    }

    public String beforeValue(RankedChange change) {
        return hasBefore(change) ? beforeValues.get(change.getPath()) : "Source content unavailable";
    }

    public String afterValue(RankedChange change) {
        return hasAfter(change) ? afterValues.get(change.getPath()) : "Changed in #" + change.getBuildNumber();
    }

    public InvestigationCase(
            BuildInvestigationEvidence evidence,
            HistoryEvidence history,
            int baseline,
            boolean comparison,
            FailureSignal baselineSignal) {
        this.evidence = evidence;
        this.history = history;
        this.baseline = baseline;
        target = evidence.getFailedBuildNumber();
        this.comparison = comparison;
        this.baselineSignal = baselineSignal;
        signal = FailureSignal.extract(
                "SUCCESS".equals(evidence.getFailedBuildResult()) ? List.of() : evidence.getLogExcerpt());
        int end = !comparison && history.isVerified() ? history.getFirstBad() : target;
        changes = evidence.getChangeEntries().stream()
                .filter(e -> e.getFromBuildNumber() > baseline && e.getFromBuildNumber() <= end)
                .toList();
        ranked = RankedChange.rank(changes, signal);
    }

    public BuildInvestigationEvidence getEvidence() {
        return evidence;
    }

    public FailureSignal getSignal() {
        return signal;
    }

    public FailureSignal getBaselineSignal() {
        return baselineSignal;
    }

    public HistoryEvidence getHistory() {
        return history;
    }

    public List<RankedChange> getRanked() {
        return ranked;
    }

    public List<ChangeEntry> getChanges() {
        return changes;
    }

    public String getWindowLabel() {
        return (baseline > 0 ? "#" + baseline : "Baseline unavailable") + " → #" + getWindowEnd();
    }

    public String getSignalTitle() {
        return "SUCCESS".equals(evidence.getFailedBuildResult())
                ? "No failure observed in target build"
                : signal.getCategory();
    }

    public String getSignalText() {
        return "SUCCESS".equals(evidence.getFailedBuildResult())
                ? "Target build completed successfully."
                : signal.getText();
    }

    public int getBaseline() {
        return baseline;
    }

    public int getTarget() {
        return target;
    }

    public boolean isComparison() {
        return comparison;
    }

    public int getWindowEnd() {
        return !comparison && history.isVerified() ? history.getFirstBad() : target;
    }

    public long getFileCount() {
        return ranked.stream().map(RankedChange::getPath).distinct().count();
    }

    public long getRelevantCount() {
        return ranked.stream().filter(r -> r.getGroup().equals("Most relevant")).count();
    }

    public long getPossibleCount() {
        return ranked.stream()
                .filter(r -> r.getGroup().equals("Possibly related"))
                .count();
    }

    public long getOtherCount() {
        return ranked.stream().filter(r -> r.getGroup().equals("Other")).count();
    }

    public boolean isStrongRelationship() {
        return getRelevantCount() > 0;
    }

    public RankedChange getTop() {
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    public String getCopyText() {
        StringBuilder text = new StringBuilder("Build #" + target + " — " + evidence.getFailedBuildResult() + "\n");
        text.append(comparison ? "Baseline: " : "Last good: ")
                .append(baseline > 0 ? "#" + baseline : "unavailable")
                .append("\nFirst bad: ")
                .append(history.isVerified() && !comparison ? "#" + history.getFirstBad() : "not yet narrowed");
        text.append("\nWindow: ")
                .append(getWindowLabel())
                .append("SUCCESS".equals(evidence.getFailedBuildResult()) ? "\n\nSignal:\n" : "\n\nFailure:\n")
                .append(signal.getLocation())
                .append('\n')
                .append(getSignalText());
        if ("SUCCESS".equals(evidence.getFailedBuildResult())) {
            text.append("\n\nNo failure observed in target; retained changes remain available for inspection.");
        } else if (isStrongRelationship()) {
            RankedChange top = getTop();
            text.append("\n\nMost relevant change:\n")
                    .append(top.getPath())
                    .append("\nBefore: ")
                    .append(beforeValue(top))
                    .append("\nAfter: ")
                    .append(afterValue(top))
                    .append("\n")
                    .append(" · ")
                    .append(top.getCommit())
                    .append("\nEvidence:\n")
                    .append(top.getReason())
                    .append("\nLimitation:\n")
                    .append(top.getLimitation())
                    .append("\nSuggested check:\n")
                    .append(top.getNextCheck());
        } else
            text.append("\n\nNo change has a strong direct relationship to this failure.\nLimitation: ")
                    .append(
                            evidence.isChangeDataAvailable()
                                    ? "Insufficient direct relationship evidence."
                                    : "SCM change evidence unavailable.")
                    .append("\nSuggested check: Inspect service inputs/runtime environment.");
        return FailureSignal.safe(text.toString(), 6000);
    }
}

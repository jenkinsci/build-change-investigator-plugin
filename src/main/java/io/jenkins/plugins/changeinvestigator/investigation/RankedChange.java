package io.jenkins.plugins.changeinvestigator.investigation;

import io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explainable relationships; a matching file is never a claim that its failing line changed. */
public final class RankedChange implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String path, commit, author, message, group, strength, reason, limitation, nextCheck;
    private final int buildNumber;

    private RankedChange(
            String path,
            ChangeEntry entry,
            String group,
            String strength,
            String reason,
            String limitation,
            String nextCheck) {
        this.path = FailureSignal.safe(path, 500);
        commit = FailureSignal.safe(entry.getCommitId(), 100);
        author = FailureSignal.safe(entry.getAuthor(), 200);
        message = FailureSignal.safe(entry.getMessage(), 2000);
        buildNumber = entry.getFromBuildNumber();
        this.group = group;
        this.strength = strength;
        this.reason = reason;
        this.limitation = limitation;
        this.nextCheck = nextCheck;
    }

    public static List<RankedChange> rank(List<ChangeEntry> entries, FailureSignal signal) {
        List<RankedChange> result = new ArrayList<>();
        String namedFile = signal.getFile();
        boolean ambiguousName = !namedFile.isEmpty()
                && !namedFile.contains("/")
                && entries.stream()
                                .limit(500)
                                .flatMap(e -> e.getAffectedFiles().stream().limit(2000))
                                .limit(2000)
                                .map(p -> p.replace('\\', '/'))
                                .filter(p -> p.equals(namedFile) || p.endsWith('/' + namedFile))
                                .distinct()
                                .limit(2)
                                .count()
                        > 1;
        changes:
        for (ChangeEntry entry : entries)
            for (String raw : entry.getAffectedFiles()) {
                if (result.size() >= 2000) break changes;
                String path = raw.replace('\\', '/'), file = signal.getFile();
                boolean direct = !file.isEmpty()
                        && (path.equals(file) || path.endsWith('/' + file) || file.endsWith('/' + path));
                boolean module = !signal.getModule().isEmpty() && path.startsWith(signal.getModule() + "/");
                boolean component = !signal.getStage().isEmpty()
                        && path.contains("/")
                        && signal.getStage().equals(path.substring(0, path.indexOf('/')));
                String group = "Other",
                        strength = "Weak evidence",
                        reason = "No direct file or observed stage/module relationship was found.",
                        limit = "An unmatched change is not proven harmless.",
                        next = "Inspect service inputs / runtime environment";
                if (direct && ambiguousName) {
                    group = "Possibly related";
                    strength = "Moderate evidence";
                    reason = "The diagnostic filename matches multiple changed paths.";
                    limit = "The retained diagnostic does not identify which matching source path failed.";
                    next = "Resolve the source path before inspecting the diff";
                } else if (direct) {
                    group = "Most relevant";
                    strength = "Strong evidence";
                    reason = "This file changed in the comparison window. The failure names the same source file.";
                    limit = "The failing line is in a changed file; the exact line is not verified as changed.";
                    next = "Inspect diff near line " + signal.getLine();
                } else if (module) {
                    group = "Most relevant";
                    strength = "Strong evidence";
                    reason = "This file changed in the module explicitly named by the failing build task: "
                            + signal.getModule() + ".";
                    limit =
                            "Module scope is observed; the changed line and loaded runtime artifact are not independently verified.";
                    next = "Inspect module diff and runtime classpath";
                } else if (component
                        || path.equals("Jenkinsfile") && !signal.getStage().isEmpty()) {
                    group = "Possibly related";
                    strength = "Moderate evidence";
                    reason = component
                            ? "The path component matches the observed failing stage name."
                            : "Pipeline configuration changed and a failing-stage label was observed.";
                    limit =
                            "Stage/name overlap is indirect; this does not establish that the changed configuration ran in that stage.";
                    next = "Inspect stage configuration and component diff";
                }
                result.add(new RankedChange(path, entry, group, strength, reason, limit, next));
            }
        result.sort(Comparator.comparingInt(
                r -> r.group.equals("Most relevant") ? 0 : r.group.equals("Possibly related") ? 1 : 2));
        return List.copyOf(result);
    }

    public String getPath() {
        return path;
    }

    public String getCommit() {
        return commit;
    }

    public String getAuthor() {
        return author;
    }

    public String getMessage() {
        return message;
    }

    public String getGroup() {
        return group;
    }

    public String getStrength() {
        return strength;
    }

    public String getReason() {
        return reason;
    }

    public String getLimitation() {
        return limitation;
    }

    public String getNextCheck() {
        return nextCheck;
    }

    public int getBuildNumber() {
        return buildNumber;
    }
}

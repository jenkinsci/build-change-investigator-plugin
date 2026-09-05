package io.jenkins.plugins.changeinvestigator.evidence;

import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.scm.RunWithSCM;

/**
 * Collects {@link BuildInvestigationEvidence} for a build using only generic, SCM-agnostic
 * Jenkins core APIs ({@link Run}, {@link hudson.scm.ChangeLogSet}). It never assumes Git;
 * any SCM plugin that populates a changelog works the same way here.
 *
 * <p>Every piece of evidence that Jenkins cannot supply is recorded as an explicit
 * "unavailable" warning rather than silently omitted or guessed.
 */
public final class EvidenceCollector {

    private static final Logger LOGGER = Logger.getLogger(EvidenceCollector.class.getName());

    /** Safety cap on how many prior builds we will walk back through to accumulate changesets. */
    private static final int MAX_BUILDS_TO_WALK = 200;

    private final int maxLogChars;

    public EvidenceCollector(int maxLogChars) {
        this.maxLogChars = maxLogChars;
    }

    public BuildInvestigationEvidence collect(Run<?, ?> failedBuild) {
        return collect(failedBuild, null);
    }

    public BuildInvestigationEvidence collect(Run<?, ?> failedBuild, Run<?, ?> baseline) {
        BuildInvestigationEvidence.Builder b = BuildInvestigationEvidence.builder();

        hudson.model.Result result = failedBuild.getResult();
        b.jobFullName(failedBuild.getParent().getFullName())
                .jobUrl(safeUrl(failedBuild.getParent().getUrl()))
                .failedBuildNumber(failedBuild.getNumber())
                .failedBuildResult(result == null ? "UNKNOWN" : result.toString())
                .failedBuildUrl(safeUrl(failedBuild.getUrl()))
                .failedBuildStartTimeMillis(failedBuild.getStartTimeInMillis())
                .failedBuildDurationMillis(failedBuild.getDuration())
                .failedBuildDurationString(failedBuild.getDurationString());

        collectNodeInfo(failedBuild, b);
        Run<?, ?> previousSuccessful = baseline == null ? collectPreviousSuccessful(failedBuild, b) : baseline;
        if (baseline != null) b.previousSuccessfulBuild(baseline.getNumber(), safeUrl(baseline.getUrl()));
        collectChanges(failedBuild, previousSuccessful, b);
        collectLog(failedBuild, b);

        return b.build();
    }

    private void collectNodeInfo(Run<?, ?> failedBuild, BuildInvestigationEvidence.Builder b) {
        if (failedBuild instanceof AbstractBuild<?, ?> abstractBuild) {
            String node = abstractBuild.getBuiltOnStr();
            b.node((node == null || node.isEmpty()) ? "built-in (controller)" : node);
        } else {
            // Pipeline builds may run across multiple agents; there is no single generic
            // "the node" for a Run. Recording this honestly rather than guessing one agent.
            b.node(null);
            b.addWarning("Agent/node information is not available for this build type "
                    + "(pipeline builds may execute across multiple agents).");
        }
    }

    private Run<?, ?> collectPreviousSuccessful(Run<?, ?> failedBuild, BuildInvestigationEvidence.Builder b) {
        Run<?, ?> previousSuccessful = failedBuild.getPreviousBuild();
        int searched = 0;
        while (previousSuccessful != null
                && !hudson.model.Result.SUCCESS.equals(previousSuccessful.getResult())
                && searched++ < MAX_BUILDS_TO_WALK) previousSuccessful = previousSuccessful.getPreviousBuild();
        if (searched >= MAX_BUILDS_TO_WALK) {
            previousSuccessful = null;
            b.addWarning("Successful baseline search reached the 200-build limit.");
        }
        if (previousSuccessful == null) {
            b.noPreviousSuccessfulBuild();
            if (failedBuild.getPreviousBuild() == null) {
                b.addWarning("This is the first build of the job; there is no prior build to compare against.");
            } else {
                b.addWarning("No previous successful build was found for this job. "
                        + "Change evidence will include everything Jenkins retains history for.");
            }
        } else {
            b.previousSuccessfulBuild(previousSuccessful.getNumber(), safeUrl(previousSuccessful.getUrl()));
        }
        return previousSuccessful;
    }

    private void collectChanges(
            Run<?, ?> failedBuild, Run<?, ?> previousSuccessful, BuildInvestigationEvidence.Builder b) {
        List<ChangeEntry> entries = new ArrayList<>();
        boolean anyChangeLogSupportSeen = false;
        String lastRevision = null;

        int previousSuccessfulNumber = previousSuccessful == null ? -1 : previousSuccessful.getNumber();
        Run<?, ?> cursor = failedBuild;
        int walked = 0;
        int retainedPaths = 0;
        while (cursor != null && cursor.getNumber() != previousSuccessfulNumber && walked < MAX_BUILDS_TO_WALK) {
            List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = safeGetChangeSets(cursor);
            for (ChangeLogSet<? extends ChangeLogSet.Entry> set : changeSets) {
                anyChangeLogSupportSeen = true;
                for (ChangeLogSet.Entry entry : set) {
                    if (entries.size() >= 500 || retainedPaths >= 2000) {
                        b.addWarning(
                                "Change evidence capped at 500 entries or 2000 paths; inspect native SCM changes for the full history.");
                        java.util.Collections.reverse(entries);
                        b.changeEntries(entries, true);
                        b.lastKnownRevision(lastRevision);
                        return;
                    }
                    ChangeEntry converted = convert(entry, cursor.getNumber(), 2000 - retainedPaths);
                    entries.add(converted);
                    retainedPaths += converted.getAffectedFiles().size();
                    if (converted.hasCommitId()) {
                        lastRevision = converted.getCommitId();
                    }
                }
            }
            cursor = cursor.getPreviousBuild();
            walked++;
        }

        if (retainedPaths >= 2000) {
            b.addWarning(
                    "Retained paths reached the 2000-path limit; inspect native SCM history for potentially omitted paths.");
        }
        if (walked >= MAX_BUILDS_TO_WALK) {
            b.addWarning("More than " + MAX_BUILDS_TO_WALK + " builds separate this failure from the last "
                    + "success; change history was capped and may be incomplete.");
        }

        // Oldest-first reading order.
        java.util.Collections.reverse(entries);

        if (entries.isEmpty()) {
            // Jenkins' generic RunWithSCM#getChangeSets() API does not reliably distinguish
            // "no SCM configured" from "SCM configured but reported zero changes" for every
            // build type (freestyle builds in particular omit an empty changeset from the list
            // entirely) - so this warning deliberately covers both rather than claiming a
            // distinction that cannot actually be observed.
            b.addWarning("No changes were reported between the last successful build and this failure "
                    + "(either no SCM is configured for this job, or the configured SCM reported zero "
                    + "changes). If changes are expected, the regression may be caused by something "
                    + "outside source control (infrastructure, external dependency, flaky test, "
                    + "environment change).");
        }

        // Revision on the failed build itself is preferred over any earlier build's revision.
        String headRevision = lastCommitIdInFailedBuild(failedBuild);
        b.changeEntries(entries, anyChangeLogSupportSeen);
        b.lastKnownRevision(headRevision != null ? headRevision : lastRevision);
    }

    private String lastCommitIdInFailedBuild(Run<?, ?> failedBuild) {
        int inspected = 0;
        for (ChangeLogSet<? extends ChangeLogSet.Entry> set : safeGetChangeSets(failedBuild)) {
            String last = null;
            for (ChangeLogSet.Entry entry : set) {
                if (inspected++ >= 500) return last;
                String id = safeCommitId(entry);
                if (id != null) {
                    last = id;
                }
            }
            if (last != null) {
                return last;
            }
        }
        return null;
    }

    private List<ChangeLogSet<? extends ChangeLogSet.Entry>> safeGetChangeSets(Run<?, ?> run) {
        try {
            // RunWithSCM is the SCM-agnostic API implemented by both AbstractBuild (freestyle)
            // and WorkflowRun (pipeline), so this works uniformly without a Git-specific or
            // freestyle-specific dependency.
            if (run instanceof RunWithSCM<?, ?> runWithScm) {
                List<ChangeLogSet<? extends ChangeLogSet.Entry>> sets = runWithScm.getChangeSets();
                return sets.subList(0, Math.min(sets.size(), 500));
            }
            return List.of();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unable to read change sets for " + run, e);
            return List.of();
        }
    }

    private ChangeEntry convert(ChangeLogSet.Entry entry, int buildNumber, int pathLimit) {
        String author = safeAuthor(entry);
        String commitId = safeCommitId(entry);
        String message = safeMessage(entry);
        List<String> affectedFiles = safeAffectedFiles(entry, pathLimit);
        long timestamp = safeTimestamp(entry);
        return new ChangeEntry(commitId, author, message, affectedFiles, timestamp, buildNumber);
    }

    private String safeAuthor(ChangeLogSet.Entry entry) {
        try {
            hudson.model.User user = entry.getAuthor();
            return user == null
                    ? null
                    : io.jenkins.plugins.changeinvestigator.investigation.FailureSignal.safe(user.getFullName(), 200);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safeCommitId(ChangeLogSet.Entry entry) {
        try {
            String id = entry.getCommitId();
            return (id == null || id.isBlank())
                    ? null
                    : io.jenkins.plugins.changeinvestigator.investigation.FailureSignal.safe(id, 100);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safeMessage(ChangeLogSet.Entry entry) {
        try {
            String msg = entry.getMsg();
            return io.jenkins.plugins.changeinvestigator.investigation.FailureSignal.safe(msg, 2000);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private List<String> safeAffectedFiles(ChangeLogSet.Entry entry, int limit) {
        try {
            return entry.getAffectedPaths().stream()
                    .limit(limit)
                    .map(path -> io.jenkins.plugins.changeinvestigator.investigation.FailureSignal.safe(path, 500))
                    .toList();
        } catch (RuntimeException e) {
            // Several SCM implementations throw UnsupportedOperationException here.
            return List.of();
        }
    }

    private long safeTimestamp(ChangeLogSet.Entry entry) {
        try {
            return entry.getTimestamp();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void collectLog(Run<?, ?> failedBuild, BuildInvestigationEvidence.Builder b) {
        try (BufferedReader reader = new BufferedReader(failedBuild.getLogReader())) {
            LogReducer.Result result = LogReducer.reduce(reader, Math.max(0, maxLogChars));
            if (result.lines.isEmpty()) {
                b.log(List.of(), false, false, result.linesScanned);
                b.addWarning("The console log for this build is empty or could not be read.");
            } else {
                b.log(result.lines, true, result.truncated, result.linesScanned);
                if (result.truncated) {
                    b.addWarning("The failure log excerpt was truncated to the configured maximum size.");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read console log for " + failedBuild, e);
            b.log(List.of(), false, false, 0);
            b.addWarning("The console log could not be read: " + e.getClass().getSimpleName());
        }
    }

    private static String safeUrl(String relativeUrl) {
        return relativeUrl == null ? "" : relativeUrl;
    }
}

package io.jenkins.plugins.changeinvestigator.evidence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete, deterministic evidence bundle collected from Jenkins for one investigation.
 *
 * <p>Every field here is either a directly observed Jenkins fact, or an explicit "not
 * available" marker recorded in {@link #getWarnings()}. Nothing in this class is inferred
 * or guessed - that is the job of the (optional) AI assessment layer, which is kept in a
 * separate object so observed evidence and AI interpretation are never conflated.
 */
public final class BuildInvestigationEvidence implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobFullName;
    private final String jobUrl;

    private final int failedBuildNumber;
    private final String failedBuildResult;
    private final String failedBuildUrl;
    private final long failedBuildStartTimeMillis;
    private final long failedBuildDurationMillis;
    private final String failedBuildDurationString;

    private final boolean previousSuccessfulBuildFound;
    private final int previousSuccessfulBuildNumber;
    private final String previousSuccessfulBuildUrl;

    private final boolean nodeInfoAvailable;
    private final String nodeName;

    private final boolean changeDataAvailable;
    private final List<ChangeEntry> changeEntries;
    private final String lastKnownRevision;

    private final boolean logAvailable;
    private final List<String> logExcerpt;
    private final boolean logExcerptTruncated;
    private final int logLinesScanned;

    private final List<String> warnings;
    private final long collectedAtMillis;
    private int changeWindowEnd;

    private BuildInvestigationEvidence(Builder b) {
        this.jobFullName = b.jobFullName;
        this.jobUrl = b.jobUrl;
        this.failedBuildNumber = b.failedBuildNumber;
        this.failedBuildResult = b.failedBuildResult;
        this.failedBuildUrl = b.failedBuildUrl;
        this.failedBuildStartTimeMillis = b.failedBuildStartTimeMillis;
        this.failedBuildDurationMillis = b.failedBuildDurationMillis;
        this.failedBuildDurationString = b.failedBuildDurationString;
        this.previousSuccessfulBuildFound = b.previousSuccessfulBuildFound;
        this.previousSuccessfulBuildNumber = b.previousSuccessfulBuildNumber;
        this.previousSuccessfulBuildUrl = b.previousSuccessfulBuildUrl;
        this.nodeInfoAvailable = b.nodeInfoAvailable;
        this.nodeName = b.nodeName;
        this.changeDataAvailable = b.changeDataAvailable;
        this.changeEntries = Collections.unmodifiableList(new ArrayList<>(b.changeEntries));
        this.lastKnownRevision = b.lastKnownRevision;
        this.logAvailable = b.logAvailable;
        this.logExcerpt = Collections.unmodifiableList(new ArrayList<>(b.logExcerpt));
        this.logExcerptTruncated = b.logExcerptTruncated;
        this.logLinesScanned = b.logLinesScanned;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(b.warnings));
        this.collectedAtMillis = b.collectedAtMillis;
    }

    /** Projects saved changes without changing the original evidence or reading SCM. */
    public BuildInvestigationEvidence forChangeWindow(List<ChangeEntry> entries, int end) {
        Builder b = builder()
                .jobFullName(jobFullName)
                .jobUrl(jobUrl)
                .failedBuildNumber(failedBuildNumber)
                .failedBuildResult(failedBuildResult)
                .failedBuildUrl(failedBuildUrl)
                .failedBuildStartTimeMillis(failedBuildStartTimeMillis)
                .failedBuildDurationMillis(failedBuildDurationMillis)
                .failedBuildDurationString(failedBuildDurationString)
                .node(nodeInfoAvailable ? nodeName : null)
                .changeEntries(entries, changeDataAvailable)
                .lastKnownRevision(lastKnownRevision)
                .log(logExcerpt, logAvailable, logExcerptTruncated, logLinesScanned);
        if (previousSuccessfulBuildFound)
            b.previousSuccessfulBuild(previousSuccessfulBuildNumber, previousSuccessfulBuildUrl);
        warnings.forEach(b::addWarning);
        b.collectedAtMillis = collectedAtMillis;
        BuildInvestigationEvidence projected = b.build();
        projected.changeWindowEnd = end;
        return projected;
    }

    public int getChangeWindowEnd() {
        return changeWindowEnd > 0 ? changeWindowEnd : failedBuildNumber;
    }

    public String getAiScope() {
        return "Changes "
                + (previousSuccessfulBuildFound ? "#" + previousSuccessfulBuildNumber : "baseline unavailable") + " → #"
                + getChangeWindowEnd() + "; failure signal and build metadata from #" + failedBuildNumber + ".";
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public String getJobUrl() {
        return jobUrl;
    }

    public int getFailedBuildNumber() {
        return failedBuildNumber;
    }

    public String getFailedBuildResult() {
        return failedBuildResult;
    }

    public String getFailedBuildUrl() {
        return failedBuildUrl;
    }

    public long getFailedBuildStartTimeMillis() {
        return failedBuildStartTimeMillis;
    }

    public long getFailedBuildDurationMillis() {
        return failedBuildDurationMillis;
    }

    public String getFailedBuildDurationString() {
        return failedBuildDurationString;
    }

    public boolean isPreviousSuccessfulBuildFound() {
        return previousSuccessfulBuildFound;
    }

    public int getPreviousSuccessfulBuildNumber() {
        return previousSuccessfulBuildNumber;
    }

    public String getPreviousSuccessfulBuildUrl() {
        return previousSuccessfulBuildUrl;
    }

    public boolean isNodeInfoAvailable() {
        return nodeInfoAvailable;
    }

    public String getNodeName() {
        return nodeName;
    }

    public boolean isChangeDataAvailable() {
        return changeDataAvailable;
    }

    public List<ChangeEntry> getChangeEntries() {
        return changeEntries;
    }

    public boolean hasLastKnownRevision() {
        return lastKnownRevision != null && !lastKnownRevision.isBlank();
    }

    public String getLastKnownRevision() {
        return lastKnownRevision;
    }

    public boolean isLogAvailable() {
        return logAvailable;
    }

    public List<String> getLogExcerpt() {
        return logExcerpt;
    }

    /**
     * The log excerpt as a single newline-joined string, for display. Views should prefer this
     * over iterating {@link #getLogExcerpt()} line-by-line in a template: a Jelly
     * {@code <j:forEach>} that tries to emit a literal newline between iterations is at the
     * mercy of the Jelly/XML parser's whitespace handling and can silently collapse those
     * newlines, running every line together in the rendered page.
     */
    public String getLogExcerptText() {
        return io.jenkins.plugins.changeinvestigator.investigation.FailureSignal.safe(
                String.join("\n", logExcerpt), 24000);
    }

    public boolean isLogExcerptTruncated() {
        return logExcerptTruncated;
    }

    public int getLogLinesScanned() {
        return logLinesScanned;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public long getCollectedAtMillis() {
        return collectedAtMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder used only during evidence collection. */
    public static final class Builder {
        private String jobFullName = "";
        private String jobUrl = "";
        private int failedBuildNumber;
        private String failedBuildResult = "UNKNOWN";
        private String failedBuildUrl = "";
        private long failedBuildStartTimeMillis;
        private long failedBuildDurationMillis;
        private String failedBuildDurationString = "";
        private boolean previousSuccessfulBuildFound;
        private int previousSuccessfulBuildNumber;
        private String previousSuccessfulBuildUrl;
        private boolean nodeInfoAvailable;
        private String nodeName;
        private boolean changeDataAvailable;
        private List<ChangeEntry> changeEntries = new ArrayList<>();
        private String lastKnownRevision;
        private boolean logAvailable;
        private List<String> logExcerpt = new ArrayList<>();
        private boolean logExcerptTruncated;
        private int logLinesScanned;
        private List<String> warnings = new ArrayList<>();
        private long collectedAtMillis = System.currentTimeMillis();

        public Builder jobFullName(String v) {
            this.jobFullName = v;
            return this;
        }

        public Builder jobUrl(String v) {
            this.jobUrl = v;
            return this;
        }

        public Builder failedBuildNumber(int v) {
            this.failedBuildNumber = v;
            return this;
        }

        public Builder failedBuildResult(String v) {
            this.failedBuildResult = v;
            return this;
        }

        public Builder failedBuildUrl(String v) {
            this.failedBuildUrl = v;
            return this;
        }

        public Builder failedBuildStartTimeMillis(long v) {
            this.failedBuildStartTimeMillis = v;
            return this;
        }

        public Builder failedBuildDurationMillis(long v) {
            this.failedBuildDurationMillis = v;
            return this;
        }

        public Builder failedBuildDurationString(String v) {
            this.failedBuildDurationString = v;
            return this;
        }

        public Builder previousSuccessfulBuild(int number, String url) {
            this.previousSuccessfulBuildFound = true;
            this.previousSuccessfulBuildNumber = number;
            this.previousSuccessfulBuildUrl = url;
            return this;
        }

        public Builder noPreviousSuccessfulBuild() {
            this.previousSuccessfulBuildFound = false;
            return this;
        }

        public Builder node(String name) {
            this.nodeInfoAvailable = name != null;
            this.nodeName = name;
            return this;
        }

        public Builder changeEntries(List<ChangeEntry> entries, boolean available) {
            this.changeEntries = new ArrayList<>(entries);
            this.changeDataAvailable = available;
            return this;
        }

        public Builder lastKnownRevision(String revision) {
            this.lastKnownRevision = revision;
            return this;
        }

        public Builder log(List<String> excerpt, boolean available, boolean truncated, int linesScanned) {
            this.logExcerpt = new ArrayList<>(excerpt);
            this.logAvailable = available;
            this.logExcerptTruncated = truncated;
            this.logLinesScanned = linesScanned;
            return this;
        }

        public Builder addWarning(String warning) {
            this.warnings.add(warning);
            return this;
        }

        public BuildInvestigationEvidence build() {
            return new BuildInvestigationEvidence(this);
        }
    }
}

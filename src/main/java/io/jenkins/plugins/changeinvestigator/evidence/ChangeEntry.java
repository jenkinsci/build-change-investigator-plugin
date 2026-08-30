package io.jenkins.plugins.changeinvestigator.evidence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single SCM change entry (commit / revision) observed between the last successful
 * build and the failed build under investigation. Populated only from data Jenkins'
 * generic {@link hudson.scm.ChangeLogSet.Entry} API actually exposes; fields are left
 * at their documented "unknown" defaults rather than guessed when a concrete SCM
 * implementation does not provide them.
 */
public final class ChangeEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String UNKNOWN_AUTHOR = "unknown";

    private final String commitId;
    private final String author;
    private final String message;
    private final List<String> affectedFiles;
    private final long timestampMillis;
    private final int fromBuildNumber;

    public ChangeEntry(String commitId, String author, String message, List<String> affectedFiles,
                        long timestampMillis, int fromBuildNumber) {
        this.commitId = commitId;
        this.author = (author == null || author.isBlank()) ? UNKNOWN_AUTHOR : author;
        this.message = message == null ? "" : message;
        this.affectedFiles = affectedFiles == null ? Collections.emptyList() : new ArrayList<>(affectedFiles);
        this.timestampMillis = timestampMillis;
        this.fromBuildNumber = fromBuildNumber;
    }

    public String getCommitId() {
        return commitId;
    }

    public boolean hasCommitId() {
        return commitId != null && !commitId.isBlank();
    }

    public String getAuthor() {
        return author;
    }

    public String getMessage() {
        return message;
    }

    /** First line of {@link #getMessage()}, convenient for compact UI rendering. */
    public String getShortMessage() {
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }

    public List<String> getAffectedFiles() {
        return Collections.unmodifiableList(affectedFiles);
    }

    public boolean hasTimestamp() {
        return timestampMillis > 0;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public int getFromBuildNumber() {
        return fromBuildNumber;
    }
}

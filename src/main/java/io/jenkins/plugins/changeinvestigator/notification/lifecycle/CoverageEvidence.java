package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import java.io.Serializable;
import java.util.Objects;

/** A named producer's evidence about one affected check in one run/context. */
public record CoverageEvidence(
        String source,
        int sourceVersion,
        String checkIdentity,
        String runId,
        String contextDigest,
        boolean executed,
        boolean comparable)
        implements Serializable {
    public CoverageEvidence {
        source = bounded(source);
        checkIdentity = bounded(checkIdentity);
        runId = bounded(runId);
        contextDigest = bounded(contextDigest);
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("Invalid coverage version");
        }
    }

    public static CoverageEvidence unknown() {
        return new CoverageEvidence("", 0, "", "", "", false, false);
    }

    public boolean verifies(String expectedRun, String expectedContext, String affectedCheck) {
        return executed
                && comparable
                && sourceVersion > 0
                && !source.isBlank()
                && !checkIdentity.isBlank()
                && !runId.isBlank()
                && !contextDigest.isBlank()
                && runId.equals(expectedRun)
                && contextDigest.equals(expectedContext)
                && checkIdentity.equals(affectedCheck);
    }

    private static String bounded(String value) {
        value = Objects.requireNonNullElse(value, "");
        if (value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)
                || !value.equals(
                        io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(value, 512))) {
            throw new IllegalArgumentException("Invalid coverage identifier");
        }
        return value;
    }
}

package io.jenkins.plugins.changeinvestigator.evidence;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reduces a (potentially huge) console log down to a small, bounded set of lines that are
 * likely relevant to diagnosing a build failure, without ever sending the entire log
 * anywhere. Secrets are redacted before any line leaves this class.
 */
public final class LogReducer {

    /** Hard cap on how many lines of the raw log this reducer will ever read, regardless of config. */
    public static final int MAX_LINES_SCANNED = 50_000;

    private static final int CONTEXT_LINES_BEFORE = 2;
    private static final int CONTEXT_LINES_AFTER = 4;
    private static final int FALLBACK_TAIL_LINES = 60;

    private static final char ESC = 0x1B;

    // Real ANSI escape sequences always start with the ESC control character, so we require
    // that literal prefix - this guarantees ordinary bracketed log text such as "[INFO]" or
    // "[ERROR]" (extremely common in build tool output) is never touched.
    private static final Pattern ANSI_CSI = Pattern.compile(ESC + "\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern ANSI_OSC = Pattern.compile(ESC + "\\][^\\x07]*(\\x07|" + ESC + "\\\\)");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    private static final List<Pattern> INTERESTING_PATTERNS = List.of(
            Pattern.compile("(?i)\\bERROR\\b"),
            Pattern.compile("(?i)\\bFATAL\\b"),
            Pattern.compile("(?i)\\bException\\b"),
            Pattern.compile("(?i)Caused by:"),
            Pattern.compile("(?i)\\bFAILURE\\b"),
            Pattern.compile("(?i)\\bfailed\\b"),
            Pattern.compile("(?i)non-zero exit"),
            Pattern.compile("(?i)exit (code|status)[:=]?\\s*[1-9]"),
            Pattern.compile("(?i)compilation (error|failed)"),
            Pattern.compile("(?i)could not resolve"),
            Pattern.compile("(?i)dependency.*not found"),
            Pattern.compile("(?i)test(s)? failed"),
            Pattern.compile("(?i)assertion(Error)?"),
            Pattern.compile("(?i)connection (refused|reset|timed out)"),
            Pattern.compile("(?i)authentication failed"),
            Pattern.compile("(?i)permission denied"),
            Pattern.compile("(?i)no such file or directory"),
            Pattern.compile("(?i)npm ERR!"),
            Pattern.compile("(?i)BUILD FAILED"));

    private LogReducer() {
    }

    public static final class Result {
        public final List<String> lines;
        public final boolean truncated;
        public final int linesScanned;

        Result(List<String> lines, boolean truncated, int linesScanned) {
            this.lines = lines;
            this.truncated = truncated;
            this.linesScanned = linesScanned;
        }
    }

    /**
     * Reads from {@code reader}, extracts likely-relevant lines (or the tail of the log if
     * nothing matches), redacts secrets, cleans ANSI/control noise, and bounds the total
     * output to {@code maxChars} characters.
     */
    public static Result reduce(BufferedReader reader, int maxChars) throws IOException {
        List<String> allLines = new ArrayList<>();
        int scanned = 0;
        boolean moreAvailable = false;
        String raw;
        while ((raw = reader.readLine()) != null) {
            if (scanned >= MAX_LINES_SCANNED) {
                moreAvailable = true;
                break;
            }
            allLines.add(clean(raw));
            scanned++;
        }

        TreeSet<Integer> selectedIndexes = new TreeSet<>();
        for (int i = 0; i < allLines.size(); i++) {
            if (matchesInteresting(allLines.get(i))) {
                int from = Math.max(0, i - CONTEXT_LINES_BEFORE);
                int to = Math.min(allLines.size() - 1, i + CONTEXT_LINES_AFTER);
                for (int j = from; j <= to; j++) {
                    selectedIndexes.add(j);
                }
            }
        }

        List<String> output = new ArrayList<>();
        boolean usedFallback = false;
        if (selectedIndexes.isEmpty() && !allLines.isEmpty()) {
            usedFallback = true;
            int start = Math.max(0, allLines.size() - FALLBACK_TAIL_LINES);
            for (int i = start; i < allLines.size(); i++) {
                selectedIndexes.add(i);
            }
        }

        Integer previous = null;
        for (Integer idx : selectedIndexes) {
            if (previous != null && idx - previous > 1) {
                output.add("[... omitted " + (idx - previous - 1) + " line(s) ...]");
            }
            output.add(allLines.get(idx));
            previous = idx;
        }

        String joined = SecretRedactor.redactMultiline(String.join("\n", output));
        boolean truncated = moreAvailable;
        if (joined.length() > maxChars) {
            joined = joined.substring(0, Math.max(0, maxChars));
            truncated = true;
        }
        List<String> finalLines = joined.isEmpty() ? List.of() : List.of(joined.split("\n", -1));
        if (usedFallback && !finalLines.isEmpty()) {
            List<String> withNote = new ArrayList<>();
            withNote.add("[No explicit error/failure keywords matched; showing the last "
                    + FALLBACK_TAIL_LINES + " lines of the log instead.]");
            withNote.addAll(finalLines);
            finalLines = withNote;
        }
        return new Result(finalLines, truncated, scanned);
    }

    private static boolean matchesInteresting(String line) {
        for (Pattern p : INTERESTING_PATTERNS) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String line) {
        String noAnsi = ANSI_OSC.matcher(line).replaceAll("");
        noAnsi = ANSI_CSI.matcher(noAnsi).replaceAll("");
        noAnsi = CONTROL_CHARS.matcher(noAnsi).replaceAll("");
        return SecretRedactor.redact(noAnsi);
    }
}

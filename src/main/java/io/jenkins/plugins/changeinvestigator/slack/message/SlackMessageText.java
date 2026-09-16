package io.jenkins.plugins.changeinvestigator.slack.message;

import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Compact presentation of retained evidence; never used for ranking or episode identity. */
final class SlackMessageText {
    private static final Pattern FILE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_./\\\\:])(?:[A-Za-z]:[\\\\/]|/)[^\\r\\n]*?([A-Za-z0-9_$.-]+\\.[A-Za-z0-9_+-]{1,20})(?=[:\\[\\s]|$)");

    private SlackMessageText() {}

    static String summary(long changes, long files) {
        return changes + (changes == 1 ? " change" : " changes") + " · " + files
                + (files == 1 ? " file changed" : " files changed");
    }

    static String failure(String raw) {
        String bounded = SlackSnapshot.safe(raw, 1200);
        FailureSignal signal = FailureSignal.extract(bounded.lines().toList());
        String file = signal.getFile();
        String location = file.substring(file.lastIndexOf('/') + 1)
                + (signal.getLine().isBlank() ? "" : ":" + signal.getLine())
                + (signal.getColumn().isBlank() ? "" : ":" + signal.getColumn());
        if (!file.isBlank() && bounded.contains("cannot find symbol")) {
            String symbol = signal.getSymbol();
            if (symbol.isBlank()) {
                var inline =
                        Pattern.compile("cannot find symbol:\\s*([^\\r\\n]+)").matcher(bounded);
                if (inline.find()) symbol = inline.group(1);
            }
            return location + "\ncannot find symbol" + (symbol.isBlank() ? "" : ": " + symbol);
        }
        return SlackSnapshot.safe(
                bounded.lines()
                        .limit(3)
                        .map(line ->
                                FILE_PATH.matcher(line).replaceAll("$1").replaceFirst("^\\[(?:ERROR|INFO)\\]\\s*", ""))
                        .distinct()
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("Failure details unavailable."),
                700);
    }

    static String reason(String value) {
        return "This file changed in the comparison window. The failure names the same source file.".equals(value)
                ? "The failure names the same source file changed since the last known-good build."
                : value;
    }

    static String limitation(String value, String failure) {
        if (!"The failing line is in a changed file; the exact line is not verified as changed.".equals(value))
            return value;
        String line = FailureSignal.extract(failure.lines().toList()).getLine();
        return line.isBlank()
                ? "The failing line has not been verified as changed."
                : "BCI has not verified that line " + line + " itself changed.";
    }

    static String check(String value) {
        String result = value.replaceFirst("^Inspect diff near line ", "Inspect the diff near line ");
        return result.isBlank() || result.endsWith(".") ? result : result + ".";
    }

    static String strength(String value) {
        return switch (value) {
            case "Strong evidence", "Strong" -> "Strong";
            case "Moderate evidence", "Moderate" -> "Moderate";
            case "Weak evidence", "Weak" -> "Weak";
            default -> "";
        };
    }

    static final String NO_RESOLUTION =
            "Review the identified change and failing check manually; the available evidence is not sufficient to recommend a specific fix.";

    static String source(String value) {
        String path = value.replace('\\', '/');
        return SlackSnapshot.safe(path.substring(path.lastIndexOf('/') + 1), 160);
    }

    static String resolution(String explanation, List<String> checks, String deterministicCheck, boolean insufficient) {
        if (insufficient || checks == null) return NO_RESOLUTION;
        String issue = compactAi(explanation, deterministicCheck);
        for (String check : checks.stream().limit(3).toList()) {
            String candidate = compactAi(check, deterministicCheck);
            if (!normalized(candidate).isBlank() && !normalized(issue).contains(normalized(candidate))) {
                return candidate;
            }
        }
        return NO_RESOLUTION;
    }

    static String compactAi(String value, String deterministicCheck) {
        String compactPaths = FILE_PATH
                .matcher(SlackSnapshot.safe(value, 8000))
                .replaceAll("$1")
                .replaceAll("(?:[A-Za-z0-9_.$~-]+[/\\\\])+([A-Za-z0-9_.$~-]+\\.[A-Za-z0-9_+-]+)", "$1");
        return SlackSnapshot.safe(
                java.util.Arrays.stream(compactPaths.split("\\r?\\n|(?<=[.!?])\\s+"))
                        .filter(line -> !sameCheck(line, deterministicCheck))
                        .filter(line -> !line.trim().equals("Interpretation only, not a confirmed cause."))
                        .distinct()
                        .limit(3)
                        .reduce((a, b) -> a + " " + b)
                        .orElse(""),
                350);
    }

    private static boolean sameCheck(String value, String deterministic) {
        return normalized(value).equals(normalized(deterministic))
                || normalized(value).equals(normalized(check(deterministic)));
    }

    private static String normalized(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceFirst("^suggested check:\\s*", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}

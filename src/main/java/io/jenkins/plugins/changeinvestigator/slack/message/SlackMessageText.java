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

    static String ai(String explanation, List<String> checks, String deterministicCheck) {
        String result = compactAi(SlackSnapshot.safe(explanation, 350), deterministicCheck);
        for (String check : checks.stream().limit(3).toList()) {
            String candidate = SlackSnapshot.safe(check, 180);
            if (!normalized(candidate).isBlank()
                    && !sameCheck(candidate, deterministicCheck)
                    && !normalized(result).contains(normalized(candidate))) {
                result += (result.isBlank() ? "" : " ") + candidate;
                break;
            }
        }
        return compactAi(result, deterministicCheck);
    }

    static String compactAi(String value, String deterministicCheck) {
        return java.util.Arrays.stream(SlackSnapshot.safe(value, 600).split("\\r?\\n|(?<=[.!?])\\s+"))
                .filter(line -> !sameCheck(line, deterministicCheck))
                .filter(line -> !line.trim().equals("Interpretation only, not a confirmed cause."))
                .distinct()
                .reduce((a, b) -> a + " " + b)
                .orElse("");
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

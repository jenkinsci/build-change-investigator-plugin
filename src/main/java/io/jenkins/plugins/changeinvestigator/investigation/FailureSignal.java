package io.jenkins.plugins.changeinvestigator.investigation;

import io.jenkins.plugins.changeinvestigator.evidence.SecretRedactor;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structured, bounded observations from a redacted console excerpt. */
public final class FailureSignal implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern LOCATION =
            Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.java):(?:\\[(\\d+),(\\d+)\\]|(\\d+)(?::(\\d+))?)");
    private static final Pattern EXCEPTION = Pattern.compile("(?:[\\w$]+\\.)*([\\w$]+(?:Exception|Error))(?::|\\b)");
    private static final Pattern STACK = Pattern.compile("at\\s+([\\w.$]+)\\(([^():]+\\.java):(\\d+)\\)");
    private static final Pattern STAGE = Pattern.compile("\\[Pipeline\\] \\{ \\(([^)]+)\\)");
    private static final Pattern TASK = Pattern.compile("> Task :([\\w:-]+)(?: FAILED)?");
    private static final Pattern TEST = Pattern.compile(
            "(?:^|\\s)([\\w.$]+(?: > [\\w$]+(?:\\(\\))?|\\.[\\w$]+))\\s+(?:FAILED|-- Time elapsed:.*<<< (?:FAILURE|ERROR))");
    private final String category, file, line, column, symbol, method, exception, test, stage, module, text;
    private final List<String> supportingLines;

    private FailureSignal(
            String category,
            String file,
            String line,
            String column,
            String symbol,
            String method,
            String exception,
            String test,
            String stage,
            String module,
            String text,
            List<String> lines) {
        this.category = category;
        this.file = file;
        this.line = line;
        this.column = column;
        this.symbol = symbol;
        this.method = method;
        this.exception = exception;
        this.test = test;
        this.stage = stage;
        this.module = module;
        this.text = text;
        this.supportingLines = List.copyOf(lines);
    }

    public static FailureSignal extract(List<String> source) {
        List<String> lines = new ArrayList<>();
        if (source != null)
            for (String value : source) {
                if (lines.size() >= 400) break;
                lines.add(safe(value, 2000));
            }
        String category = "Build failure",
                file = "",
                line = "",
                column = "",
                symbol = "",
                method = "",
                exception = "",
                test = "",
                stage = "",
                module = "";
        int best = -1, score = 0;
        String activeStage = "", activeModule = "";
        for (int i = 0; i < lines.size(); i++) {
            String value = lines.get(i);
            Matcher st = STAGE.matcher(value);
            if (st.find()) activeStage = st.group(1);
            Matcher task = TASK.matcher(value);
            if (task.find()) {
                activeStage = task.group(1);
                int cut = activeStage.lastIndexOf(':');
                activeModule = cut > 0 ? activeStage.substring(0, cut).replace(':', '/') : "";
            }
            Matcher maven =
                    Pattern.compile("--- .*?\\(([^)]+)\\) @ ([\\w.-]+) ---").matcher(value);
            if (maven.find()) {
                activeStage = maven.group(1);
                activeModule = maven.group(2);
            }
            Matcher loc = LOCATION.matcher(value), ex = EXCEPTION.matcher(value), te = TEST.matcher(value);
            int rank = 0;
            if (loc.find() && (value.contains("error") || value.contains("[ERROR]"))) rank = 5;
            else if (value.contains("NoSuchMethodError") || value.contains("NoClassDefFoundError")) rank = 4;
            else if (te.find()) rank = 3;
            else if (ex.find() && !value.contains("at ")) rank = 2;
            else if (value.matches("(?i).*(error|failed|failure|cannot find symbol).*")) rank = 1;
            if (rank > score) {
                score = rank;
                best = i;
                stage = activeStage;
                module = activeModule;
            }
        }
        if (best < 0) best = lines.isEmpty() ? -1 : Math.max(0, lines.size() - 3);
        List<String> supporting = new ArrayList<>();
        if (best >= 0) for (int i = best; i < Math.min(lines.size(), best + 6); i++) supporting.add(lines.get(i));
        String text = String.join("\n", supporting);
        Matcher loc = LOCATION.matcher(text);
        if (loc.find()) {
            file = loc.group(1).replace('\\', '/');
            line = loc.group(2) != null ? loc.group(2) : loc.group(4);
            column = loc.group(3) != null ? loc.group(3) : loc.group(5);
            if (score == 5) category = "Compilation error";
        }
        Matcher ex = EXCEPTION.matcher(text);
        if (ex.find()) {
            exception = ex.group();
            if (exception.endsWith(":")) exception = exception.substring(0, exception.length() - 1);
            if (!category.equals("Compilation error")) category = "Java exception";
        }
        Matcher stack = STACK.matcher(text);
        if (stack.find()) {
            method = stack.group(1);
            if (file.isEmpty()) {
                file = stack.group(2);
                line = stack.group(3);
            }
        }
        Matcher sym = Pattern.compile("symbol:\\s*(?:variable|method|class)?\\s*([^\\n]+)")
                .matcher(text);
        if (sym.find()) symbol = sym.group(1).trim();
        Matcher te = TEST.matcher(text);
        if (te.find()) {
            test = te.group(1);
            category = "Test failure";
        }
        if (text.contains("NoSuchMethodError") || text.contains("NoClassDefFoundError")) {
            category = "Dependency / API failure";
            Matcher api = Pattern.compile("(?:NoSuchMethodError|NoClassDefFoundError):\\s*([^\\n]+)")
                    .matcher(text);
            if (api.find()) symbol = api.group(1).trim();
        }
        if (text.isBlank()) text = "No structured failure signal is available in the retained log.";
        return new FailureSignal(
                category,
                file,
                line == null ? "" : line,
                column == null ? "" : column,
                symbol,
                method,
                exception,
                test,
                stage,
                module,
                safe(compact(text), 3000),
                supporting);
    }

    private static String compact(String text) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            if (result.isEmpty()
                    || line.matches(".*(symbol:|location:|\\bat\\s|Caused by:|cannot find symbol|\\.java:).*"))
                result.add(line.replaceFirst("^\\[INFO\\]\\s*(?:\\[exec\\]\\s*)?", ""));
        }
        return String.join("\n", result);
    }

    public String getSupportingText() {
        return String.join("\n", supportingLines);
    }

    public static String safe(String value, int max) {
        if (value == null) return "";
        String bounded = value.substring(0, Math.min(value.length(), Math.max(max, 8000)));
        String redacted = SecretRedactor.redactMultiline(String.join(
                "\n",
                SecretRedactor.redactMultiline(bounded)
                        .lines()
                        .map(SecretRedactor::redact)
                        .toList()));
        return redacted.substring(0, Math.min(max, redacted.length()));
    }

    public String getCategory() {
        return category;
    }

    public String getFile() {
        return file;
    }

    public String getLine() {
        return line;
    }

    public String getColumn() {
        return column;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getMethod() {
        return method;
    }

    public String getException() {
        return exception;
    }

    public String getTest() {
        return test;
    }

    public String getStage() {
        return stage;
    }

    public String getModule() {
        return module;
    }

    public String getText() {
        return text;
    }

    public String getLocation() {
        return file + (line.isEmpty() ? "" : ":" + line) + (column.isEmpty() ? "" : ":" + column);
    }

    public List<String> getSupportingLines() {
        return supportingLines;
    }

    public boolean isSpecific() {
        return !exception.isEmpty() || !file.isEmpty() || !test.isEmpty();
    }

    public String getSignature() {
        return isSpecific()
                ? String.join(
                        "|",
                        category,
                        exception,
                        file,
                        line,
                        symbol,
                        method,
                        test,
                        stage,
                        module,
                        text.lines().findFirst().orElse(""))
                : "";
    }
}

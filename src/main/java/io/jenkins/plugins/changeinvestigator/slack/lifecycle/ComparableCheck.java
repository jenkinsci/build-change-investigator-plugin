package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative recovery evidence from actual Maven compiler execution, not the build result alone. */
public final class ComparableCheck {
    private static final Pattern HEADER = Pattern.compile(
            "^\\[INFO\\] --- (?:maven-compiler-plugin|compiler):([A-Za-z0-9_.-]+):(compile|testCompile) \\(([^)]+)\\) @ ([A-Za-z0-9_.-]+) ---$");
    private static final Pattern COMPILED = Pattern.compile("^\\[INFO\\] Compiling [1-9][0-9]* source files?(?: .*)?$");
    private static final int MAX_CHARACTERS = 1024 * 1024;

    private ComparableCheck() {}

    public static final class Check {
        public String kind;
        public String context;
        public String module;
        public String goal;
        public String execution;
        public String source;
        public String version;
    }

    /** Constructed only by positive execution checks; callers cannot manufacture a green proof. */
    public static final class Proof {
        private final Check check;
        private final String explanation;

        private Proof(Check check, String explanation) {
            this.check = check;
            this.explanation = explanation;
        }

        public String getExplanation() {
            return explanation;
        }

        public boolean matches(Check expected) {
            return expected != null
                    && Objects.equals(check.kind, expected.kind)
                    && Objects.equals(check.context, expected.context)
                    && Objects.equals(check.module, expected.module)
                    && Objects.equals(check.goal, expected.goal)
                    && Objects.equals(check.execution, expected.execution)
                    && Objects.equals(check.version, expected.version)
                    && Objects.equals(check.source, expected.source);
        }
    }

    public static Check failed(Run<?, ?> run, FailureSignal signal, String executionContext) throws IOException {
        Check test = failedTest(run, signal, executionContext);
        if (test != null) return test;
        return failed(read(run), signal, executionContext);
    }

    public static Check failed(List<String> lines, FailureSignal signal, String executionContext) {
        if (!signal.getCategory().equals("Compilation error") || executionContext == null || executionContext.isBlank())
            return null;
        Check active = null;
        for (String line : lines) {
            if (line.equals("[INFO] BUILD FAILURE") || line.startsWith("[ERROR] Failed to execute goal")) active = null;
            Matcher header = HEADER.matcher(line);
            if (header.matches()) active = check(header, executionContext, signal.getFile());
            else if (line.startsWith("[INFO] --- ")) active = null;
            if (active != null
                    && line.contains("[ERROR]")
                    && line.contains(signal.getFile())
                    && (line.contains("cannot find symbol") || line.contains("error") || line.contains(":[")))
                return active;
        }
        return null;
    }

    public static Proof passed(Run<?, ?> run, Check expected, String executionContext) throws IOException {
        if (run.isBuilding() || !Result.SUCCESS.equals(run.getResult())) return null;
        if (expected != null && "junit-test".equals(expected.kind)) {
            if (!Objects.equals(expected.context, executionContext)) return null;
            List<TestCase> matches = tests(run).stream()
                    .filter(t -> t.identity().equals(expected.source))
                    .toList();
            return matches.size() == 1
                            && (matches.get(0).status().equals("PASSED")
                                    || matches.get(0).status().equals("FIXED"))
                    ? new Proof(expected, "The affected test ran and passed in the published JUnit results.")
                    : null;
        }
        return passed(read(run), expected, executionContext, true);
    }

    public static Proof passed(List<String> lines, Check expected, String executionContext, boolean successful) {
        if (!successful
                || expected == null
                || !"maven-compile".equals(expected.kind)
                || !Objects.equals(expected.context, executionContext)) return null;
        boolean matching = false, compiled = false, completed = false, buildSuccess = false;
        int occurrences = 0;
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (line.startsWith("[INFO] --- ")) {
                if (matching && compiled) completed = true;
                matching = header.matches()
                        && new Proof(check(header, executionContext, expected.source), "").matches(expected);
                compiled = false;
                if (matching) occurrences++;
            }
            if (matching && COMPILED.matcher(line).matches()) compiled = true;
            if (matching
                    && (line.toLowerCase(java.util.Locale.ROOT).contains("skip")
                            || line.contains("Nothing to compile")
                            || line.contains("NO-SOURCE")
                            || line.contains("UP-TO-DATE")
                            || line.contains("[ERROR]"))) return null;
            if (line.equals("[INFO] BUILD SUCCESS")) {
                if (matching && compiled) completed = true;
                buildSuccess = true;
            }
            if (line.equals("[INFO] BUILD FAILURE")) return null;
        }
        return completed && buildSuccess && occurrences == 1
                ? new Proof(expected, "The affected Maven compilation check executed and completed successfully.")
                : null;
    }

    private static Check check(Matcher header, String context, String source) {
        Check check = new Check();
        check.kind = "maven-compile";
        check.context = context;
        check.version = header.group(1);
        check.goal = header.group(2);
        check.execution = header.group(3);
        check.module = header.group(4);
        check.source = source;
        return check;
    }

    private static List<String> read(Run<?, ?> run) throws IOException {
        List<String> result = new ArrayList<>();
        int characters = 0;
        try (BufferedReader reader = new BufferedReader(run.getLogReader())) {
            StringBuilder line = new StringBuilder();
            int character;
            while ((character = reader.read()) != -1) {
                if (++characters > MAX_CHARACTERS || result.size() >= 20000 || line.length() > 8000) return List.of();
                if (character == '\n') {
                    result.add(line.toString().replace("\r", ""));
                    line.setLength(0);
                } else line.append((char) character);
            }
            if (!line.isEmpty()) result.add(line.toString().replace("\r", ""));
        }
        return result;
    }

    private record TestCase(String identity, String status) {}

    private static Check failedTest(Run<?, ?> run, FailureSignal signal, String context) {
        if (!signal.getCategory().equals("Test failure")
                || signal.getTest().isEmpty()
                || context == null
                || context.isBlank()) return null;
        String identity = signal.getTest().replace(" > ", ".").replace("()", "");
        List<TestCase> matches = tests(run).stream()
                .filter(t -> t.identity().equals(identity))
                .filter(t -> t.status().equals("FAILED") || t.status().equals("REGRESSION"))
                .toList();
        if (matches.size() != 1) return null;
        Check check = new Check();
        check.kind = "junit-test";
        check.context = context;
        check.module = signal.getModule();
        check.goal = "published-junit";
        check.execution = signal.getStage();
        check.source = identity;
        check.version = "1";
        return check;
    }

    /** Fixed optional-plugin API only; never resolve a class or method supplied by build content. */
    private static List<TestCase> tests(Run<?, ?> run) {
        List<TestCase> tests = new ArrayList<>();
        try {
            for (hudson.model.Action action : run.getAllActions()) {
                if (!action.getClass().getName().equals("hudson.tasks.junit.TestResultAction")) continue;
                Object result = action.getClass().getMethod("getResult").invoke(action);
                Object suites = result.getClass().getMethod("getSuites").invoke(result);
                if (!(suites instanceof Iterable<?> iterable)) return List.of();
                int suiteCount = 0;
                for (Object suite : iterable) {
                    if (++suiteCount > 2000) return List.of();
                    Object cases = suite.getClass().getMethod("getCases").invoke(suite);
                    if (!(cases instanceof Iterable<?> caseIterable)) return List.of();
                    for (Object test : caseIterable) {
                        if (tests.size() >= 10000) return List.of();
                        String className = String.valueOf(
                                test.getClass().getMethod("getClassName").invoke(test));
                        String methodName = String.valueOf(
                                test.getClass().getMethod("getName").invoke(test));
                        if (className.length() > 1000 || methodName.length() > 1000) return List.of();
                        String name = className + "." + methodName;
                        Object rawStatus =
                                test.getClass().getMethod("getStatus").invoke(test);
                        String status = rawStatus instanceof Enum<?> value ? value.name() : String.valueOf(rawStatus);
                        tests.add(new TestCase(name, status));
                    }
                }
            }
            return tests;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }
}

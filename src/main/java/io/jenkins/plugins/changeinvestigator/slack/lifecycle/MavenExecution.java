package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import hudson.model.Result;
import hudson.model.Run;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jenkins.model.Jenkins;

/** Fixed optional Maven plugin APIs; unavailable or ambiguous evidence remains unverified. */
final class MavenExecution {
    private MavenExecution() {}

    static boolean isModuleSet(Run<?, ?> run) {
        return run.getClass().getName().equals("hudson.maven.MavenModuleSetBuild")
                && run.getParent().getClass().getName().equals("hudson.maven.MavenModuleSet");
    }

    static String configuration(Run<?, ?> run) {
        if (!isModuleSet(run)) return null;
        Object project = run.getParent();
        try {
            if (!Boolean.TRUE.equals(call(project, "isAggregatorStyleBuild"))) return null;
            String goals = String.valueOf(call(project, "getGoals"));
            // Interleaved parallel reactor logs cannot establish which module produced a compile count.
            if (goals.length() > 128000) return null;
            for (String argument : hudson.Util.tokenize(goals)) {
                if (argument.startsWith("-T") || argument.equals("--threads") || argument.startsWith("--threads="))
                    return null;
            }
            StringBuilder configuration = new StringBuilder("maven-module-set\n");
            for (String method : List.of(
                    "getGoals",
                    "getRootPOM",
                    "getMaven",
                    "getMavenOpts",
                    "getJDK",
                    "isAggregatorStyleBuild",
                    "isIncrementalBuild",
                    "getSettings",
                    "getGlobalSettings",
                    "getLocalRepository",
                    "getPrebuilders",
                    "getPostbuilders",
                    "getReporters",
                    "getPublishersList",
                    "getBuildWrappersList",
                    "getScm")) {
                Object value = call(project, method);
                if (value instanceof hudson.util.DescribableList<?, ?> values)
                    value = new java.util.ArrayList<>(values.toList());
                configuration
                        .append(method)
                        .append(':')
                        .append(Jenkins.XSTREAM2.toXML(value))
                        .append('\n');
                if (configuration.length() > 128000) return null;
            }
            return configuration.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Mojo metadata corroborates the log proof; it alone does not prove compilation was not skipped. */
    static boolean completed(Run<?, ?> run, ComparableCheck.Check expected) {
        if (!isModuleSet(run)) return true;
        if (expected == null) return false;
        try {
            Object raw = call(run, "getModuleBuilds");
            if (!(raw instanceof Map<?, ?> modules) || modules.size() > 2000) return false;
            int matchingModules = 0;
            int matchingExecutions = 0;
            for (var entry : modules.entrySet()) {
                Object name = call(entry.getKey(), "getModuleName");
                if (!Objects.equals(field(name, "artifactId"), expected.module)) continue;
                if (++matchingModules > 1) return false;
                if (!(entry.getValue() instanceof List<?> builds) || builds.size() != 1) return false;
                if (!(builds.get(0) instanceof Run<?, ?> module)
                        || module.isLogUpdated()
                        || !Result.SUCCESS.equals(module.getResult())) return false;
                Object rawMojos = call(module, "getExecutedMojos");
                if (!(rawMojos instanceof List<?> mojos) || mojos.size() > 2000) return false;
                for (Object mojo : mojos) {
                    if (Objects.equals(field(mojo, "groupId"), "org.apache.maven.plugins")
                            && Objects.equals(field(mojo, "artifactId"), "maven-compiler-plugin")
                            && Objects.equals(field(mojo, "version"), expected.version)
                            && Objects.equals(field(mojo, "goal"), expected.goal)
                            && Objects.equals(field(mojo, "executionId"), expected.execution)) matchingExecutions++;
                }
            }
            return matchingModules == 1 && matchingExecutions == 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static Object call(Object value, String method) throws ReflectiveOperationException {
        return value.getClass().getMethod(method).invoke(value);
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        return value.getClass().getField(name).get(value);
    }
}

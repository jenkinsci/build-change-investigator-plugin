package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparableCheckTest {
    private static final String HEADER =
            "[INFO] --- maven-compiler-plugin:3.13.0:compile (default-compile) @ service ---";
    private static final String COMPILED =
            "[INFO] Compiling 4 source files with javac [debug release 17] to target/classes";
    private static final String GOOD = "[INFO] BUILD SUCCESS";
    private static final String ERROR = "[ERROR] src/main/java/Trade.java:[10,2] cannot find symbol";
    private static final FailureSignal SIGNAL = FailureSignal.extract(List.of(HEADER, ERROR));
    private static final ComparableCheck.Check CHECK =
            ComparableCheck.failed(List.of(HEADER, ERROR), SIGNAL, "builder");

    @Test
    void positiveComparableCompileIsVerified() {
        assertNotNull(CHECK);
        assertNotNull(ComparableCheck.passed(List.of(HEADER, COMPILED, GOOD), CHECK, "builder", true));
    }

    @Test
    void successAloneAndNoOpCannotProveRecovery() {
        assertNull(ComparableCheck.passed(List.of(GOOD), CHECK, "builder", true));
        assertNull(ComparableCheck.passed(
                List.of(HEADER, "[INFO] Nothing to compile - all classes are up to date", GOOD),
                CHECK,
                "builder",
                true));
        assertNull(ComparableCheck.passed(
                List.of(HEADER, COMPILED, "[INFO] Skipping compilation", GOOD), CHECK, "builder", true));
    }

    @Test
    void changedModuleGoalOrBuilderCannotProveRecovery() {
        assertNull(ComparableCheck.passed(
                List.of(HEADER.replace("@ service", "@ other"), COMPILED, GOOD), CHECK, "builder", true));
        assertNull(ComparableCheck.passed(
                List.of(HEADER.replace(":compile", ":testCompile"), COMPILED, GOOD), CHECK, "builder", true));
        assertNull(ComparableCheck.passed(List.of(HEADER, COMPILED, GOOD), CHECK, "different-builder", true));
        assertNull(ComparableCheck.passed(
                List.of(HEADER.replace("3.13.0", "3.14.0"), COMPILED, GOOD), CHECK, "builder", true));
    }

    @Test
    void otherModuleCannotSupplyPositiveCompileCount() {
        assertNull(ComparableCheck.passed(
                List.of(
                        HEADER,
                        "[INFO] --- maven-compiler-plugin:3.13.0:compile (default-compile) @ other ---",
                        COMPILED,
                        GOOD),
                CHECK,
                "builder",
                true));
        assertNull(ComparableCheck.passed(List.of(HEADER, "[INFO] NO-SOURCE", COMPILED, GOOD), CHECK, "builder", true));
        assertNull(
                ComparableCheck.passed(List.of(HEADER, "[INFO] UP-TO-DATE", COMPILED, GOOD), CHECK, "builder", true));
    }

    @Test
    void incompleteFailedOrRepeatedExecutionIsUnknown() {
        assertNull(ComparableCheck.passed(List.of(HEADER, COMPILED), CHECK, "builder", true));
        assertNull(ComparableCheck.passed(List.of(HEADER, COMPILED, GOOD), CHECK, "builder", false));
        assertNull(ComparableCheck.passed(List.of(HEADER, COMPILED, HEADER, COMPILED, GOOD), CHECK, "builder", true));
        assertNull(ComparableCheck.passed(
                List.of(HEADER, COMPILED, "[INFO] BUILD FAILURE", GOOD), CHECK, "builder", true));
    }

    @Test
    void diagnosticMustBelongToActiveCompilerGoal() {
        assertNull(ComparableCheck.failed(List.of(ERROR), SIGNAL, "builder"));
        assertNull(ComparableCheck.failed(
                List.of(HEADER, "[INFO] --- surefire:3.0:test (test) @ service ---", ERROR), SIGNAL, "builder"));
        assertNull(ComparableCheck.failed(List.of(HEADER, ERROR), SIGNAL, ""));
    }
}

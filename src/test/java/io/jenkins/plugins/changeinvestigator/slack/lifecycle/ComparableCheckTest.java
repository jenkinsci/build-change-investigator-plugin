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
    void reportedMaven381ExecutionAndNonComparableAlternatives() {
        String header = "[INFO] --- compiler:3.8.1:compile (default-compile) @ collateral-service ---";
        var signal = FailureSignal.extract(List.of(header, ERROR));
        var check = ComparableCheck.failed(List.of(header, ERROR), signal, "maven-project");
        assertNotNull(check);
        var success = List.of(
                header,
                "[INFO] Changes detected - recompiling the module!",
                "[INFO] Compiling 182 source files to /synthetic/target/classes",
                "[INFO] --- resources:3.3.1:testResources (default-testResources) @ collateral-service ---",
                "[INFO] --- compiler:3.8.1:testCompile (default-testCompile) @ collateral-service ---",
                "[INFO] Nothing to compile - all classes are up to date",
                "[INFO] --- surefire:3.2.5:test (default-test) @ collateral-service ---",
                "[INFO] --- jar:3.4.1:jar (default-jar) @ collateral-service ---",
                "[INFO] --- install:3.1.2:install (default-install) @ collateral-service ---",
                GOOD);
        assertNotNull(ComparableCheck.passed(success, check, "maven-project", true));
        for (String different : List.of(
                header.replace("collateral-service", "other-service"),
                header.replace("default-compile", "alternate-compile"),
                header.replace("compiler:", "other-plugin:"),
                header.replace(":compile", ":testCompile"),
                header.replace("3.8.1", "3.13.0"))) {
            assertNull(ComparableCheck.passed(List.of(different, COMPILED, GOOD), check, "maven-project", true));
        }
        assertNull(ComparableCheck.passed(
                List.of(header, "[INFO] Skipping compilation", GOOD), check, "maven-project", true));
        assertNull(ComparableCheck.passed(List.of(header, GOOD), check, "maven-project", true));
        assertNull(ComparableCheck.passed(List.of(GOOD), check, "maven-project", true));
        assertNull(ComparableCheck.passed(success, null, "maven-project", true));
        assertNull(ComparableCheck.passed(success, check, "", true));
    }

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

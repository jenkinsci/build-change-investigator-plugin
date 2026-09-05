package io.jenkins.plugins.changeinvestigator.investigation;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvestigationEngineTest {

    @Test
    void successfulComparisonTargetDoesNotAcquireAFailureSuspect() {
        var evidence = BuildInvestigationEvidence.builder()
                .failedBuildNumber(2)
                .failedBuildResult("SUCCESS")
                .changeEntries(List.of(change("ServiceCheck.java")), true)
                .log(List.of("ServiceCheck.java:1: error: cannot find symbol"), true, false, 1)
                .build();
        var value = new InvestigationCase(evidence, new HistoryEvidence(0, 0, "Explicit comparison"), 1, true, null);
        assertEquals(0, value.getRelevantCount());
        assertTrue(value.getSignalTitle().contains("No failure observed"));
        assertFalse(value.getCopyText().contains("cannot find symbol"));
        assertFalse(value.getCopyText().contains("Most relevant change"));
    }

    @Test
    void duplicateSourceNamesAreNotDirectFileProof() {
        var failure = signal("ServiceCheck.java:1: error: cannot find symbol");
        var ranked = RankedChange.rank(List.of(change("one/ServiceCheck.java", "two/ServiceCheck.java")), failure);
        assertTrue(ranked.stream().allMatch(r -> r.getGroup().equals("Possibly related")));
        assertTrue(ranked.get(0).getLimitation().contains("does not identify"));
    }

    @Test
    void emptyLogHasAnExplicitUnknownSignal() {
        var failure = FailureSignal.extract(List.of());
        assertTrue(failure.getText().contains("No structured failure signal"));
        assertFalse(failure.isSpecific());
    }

    private FailureSignal signal(String... lines) {
        return FailureSignal.extract(List.of(lines));
    }

    private ChangeEntry change(String... paths) {
        return new ChangeEntry("a254c24", "Developer", "Update component", List.of(paths), 0, 2);
    }

    @Test
    void mavenCompile() {
        var s = signal(
                "[ERROR] /src/CollateralTrade.java:[853,24] cannot find symbol",
                "[ERROR] symbol: variable isPortolioIM");
        assertEquals("Compilation error", s.getCategory());
        assertEquals("853", s.getLine());
        assertEquals("24", s.getColumn());
        assertEquals("isPortolioIM", s.getSymbol());
    }

    @Test
    void gradleCompile() {
        var s = signal(
                "> Task :payments:compileJava FAILED",
                "/src/Payment.java:31: error: cannot find symbol",
                "symbol: variable amount");
        assertEquals("Compilation error", s.getCategory());
        assertEquals("payments", s.getModule());
        assertEquals("31", s.getLine());
    }

    @Test
    void javaException() {
        var s = signal(
                "java.lang.IllegalStateException: service handshake refused",
                " at com.acme.ServiceCheck.run(ServiceCheck.java:1)");
        assertEquals("Java exception", s.getCategory());
        assertEquals("ServiceCheck.java", s.getFile());
        assertTrue(s.getException().contains("IllegalStateException"));
    }

    @Test
    void testFailure() {
        var s = signal("PaymentContractTest > shouldCreatePayment() FAILED", "java.lang.AssertionError: expected true");
        assertEquals("Test failure", s.getCategory());
        assertTrue(s.getTest().contains("shouldCreatePayment"));
    }

    @Test
    void apiFailure() {
        var s = signal(
                "> Task :payment-common:payments-integration-test FAILED",
                "java.lang.NoSuchMethodError: 'boolean com.acme.PaymentOptions.isRetryable()'",
                " at com.acme.PaymentClient.run(PaymentClient.java:67)");
        assertEquals("Dependency / API failure", s.getCategory());
        assertTrue(s.getSymbol().contains("isRetryable"));
        assertEquals("payment-common", s.getModule());
    }

    @Test
    void fallbackIsNotSignature() {
        assertFalse(signal("Process exited with status 7").isSpecific());
        assertEquals("", signal().getSignature());
    }

    @Test
    void sourceMatchIsNotChangedLine() {
        var result = RankedChange.rank(
                List.of(change("src/ServiceCheck.java")),
                signal("java.lang.IllegalStateException: fail", " at com.acme.ServiceCheck.run(ServiceCheck.java:1)"));
        assertEquals("Most relevant", result.get(0).getGroup());
        assertTrue(result.get(0).getLimitation().contains("not verified as changed"));
    }

    @Test
    void moduleAndIndirectAndUnrelated() {
        var s = signal("> Task :payment-common:integrationTest FAILED", "java.lang.NoSuchMethodError: options()");
        var result = RankedChange.rank(List.of(change("README.md", "Jenkinsfile", "payment-common/pom.xml")), s);
        assertEquals(
                List.of("Most relevant", "Possibly related", "Other"),
                result.stream().map(RankedChange::getGroup).toList());
        assertTrue(result.stream().allMatch(r -> !r.getReason().isBlank()));
    }

    @Test
    void noStageDoesNotBlameConfig() {
        assertEquals(
                "Other",
                RankedChange.rank(List.of(change("Jenkinsfile")), signal("Build failed"))
                        .get(0)
                        .getGroup());
    }

    @Test
    void manyChangesAreRetained() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 100; i++) paths.add("docs/" + i + ".md");
        assertEquals(
                100,
                RankedChange.rank(List.of(change(paths.toArray(String[]::new))), signal("failed"))
                        .size());
    }

    @Test
    void verifiesContinuousMatchingFailures() {
        var s = signal("java.lang.IllegalStateException: fail");
        var h = HistoryEvidence.analyze(
                List.of(obs(4, "FAILURE", s), obs(3, "FAILURE", s), obs(2, "SUCCESS", s)), 4, s);
        assertEquals(3, h.getFirstBad());
    }

    @Test
    void unstableBreaksProof() {
        assertUnknown("UNSTABLE");
    }

    @Test
    void abortedBreaksProof() {
        assertUnknown("ABORTED");
    }

    @Test
    void notBuiltBreaksProof() {
        assertUnknown("NOT_BUILT");
    }

    private void assertUnknown(String result) {
        var s = signal("java.lang.IllegalStateException: fail");
        assertFalse(
                HistoryEvidence.analyze(List.of(obs(4, "FAILURE", s), obs(3, result, s), obs(2, "SUCCESS", s)), 4, s)
                        .isVerified());
    }

    @Test
    void gapBreaksProof() {
        var s = signal("java.lang.IllegalStateException: fail");
        assertFalse(HistoryEvidence.analyze(List.of(obs(4, "FAILURE", s), obs(2, "SUCCESS", s)), 4, s)
                .isVerified());
    }

    @Test
    void boundsAndMissingHistoryStayUnknown() {
        var s = signal("java.lang.IllegalStateException: fail");
        List<HistoryEvidence.Observation> runs = new ArrayList<>();
        for (int i = 150; i > 49; i--) runs.add(obs(i, "FAILURE", s));
        runs.add(obs(49, "SUCCESS", s));
        assertFalse(HistoryEvidence.analyze(runs, 150, s).isVerified());
        assertFalse(HistoryEvidence.analyze(List.of(), 1, s).isVerified());
    }

    @Test
    void partialSignatureDoesNotMatch() {
        var a = signal("java.lang.NoSuchMethodError: alpha()");
        var b = signal("java.lang.NoSuchMethodError: beta()");
        assertNotEquals(a.getSignature(), b.getSignature());
        var h = HistoryEvidence.analyze(
                List.of(obs(3, "FAILURE", a), obs(2, "FAILURE", b), obs(1, "SUCCESS", b)), 3, a);
        assertFalse(h.isVerified());
        assertEquals(0, h.getSimilarBuild());
    }

    @Test
    void similarIsExactContext() {
        var s = signal("java.lang.NoSuchMethodError: alpha()");
        assertEquals(
                1,
                HistoryEvidence.analyze(List.of(obs(3, "FAILURE", s), obs(2, "SUCCESS", s), obs(1, "FAILURE", s)), 3, s)
                        .getSimilarBuild());
    }

    @Test
    void copyIsStructuredRedactedAndConservative() {
        var e = BuildInvestigationEvidence.builder()
                .failedBuildNumber(2)
                .failedBuildResult("FAILURE")
                .previousSuccessfulBuild(1, "job/demo/1/")
                .changeEntries(List.of(change("README.md", "docs/ownership.md")), true)
                .log(
                        List.of("java.lang.IllegalStateException: service handshake refused password=example-secret"),
                        true,
                        false,
                        1)
                .build();
        var c = new InvestigationCase(e, new HistoryEvidence(0, 0, "Unknown"), 1, false, null);
        assertFalse(c.isStrongRelationship());
        String copy = c.getCopyText();
        assertTrue(copy.contains("No change has a strong direct relationship"));
        assertTrue(copy.contains("Limitation:"));
        assertFalse(copy.contains("example-secret"));
        assertTrue(copy.length() < 6001);
    }

    @Test
    void similarRequiresExactLineAndExceptionAndChoosesMostRecentMatch() {
        var current = signal("java.lang.IllegalStateException: failure", " at com.acme.Service.run(Service.java:12)");
        var changedLine =
                signal("java.lang.IllegalStateException: failure", " at com.acme.Service.run(Service.java:13)");
        var changedType =
                signal("java.lang.IllegalArgumentException: failure", " at com.acme.Service.run(Service.java:12)");
        for (var other : List.of(changedLine, changedType)) {
            assertEquals(
                    0,
                    HistoryEvidence.analyze(
                                    List.of(
                                            obs(3, "FAILURE", current),
                                            obs(2, "SUCCESS", current),
                                            obs(1, "FAILURE", other)),
                                    3,
                                    current)
                            .getSimilarBuild());
        }
        var history = HistoryEvidence.analyze(
                List.of(
                        obs(6, "FAILURE", current),
                        obs(5, "SUCCESS", current),
                        obs(4, "FAILURE", current),
                        obs(3, "SUCCESS", current),
                        obs(2, "FAILURE", current)),
                6,
                current);
        assertEquals(4, history.getSimilarBuild());
        var generic = signal("Build failed");
        assertEquals(
                0,
                HistoryEvidence.analyze(
                                List.of(
                                        obs(3, "FAILURE", generic),
                                        obs(2, "SUCCESS", generic),
                                        obs(1, "FAILURE", generic)),
                                3,
                                generic)
                        .getSimilarBuild());
    }

    @Test
    void copyUsesVerifiedWindowManifestValuesAndSelectedRevision() {
        var evidence = BuildInvestigationEvidence.builder()
                .failedBuildNumber(3)
                .failedBuildResult("FAILURE")
                .previousSuccessfulBuild(1, "job/demo/1/")
                .changeEntries(List.of(change("payment-common/pom.xml")), true)
                .log(
                        List.of(
                                "> Task :payment-common:integrationTest FAILED",
                                "java.lang.NoSuchMethodError: options()"),
                        true,
                        false,
                        2)
                .build();
        var investigation = new InvestigationCase(evidence, new HistoryEvidence(2, 0, "Verified"), 1, false, null);
        investigation.attachManifests(
                new ManifestEvidence(java.util.Map.of("payment-common/pom.xml", "module 2.3.1")),
                new ManifestEvidence(java.util.Map.of("payment-common/pom.xml", "module 2.4.0")));
        String copy = investigation.getCopyText();
        for (String expected : List.of(
                "Build #3",
                "Last good: #1",
                "First bad: #2",
                "#1 → #2",
                "NoSuchMethodError",
                "payment-common/pom.xml",
                "a254c24",
                "Before: module 2.3.1",
                "After: module 2.4.0",
                "Evidence:",
                "Limitation:",
                "Suggested check:")) {
            assertTrue(copy.contains(expected), expected + " missing from " + copy);
        }
        assertTrue(copy.length() <= 6000);
        assertFalse(copy.contains("AI interpretation"));
    }

    @Test
    void historyAcceptsSuccessfulBoundaryAtExactlyOneHundredObservations() {
        var failure = signal("java.lang.IllegalStateException: failure");
        List<HistoryEvidence.Observation> withinBound = new ArrayList<>();
        for (int number = 100; number >= 2; number--) withinBound.add(obs(number, "FAILURE", failure));
        withinBound.add(obs(1, "SUCCESS", failure));
        assertEquals(2, HistoryEvidence.analyze(withinBound, 100, failure).getFirstBad());
        List<HistoryEvidence.Observation> beyondBound = new ArrayList<>();
        for (int number = 101; number >= 2; number--) beyondBound.add(obs(number, "FAILURE", failure));
        beyondBound.add(obs(1, "SUCCESS", failure));
        assertFalse(HistoryEvidence.analyze(beyondBound, 101, failure).isVerified());
    }

    @Test
    void historyRequiresSuccessAndEveryPriorFailureSignature() {
        var failure = signal("java.lang.IllegalStateException: failure");
        assertEquals(
                2,
                HistoryEvidence.analyze(List.of(obs(2, "FAILURE", failure), obs(1, "SUCCESS", failure)), 2, failure)
                        .getFirstBad());
        assertFalse(HistoryEvidence.analyze(List.of(obs(2, "FAILURE", failure), obs(1, "FAILURE", failure)), 2, failure)
                .isVerified());
        assertFalse(HistoryEvidence.analyze(
                        List.of(
                                obs(3, "FAILURE", failure),
                                new HistoryEvidence.Observation(2, "FAILURE", ""),
                                obs(1, "SUCCESS", failure)),
                        3,
                        failure)
                .isVerified());
    }

    private HistoryEvidence.Observation obs(int n, String result, FailureSignal s) {
        return new HistoryEvidence.Observation(n, result, s.getSignature());
    }
}

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

    private HistoryEvidence.Observation obs(int n, String result, FailureSignal s) {
        return new HistoryEvidence.Observation(n, result, s.getSignature());
    }
}

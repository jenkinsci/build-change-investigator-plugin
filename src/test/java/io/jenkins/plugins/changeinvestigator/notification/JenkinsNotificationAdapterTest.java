package io.jenkins.plugins.changeinvestigator.notification;

import static org.junit.jupiter.api.Assertions.*;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.NotificationRunIdentity;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.notification.identity.TrustedIdentityEvidence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class JenkinsNotificationAdapterTest {
    private static String persistIdentity(Run<?, ?> run) throws Exception {
        var identity = new NotificationRunIdentity();
        run.addAction(identity);
        run.save();
        return identity.getId();
    }

    private static FreeStyleBuild fail(JenkinsRule j, FreeStyleProject job) throws Exception {
        var build = job.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);
        assertNotNull(build.getAction(InvestigationAction.class));
        persistIdentity(build);
        return build;
    }

    private static NotificationObservation observe(FreeStyleBuild build) throws Exception {
        return JenkinsNotificationAdapter.observe(
                build,
                StableIdentities.jobId(build.getParent()),
                build.getAction(NotificationRunIdentity.class).getId(),
                true);
    }

    @Test
    void contiguousV1EvidenceProvesBoundaryWithoutUsingLineNumbersAsIdentity(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("adapter-boundary");
        var good = j.buildAndAssertSuccess(job);
        String goodId = persistIdentity(good);
        job.getBuildersList().add(new CompilerFailure());
        var first = fail(j, job);
        var current = fail(j, job);
        var projection = observe(current);
        var boundary = projection.display().path("boundary");
        assertTrue(boundary.path("firstBadVerified").asBoolean());
        assertEquals("VERIFIED", boundary.path("proofStatus").asText());
        assertEquals(goodId, boundary.path("lastKnownGood").path("runId").asText());
        assertEquals(
                first.getAction(NotificationRunIdentity.class).getId(),
                projection.facts().verifiedFirstBadRunId());
        assertEquals(2, boundary.path("firstBad").path("number").asInt());
        assertEquals("SUCCESS", boundary.path("lastKnownGood").path("result").asText());
    }

    @Test
    void missingHistoricalIdentityRemainsUnknownAndIsNeverAllocated(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("adapter-missing");
        var good = j.buildAndAssertSuccess(job);
        job.getBuildersList().add(new CompilerFailure());
        var current = fail(j, job);
        var projection = observe(current);
        assertFalse(
                projection.display().path("boundary").path("firstBadVerified").asBoolean());
        assertTrue(projection.facts().verifiedFirstBadRunId().isEmpty());
        assertNull(good.getAction(NotificationRunIdentity.class));
        persistIdentity(good); // Explicit fixture provenance arrives later; the adapter never allocates it.
        var refined = observe(current);
        assertTrue(refined.display().path("boundary").path("firstBadVerified").asBoolean());
        assertNotEquals(projection.observationId(), refined.observationId());
        assertEquals(101, refined.observationId().length());
        assertTrue(projection.signature().sameIdentity(refined.signature()));
        assertEquals(refined.observationId(), refined.signature().sourceObservationId());
    }

    @Test
    void differentSignatureOrDeletedIntermediateBuildBreaksProof(JenkinsRule j) throws Exception {
        for (String name : List.of("adapter-different", "adapter-gap")) {
            var job = j.createFreeStyleProject(name);
            persistIdentity(j.buildAndAssertSuccess(job));
            job.getBuildersList().add(new CompilerFailure());
            var first = fail(j, job);
            var current = fail(j, job);
            if (name.endsWith("gap")) first.delete();
            assertFalse(observe(current)
                    .display()
                    .path("boundary")
                    .path("firstBadVerified")
                    .asBoolean());
        }
    }

    @Test
    void incompleteInventoryAndAmbiguousProducerNeverProveBoundary(JenkinsRule j) throws Exception {
        for (String name : List.of("adapter-inventory", "adapter-duplicate")) {
            var job = j.createFreeStyleProject(name);
            persistIdentity(j.buildAndAssertSuccess(job));
            job.getBuildersList().add(new CompilerFailure());
            var current = fail(j, job);
            assertFalse(observe(current)
                    .display()
                    .path("boundary")
                    .path("firstBadVerified")
                    .asBoolean());
        }
    }

    @Test
    void unsafeCandidateIdentityIsWithheldInsteadOfHashedAfterClipping(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("adapter-candidate");
        job.getBuildersList().add(new CompilerFailure());
        var build = fail(j, job);
        for (String commit : List.of("x".repeat(120), "x".repeat(129), "password=synthetic-secret")) {
            replaceEvidence(
                    build,
                    List.of(new ChangeEntry(
                            commit,
                            "Demo",
                            "Synthetic change",
                            List.of("src/CollateralTrade.java"),
                            0,
                            build.getNumber())));
            var projection = observe(build);
            assertEquals(0, projection.display().path("topCandidates").size());
            assertTrue(projection.facts().topCandidates().isEmpty());
            assertTrue(projection.facts().relevantCommits().isEmpty());
            assertFalse(projection.display().path("changesComplete").asBoolean());
            assertTrue(projection
                    .display()
                    .path("failureSummary")
                    .path("limitation")
                    .asText()
                    .contains("withheld"));
        }
    }

    @Test
    void relevantCommitsExcludeUnrelatedWeakDocumentation(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("adapter-relevant");
        job.getBuildersList().add(new CompilerFailure());
        var build = fail(j, job);
        replaceEvidence(
                build,
                List.of(
                        new ChangeEntry(
                                "source-commit", "Demo", "Source change", List.of("src/CollateralTrade.java"), 0, 1),
                        new ChangeEntry("docs-commit", "Demo", "Docs change", List.of("docs/readme.md"), 0, 1)));
        var projection = observe(build);
        assertEquals(List.of("source-commit"), projection.facts().relevantCommits());
        assertEquals(1, projection.facts().topCandidates().size());
    }

    private static void replaceEvidence(FreeStyleBuild build, List<ChangeEntry> changes) {
        var previous = build.getAction(InvestigationAction.class);
        build.removeAction(previous);
        var evidence = BuildInvestigationEvidence.builder()
                .jobFullName(build.getParent().getFullName())
                .failedBuildNumber(build.getNumber())
                .failedBuildResult("FAILURE")
                .changeEntries(changes, true)
                .log(
                        List.of(
                                "src/CollateralTrade.java:853:24: error: cannot find symbol",
                                "symbol: variable isPortolioIM"),
                        true,
                        false,
                        2)
                .build();
        build.addAction(new InvestigationAction(build, evidence));
    }

    private static final class CompilerFailure extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger()
                    .println(
                            "src/CollateralTrade.java:" + (850 + build.getNumber()) + ":24: error: cannot find symbol");
            listener.getLogger().println("symbol: variable " + symbol(build));
            return false;
        }
    }

    private static String symbol(Run<?, ?> run) {
        return run.getParent().getName().equals("adapter-different") && run.getNumber() == 2
                ? "otherSymbol"
                : "isPortolioIM";
    }

    @TestExtension
    public static final class TrustedFixture implements TrustedIdentityEvidence {
        @Override
        public Snapshot observe(Run<?, ?> run, String jobId, String observationId) {
            if (!run.getParent().getName().startsWith("adapter-")) return null;
            var context = ExecutionContextV1.create(
                    jobId,
                    List.of(new ExecutionContextV1.ScmSource("source", "demo-repository", "BRANCH", "main", null)),
                    ExecutionContextV1.Origin.NORMAL,
                    true,
                    true);
            return new Snapshot(
                    context,
                    FailureSignatureV1.Category.COMPILER,
                    Map.of(
                            "profile",
                            "COMPILER",
                            "scmSourceId",
                            "source",
                            "repositoryPath",
                            "src/CollateralTrade.java",
                            "diagnosticKind",
                            "cannot.find.symbol",
                            "discriminant",
                            symbol(run)),
                    new FailureSignatureV1.ContextFields(
                            850 + run.getNumber(), 24, "compile", "src/CollateralTrade.java"),
                    "src/CollateralTrade.java",
                    null,
                    List.of("src/CollateralTrade.java"),
                    !run.getParent().getName().equals("adapter-inventory") || run.getNumber() != 1);
        }
    }

    @TestExtension
    public static final class DuplicateFixture implements TrustedIdentityEvidence {
        @Override
        public Snapshot observe(Run<?, ?> run, String jobId, String observationId) {
            return run.getParent().getName().equals("adapter-duplicate")
                    ? new TrustedFixture().observe(run, jobId, observationId)
                    : null;
        }
    }
}

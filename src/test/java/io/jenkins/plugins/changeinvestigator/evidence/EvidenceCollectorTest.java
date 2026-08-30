package io.jenkins.plugins.changeinvestigator.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class EvidenceCollectorTest {

    @Test
    void findsPreviousSuccessfulBuildAndChangesSinceIt(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("with-history");

        project.setScm(FakeChangeLogSCM.none());
        FreeStyleBuild build1 = jenkins.buildAndAssertSuccess(project);

        project.setScm(new FakeChangeLogSCM(List.of(
                new FakeChangeLogSCM.FakeCommit("abc123", "alice", "Bump dependency version",
                        List.of("pom.xml")))));
        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build2 = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build2);

        BuildInvestigationEvidence evidence = new EvidenceCollector(50_000).collect(build2);

        assertTrue(evidence.isPreviousSuccessfulBuildFound());
        assertEquals(build1.getNumber(), evidence.getPreviousSuccessfulBuildNumber());
        assertEquals(build2.getNumber(), evidence.getFailedBuildNumber());
        assertEquals("FAILURE", evidence.getFailedBuildResult());
        assertTrue(evidence.isChangeDataAvailable());
        assertEquals(1, evidence.getChangeEntries().size());
        assertEquals("abc123", evidence.getChangeEntries().get(0).getCommitId());
        assertEquals("Bump dependency version", evidence.getChangeEntries().get(0).getMessage());
        assertTrue(evidence.getWarnings().isEmpty(), evidence.getWarnings().toString());
    }

    @Test
    void accumulatesChangesAcrossMultipleFailedBuildsSinceLastSuccess(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("multi-fail-history");

        project.setScm(FakeChangeLogSCM.none());
        jenkins.buildAndAssertSuccess(project);

        project.setScm(new FakeChangeLogSCM(List.of(
                new FakeChangeLogSCM.FakeCommit("commit-1", "alice", "First bad change", List.of("a.txt")))));
        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build2 = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build2);

        project.setScm(new FakeChangeLogSCM(List.of(
                new FakeChangeLogSCM.FakeCommit("commit-2", "bob", "Second change", List.of("b.txt")))));
        FreeStyleBuild build3 = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build3);

        BuildInvestigationEvidence evidence = new EvidenceCollector(50_000).collect(build3);

        assertEquals(2, evidence.getChangeEntries().size());
        // Oldest-first reading order.
        assertEquals("commit-1", evidence.getChangeEntries().get(0).getCommitId());
        assertEquals("commit-2", evidence.getChangeEntries().get(1).getCommitId());
        assertEquals("commit-2", evidence.getLastKnownRevision());
    }

    @Test
    void reportsNoPreviousSuccessfulBuildOnFirstBuild(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("first-build-fails");
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());

        FreeStyleBuild build1 = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build1);

        BuildInvestigationEvidence evidence = new EvidenceCollector(50_000).collect(build1);

        assertFalse(evidence.isPreviousSuccessfulBuildFound());
        assertTrue(evidence.getWarnings().stream().anyMatch(w -> w.toLowerCase().contains("first build")),
                evidence.getWarnings().toString());
    }

    @Test
    void reportsNoChangesWarningWhenScmReportsNothing(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("no-scm-changes");
        project.setScm(FakeChangeLogSCM.none());
        jenkins.buildAndAssertSuccess(project);

        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build2 = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build2);

        BuildInvestigationEvidence evidence = new EvidenceCollector(50_000).collect(build2);

        assertTrue(evidence.getChangeEntries().isEmpty());
        assertTrue(evidence.getWarnings().stream().anyMatch(w -> w.toLowerCase().contains("no changes were reported")),
                evidence.getWarnings().toString());
    }

    @Test
    void collectsLogExcerptForFailedBuild(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("log-excerpt");
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());

        FreeStyleBuild build1 = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build1);

        BuildInvestigationEvidence evidence = new EvidenceCollector(50_000).collect(build1);

        assertTrue(evidence.isLogAvailable());
        assertFalse(evidence.getLogExcerpt().isEmpty());
    }

    @Test
    void reportsNodeNameForFreestyleBuild(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("node-info");
        project.setScm(FakeChangeLogSCM.none());
        FreeStyleBuild build1 = jenkins.buildAndAssertSuccess(project);

        BuildInvestigationEvidence evidence = new EvidenceCollector(50_000).collect(build1);

        assertTrue(evidence.isNodeInfoAvailable());
    }
}

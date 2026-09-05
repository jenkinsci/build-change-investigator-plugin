package io.jenkins.plugins.changeinvestigator.investigation;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleProject;
import hudson.tasks.Shell;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class InvestigationComparisonTest {
    @Test
    void comparisonReusesCaseAndPreservesNativeWidgets(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("comparison");
        var good = project.scheduleBuild2(0).get();
        project.setScm(new FakeChangeLogSCM(List.of(new FakeChangeLogSCM.FakeCommit(
                "abc123", "Developer", "Change source", List.of("src/ServiceCheck.java")))));
        project.getBuildersList()
                .add(
                        new Shell(
                                "echo 'java.lang.IllegalStateException: service handshake refused'; echo ' at com.acme.ServiceCheck.run(ServiceCheck.java:1)'; exit 1"));
        var bad = project.scheduleBuild2(0).get();
        var current = project.scheduleBuild2(0).get();
        var wc = j.createWebClient();
        HtmlPage page = wc.getPage(current, "change-investigation/");
        String text = page.asNormalizedText();
        assertTrue(
                text.contains("FIRST BAD"),
                () -> current.getAction(InvestigationAction.class)
                                .getView()
                                .getHistory()
                                .getExplanation() + "\nCurrent: "
                        + FailureSignal.extract(current.getAction(InvestigationAction.class)
                                        .getEvidence()
                                        .getLogExcerpt())
                                .getSignature()
                        + "\nPrior: "
                        + FailureSignal.extract(bad.getAction(InvestigationAction.class)
                                        .getEvidence()
                                        .getLogExcerpt())
                                .getSignature());
        assertTrue(text.contains("src/ServiceCheck.java"));
        assertNotNull(page.getElementById("jenkins-build-history"));
        HtmlPage compare = wc.getPage(
                current, "change-investigation/?baseline=" + good.getNumber() + "&target=" + bad.getNumber());
        assertTrue(compare.asNormalizedText().contains("BASELINE"));
        assertTrue(compare.asNormalizedText().contains("TARGET BUILD"));
        assertThrows(
                FailingHttpStatusCodeException.class,
                () -> wc.getPage(current, "change-investigation/?baseline=3&target=1"));
        assertThrows(
                FailingHttpStatusCodeException.class,
                () -> wc.getPage(current, "change-investigation/?baseline=1&target=999"));
        assertThrows(
                FailingHttpStatusCodeException.class,
                () -> wc.getPage(current, "change-investigation/?baseline=x&target=2"));
    }

    @Test
    void noScmOldEvidenceAndEscaping(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("old-evidence");
        var run = project.scheduleBuild2(0).get();
        var evidence = BuildInvestigationEvidence.builder()
                .failedBuildNumber(1)
                .failedBuildResult("FAILURE")
                .log(
                        List.of(
                                "java.lang.IllegalStateException: <script>alert(1)</script> password=private-demo-value"),
                        true,
                        false,
                        1)
                .build();
        var action = new InvestigationAction(run, evidence);
        var structuredCase = InvestigationAction.class.getDeclaredField("investigation");
        structuredCase.setAccessible(true);
        structuredCase.set(action, null);
        run.addAction(action);
        run.save();
        HtmlPage page = j.createWebClient().getPage(run, "change-investigation/");
        assertTrue(page.asNormalizedText().contains("SCM change evidence unavailable"));
        assertFalse(page.getWebResponse().getContentAsString().contains("<script>alert(1)</script>"));
        assertFalse(action.getView().getCopyText().contains("private-demo-value"));
        j.jenkins.reload();
        assertNotNull(j.jenkins
                .getItemByFullName("old-evidence", FreeStyleProject.class)
                .getLastBuild()
                .getAction(InvestigationAction.class)
                .getView());
    }

    @Test
    void comparisonRequiresJobRead(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("private-job");
        var run = project.scheduleBuild2(0).get();
        run.addAction(new InvestigationAction(
                run, BuildInvestigationEvidence.builder().failedBuildNumber(1).build()));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("viewer"));
        var wc = j.createWebClient().login("viewer");
        assertThrows(
                FailingHttpStatusCodeException.class,
                () -> wc.getPage(run, "change-investigation/?baseline=1&target=2"));
        try (var context = hudson.security.ACL.as2(
                hudson.model.User.getById("viewer", true).impersonate2())) {
            assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> run.getAction(InvestigationAction.class).getView());
        }
    }
}

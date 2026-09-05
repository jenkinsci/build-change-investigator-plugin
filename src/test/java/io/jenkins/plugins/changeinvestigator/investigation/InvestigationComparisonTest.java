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
    void comparisonRejectsInvalidBoundsWith400AndRendersSuccessfulTarget(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("comparison-bounds");
        var first = project.scheduleBuild2(0).get();
        var second = project.scheduleBuild2(0).get();
        project.getBuildersList().add(new org.jvnet.hudson.test.FailureBuilder());
        var failed = project.scheduleBuild2(0).get();
        var wc = j.createWebClient();
        for (String query : List.of(
                "baseline=0&target=2",
                "baseline=2&target=2",
                "baseline=2&target=1",
                "baseline=1&target=102",
                "baseline=1&target=999",
                "baseline=x&target=2",
                "baseline=1",
                "target=2",
                "baseline=2147483648&target=2")) {
            var error = assertThrows(
                    FailingHttpStatusCodeException.class, () -> wc.getPage(failed, "change-investigation/?" + query));
            assertEquals(400, error.getStatusCode(), query);
        }
        HtmlPage normal = wc.getPage(failed, "change-investigation/");
        assertEquals(200, normal.getWebResponse().getStatusCode());
        assertFalse(failed.getAction(InvestigationAction.class).hasAiAssessment());
        var rejectedAiGet = assertThrows(
                FailingHttpStatusCodeException.class, () -> wc.getPage(failed, "change-investigation/runAi"));
        assertEquals(404, rejectedAiGet.getStatusCode());
        assertFalse(failed.getAction(InvestigationAction.class).hasAiAssessment());
        HtmlPage success = wc.getPage(
                failed, "change-investigation/?baseline=" + first.getNumber() + "&target=" + second.getNumber());
        assertEquals(200, success.getWebResponse().getStatusCode());
        assertTrue(success.asNormalizedText().contains("No failure observed in target build"));
        assertFalse(success.asNormalizedText().contains("Most relevant change:"));
        assertTrue(success.getForms().stream().noneMatch(f -> "runAi".equals(f.getNameAttribute())));
        assertNotNull(success.getElementById("jenkins-build-history"));
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
        var wc = j.createWebClient();
        HtmlPage page = wc.getPage(run, "change-investigation/");
        assertTrue(page.asNormalizedText().contains("SCM change evidence unavailable"));
        assertFalse(page.getWebResponse().getContentAsString().contains("<script>alert(1)</script>"));
        assertFalse(action.getView().getCopyText().contains("private-demo-value"));
        assertEquals(
                Boolean.TRUE,
                page.executeJavaScript("document.getElementById('bci-bundle').hidden")
                        .getJavaScriptResult());
        page.executeJavaScript("Object.defineProperty(navigator, 'clipboard', {configurable:true, value:{"
                + "writeText:function(value){window.bciCopyAttempt=value;return Promise.reject(new Error('Synthetic clipboard denial'));}"
                + "}});");
        page.getHtmlElementById("bci-copy").click();
        wc.waitForBackgroundJavaScript(1000);
        String bundle = action.getView().getCopyText();
        assertEquals(bundle, page.executeJavaScript("window.bciCopyAttempt").getJavaScriptResult());
        assertTrue(bundle.length() <= 6000);
        assertFalse(bundle.contains("private-demo-value"));
        assertEquals(
                Boolean.TRUE,
                page.executeJavaScript("(function(){var b=document.getElementById('bci-bundle');"
                                + "return !b.hidden && b.readOnly && document.activeElement===b"
                                + " && b.selectionStart===0 && b.selectionEnd===b.value.length;})()")
                        .getJavaScriptResult());
        assertEquals(
                "Select and copy the investigation below.",
                page.getElementById("bci-copy-status").getTextContent());
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
            assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> run.getAction(InvestigationAction.class).getTarget());
        }
    }
}

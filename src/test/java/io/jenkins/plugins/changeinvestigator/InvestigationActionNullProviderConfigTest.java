package io.jenkins.plugins.changeinvestigator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Covers the one real-analysis-path gap left by {@code InvestigationActionTest}: AI analysis is
 * enabled globally, but no provider was ever selected/configured (an administrator ticked
 * "Enable AI analysis" and never picked a provider from the dropdown, or a provider that was
 * configured got removed). {@link InvestigationAction#doRunAi} must record a clear, safe
 * {@code failed} assessment - the observed evidence must still render - never throw.
 */
@WithJenkins
class InvestigationActionNullProviderConfigTest {

    @Test
    void runningAiWhileEnabledWithNoProviderConfiguredRecordsAFailedAssessmentNotACrash(JenkinsRule jenkins)
            throws Exception {
        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        config.setProviderConfig(null);

        FreeStyleProject project = jenkins.createFreeStyleProject("ai-enabled-no-provider");
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);

        InvestigationAction action = build.getAction(InvestigationAction.class);
        assertNotNull(action);
        assertFalse(action.hasAiAssessment());

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        HtmlPage page = wc.getPage(build, "change-investigation/");
        HtmlForm form = page.getFormByName("runAi");
        // Must not throw (no IllegalStateException, no NPE reaching Jenkins as an "Oops" page) -
        // the point of this test is that submitting simply succeeds and redirects back.
        jenkins.submit(form);

        InvestigationAction reloaded = build.getAction(InvestigationAction.class);
        assertTrue(reloaded.hasAiAssessment());
        assertTrue(reloaded.getAiAssessment().isFailed(), "no provider configured must record a *failed* assessment");
        assertTrue(
                reloaded.getAiAssessment()
                        .getErrorMessage()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("provider"),
                "the error message should point the administrator at configuring a provider");

        // The build page itself (observed evidence) must still render fine despite the AI failure.
        HtmlPage reloadedPage = wc.getPage(build, "change-investigation/");
        assertTrue(reloadedPage.asNormalizedText().contains("Build Change Investigation"));
    }
}

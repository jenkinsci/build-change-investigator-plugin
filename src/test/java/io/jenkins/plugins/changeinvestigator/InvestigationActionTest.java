package io.jenkins.plugins.changeinvestigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class InvestigationActionTest {

    private FreeStyleBuild createFailedBuild(JenkinsRule jenkins, String name) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(name);
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        return build;
    }

    @Test
    void runningAiWhileDisabledRecordsADisabledAssessment(JenkinsRule jenkins) throws Exception {
        ChangeInvestigatorGlobalConfiguration.get().setAiEnabled(false);
        FreeStyleBuild build = createFailedBuild(jenkins, "disabled-ai");
        InvestigationAction action = build.getAction(InvestigationAction.class);
        assertNotNull(action);
        assertFalse(action.hasAiAssessment());

        // With AI disabled globally, the "Run AI Analysis" button is correctly not rendered at
        // all (see index.jelly) - so this posts directly to the endpoint (as doRunAi's own
        // defense-in-depth disabled check, not the UI, is what's under test here) rather than
        // looking up a form that should not exist.
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        org.htmlunit.WebRequest request = new org.htmlunit.WebRequest(
                new java.net.URL(jenkins.getURL(), build.getUrl() + "change-investigation/runAi"),
                org.htmlunit.HttpMethod.POST);
        wc.addCrumb(request);
        wc.getPage(request);

        InvestigationAction reloaded = build.getAction(InvestigationAction.class);
        assertTrue(reloaded.hasAiAssessment());
        assertTrue(reloaded.getAiAssessment().isDisabled());
    }

    @Test
    void runningAiWhileEnabledCallsConfiguredEndpointAndCachesResult(JenkinsRule jenkins) throws Exception {
        try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"{"
                + "\\\"mostLikelyCause\\\":\\\"dependency bump\\\","
                + "\\\"confidence\\\":\\\"HIGH\\\","
                + "\\\"reasoning\\\":\\\"only change present\\\","
                + "\\\"supportingEvidence\\\":[],\\\"recommendedChecks\\\":[],"
                + "\\\"insufficientEvidence\\\":false}\"}}]}")) {

            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL, "test-cred", "desc", hudson.util.Secret.fromString("test-token")));

            ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
            config.setAiEnabled(true);
            config.setBaseUrl(mock.baseUrl());
            config.setModel("test-model");
            config.setCredentialsId("test-cred");

            FreeStyleBuild build = createFailedBuild(jenkins, "enabled-ai");

            JenkinsRule.WebClient wc = jenkins.createWebClient();
            HtmlPage page = wc.getPage(build, "change-investigation/");
            HtmlForm form = page.getFormByName("runAi");
            jenkins.submit(form);

            InvestigationAction reloaded = build.getAction(InvestigationAction.class);
            assertTrue(reloaded.getAiAssessment().isCompleted());
            assertEquals("dependency bump", reloaded.getAiAssessment().getMostLikelyCause());
            assertEquals("Bearer test-token", mock.lastAuthorizationHeader);
        }
    }

    @Test
    void usersWithoutPermissionCannotTriggerAiAnalysis(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("viewer"));
        ChangeInvestigatorGlobalConfiguration.get().setAiEnabled(true);

        FreeStyleBuild build = createFailedBuild(jenkins, "no-permission");

        JenkinsRule.WebClient wc = jenkins.createWebClient().login("viewer");
        HtmlPage page = wc.getPage(build, "change-investigation/");
        // Without RUN_AI_ANALYSIS (and it is not implied by READ alone), the button must not
        // even be rendered - but assert the server-side check too, directly.
        assertTrue(
                page.getForms().stream().noneMatch(f -> "runAi".equals(f.getNameAttribute())),
                "the Run AI Analysis form must not be rendered for a user without permission");

        InvestigationAction action = build.getAction(InvestigationAction.class);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
            try (hudson.security.ACLContext ctx = hudson.security.ACL.as2(
                    hudson.model.User.getById("viewer", true).impersonate2())) {
                action.doRunAi(null);
            }
        });
    }
}

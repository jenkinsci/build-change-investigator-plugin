package io.jenkins.plugins.changeinvestigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that {@link InvestigationAction} is attached exactly where it should be: on
 * builds that finished worse than SUCCESS, and nowhere else.
 */
@WithJenkins
class InvestigationRunListenerTest {

    @Test
    void actionIsAttachedToFailedBuilds(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("fails");
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);

        assertNotNull(
                build.getAction(InvestigationAction.class), "InvestigationAction should be attached to a failed build");
    }

    @Test
    void actionIsNotAttachedToSuccessfulBuilds(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("succeeds");
        project.setScm(FakeChangeLogSCM.none());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        assertNull(
                build.getAction(InvestigationAction.class),
                "InvestigationAction should not be attached to a successful build");
    }

    @Test
    void actionIsAttachedExactlyOnceEvenIfListenerRanTwice(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("idempotent");
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        assertEquals(1, build.getActions(InvestigationAction.class).size());

        // The listener already ran once as part of the real build lifecycle above; invoking it
        // again directly (simulating an unexpected duplicate invocation) must not add a second action.
        new InvestigationRunListener().onCompleted(build, TaskListener.NULL);

        assertEquals(1, build.getActions(InvestigationAction.class).size());
    }
}

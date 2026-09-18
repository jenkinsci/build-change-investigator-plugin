package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import hudson.maven.MavenModuleSet;
import hudson.model.TaskListener;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class MavenRecoveryTest {
    @Test
    void mavenProjectFreezesComparableIdentityBeforeExecution(JenkinsRule jenkins) throws Exception {
        var project = jenkins.createProject(MavenModuleSet.class, "synthetic-maven");
        project.setGoals("clean install");
        project.setAggregatorStyleBuild(true);
        var config = io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration.get();
        config.setCredentialId("synthetic");
        config.setDefaultChannel("C12345678");
        config.setEnabled(true);
        project.addProperty(new io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty(true, false, ""));
        var run = new hudson.maven.MavenModuleSetBuild(project);
        new SlackRuntime.Completed().onStarted(run, TaskListener.NULL);
        String original = SlackRuntime.executionContext(run);
        assertFalse(original.isEmpty(), "Maven Project needs a persisted comparable execution identity");
        String configured = MavenExecution.configuration(run);
        project.save();
        project.doReload();
        assertEquals(
                configured, MavenExecution.configuration(run), "Reload must not change equivalent empty-list identity");
        project.setGoals("clean test");
        assertEquals(original, SlackRuntime.executionContext(run), "Running build keeps its frozen configuration");
        var next = new hudson.maven.MavenModuleSetBuild(project);
        new SlackRuntime.Completed().onStarted(next, TaskListener.NULL);
        assertNotEquals(original, SlackRuntime.executionContext(next));
        for (String goals : java.util.List.of(
                "clean install -T2",
                "-T2 clean install",
                "clean -T 2 install",
                "--threads=2 clean install",
                "clean --threads 2 install")) {
            project.setGoals(goals);
            var parallel = new hudson.maven.MavenModuleSetBuild(project);
            new SlackRuntime.Completed().onStarted(parallel, TaskListener.NULL);
            assertEquals("", SlackRuntime.executionContext(parallel), "Parallel reactor evidence is ambiguous");
        }
    }
}

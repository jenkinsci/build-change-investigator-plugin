package io.jenkins.plugins.changeinvestigator.investigation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Shell;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.ai.AiAssessment;
import io.jenkins.plugins.changeinvestigator.ai.Confidence;
import io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HistoricalInvestigationTest {
    private static void set(InvestigationAction action, String name, Object value) throws Exception {
        var field = InvestigationAction.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(action, value);
    }

    private static InvestigationAction action(JenkinsRule j, String job, int number) {
        return j.jenkins
                .getItemByFullName(job, FreeStyleProject.class)
                .getBuildByNumber(number)
                .getAction(InvestigationAction.class);
    }

    private static FreeStyleProject sequence(JenkinsRule j, String name) throws Exception {
        var project = j.createFreeStyleProject(name);
        var good = project.scheduleBuild2(0).get();
        good.replaceAction(new ManifestEvidence(Map.of("payment-common/pom.xml", "payment-common 2.3.1")));
        good.save();
        project.setScm(new FakeChangeLogSCM(List.of(new FakeChangeLogSCM.FakeCommit(
                "abc123", "Developer", "Update dependency", List.of("payment-common/pom.xml")))));
        project.getBuildersList()
                .add(new Shell("echo '> Task :payment-common:integrationTest FAILED'; "
                        + "echo 'java.lang.NoSuchMethodError: options()'; exit 1"));
        var first = project.scheduleBuild2(0).get();
        first.replaceAction(new ManifestEvidence(Map.of("payment-common/pom.xml", "payment-common 2.4.0")));
        first.save();
        project.scheduleBuild2(0).get();
        return project;
    }

    @Test
    void historicalCompletedAndFailedAssessmentsRenderWithoutStructuredCaseOrNetwork(JenkinsRule j) throws Exception {
        try (var mock = MockAiServer.start("{}")) {
            var config = ChangeInvestigatorGlobalConfiguration.get();
            config.setAiEnabled(true);
            config.setProviderConfig(new OpenAiCompatibleProviderConfig(mock.baseUrl(), "demo-model", ""));
            config.save();
            var project = sequence(j, "historical");
            set(
                    action(j, "historical", 2),
                    "aiAssessment",
                    AiAssessment.completed(
                            "Historical interpretation",
                            Confidence.LOW,
                            "Stored reasoning",
                            List.of("Stored evidence"),
                            List.of("Inspect the dependency"),
                            false,
                            "Demo provider",
                            "demo-model"));
            set(action(j, "historical", 3), "aiAssessment", AiAssessment.failed("Synthetic unavailable provider"));
            for (int n : List.of(2, 3)) {
                set(action(j, "historical", n), "investigation", null);
                project.getBuildByNumber(n).save();
            }
            j.jenkins.reload();
            for (int n : List.of(2, 3)) {
                var restored = action(j, "historical", n);
                assertFalse(restored.getView().getHistory().isVerified());
                HtmlPage page = j.createWebClient().getPage(restored.getRun(), "change-investigation/");
                assertEquals(200, page.getWebResponse().getStatusCode());
                assertTrue(page.asNormalizedText().contains("NoSuchMethodError"));
                assertTrue(page.asNormalizedText()
                        .contains(n == 2 ? "Historical interpretation" : "Synthetic unavailable provider"));
                assertNotNull(page.getElementById("jenkins-build-history"));
            }
            assertNull(mock.lastRequestBody, "Reload and page view must not call the provider");
            assertTrue(action(j, "historical", 2).getAiReferences().isEmpty());
        }
    }

    @Test
    void persistedV3SurvivesFailureReloadAndRetryWithTransientComparisonCache(JenkinsRule j) throws Exception {
        var project = sequence(j, "persisted");
        var original = action(j, "persisted", 3);
        String copy = original.getView().getCopyText();
        assertEquals(2, original.getView().getHistory().getFirstBad());
        assertTrue(copy.contains("Before: payment-common 2.3.1"));
        assertTrue(copy.contains("After: payment-common 2.4.0"));
        var config = ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        try (var unavailable = MockAiServer.startWithStatus(503, "{}")) {
            config.setProviderConfig(new OpenAiCompatibleProviderConfig(unavailable.baseUrl(), "demo-model", ""));
            config.save();
            HtmlPage page = j.createWebClient().getPage(original.getRun(), "change-investigation/");
            j.submit(page.getFormByName("runAi"));
            assertTrue(original.getAiAssessment().isFailed());
        }
        j.createWebClient().getPage(original.getRun(), "change-investigation/?baseline=1&target=3");
        var cache = InvestigationAction.class.getDeclaredField("comparisons");
        cache.setAccessible(true);
        assertNotNull(cache.get(original));
        original.getRun().save();
        assertFalse(Files.readString(original.getRun().getRootDir().toPath().resolve("build.xml"))
                .contains("<comparisons>"));
        j.jenkins.reload();
        var restored = action(j, "persisted", 3);
        assertNull(cache.get(restored));
        assertEquals(copy, restored.getView().getCopyText());
        assertEquals(2, restored.getView().getHistory().getFirstBad());
        assertTrue(restored.getAiAssessment().isFailed());
        assertEquals(
                "payment-common 2.4.0",
                j.jenkins
                        .getItemByFullName("persisted", FreeStyleProject.class)
                        .getBuildByNumber(2)
                        .getAction(ManifestEvidence.class)
                        .value("payment-common/pom.xml"));
        var mapper = new ObjectMapper();
        String content = mapper.writeValueAsString(Map.of(
                "mostLikelyCause",
                "Synthetic retry explanation",
                "confidence",
                "LOW",
                "reasoning",
                "Stored evidence",
                "supportingEvidence",
                List.of("E1 change"),
                "recommendedChecks",
                List.of("Inspect dependency"),
                "insufficientEvidence",
                false));
        String response =
                mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        try (var mock = MockAiServer.start(response)) {
            config = ChangeInvestigatorGlobalConfiguration.get();
            config.setProviderConfig(new OpenAiCompatibleProviderConfig(mock.baseUrl(), "demo-model", ""));
            HtmlPage page = j.createWebClient().getPage(restored.getRun(), "change-investigation/");
            assertTrue(page.asNormalizedText().contains("AI analysis unavailable"));
            j.submit(page.getFormByName("runAi"));
            assertTrue(restored.getAiAssessment().isCompleted());
            assertNotNull(mock.lastRequestBody);
            assertEquals(Result.FAILURE, restored.getRun().getResult());
            assertEquals(copy, restored.getView().getCopyText());
            j.jenkins.reload();
            assertTrue(action(j, "persisted", 3).getAiAssessment().isCompleted());
            assertEquals(copy, action(j, "persisted", 3).getView().getCopyText());
        }
    }
}

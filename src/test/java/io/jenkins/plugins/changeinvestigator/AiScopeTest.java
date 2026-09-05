package io.jenkins.plugins.changeinvestigator;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.ai.AiAssessment;
import io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry;
import io.jenkins.plugins.changeinvestigator.investigation.HistoryEvidence;
import io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import java.net.URL;
import java.util.List;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AiScopeTest {
    private BuildInvestigationEvidence evidence() {
        return BuildInvestigationEvidence.builder()
                .failedBuildNumber(3)
                .failedBuildResult("FAILURE")
                .previousSuccessfulBuild(1, "job/scope-demo/1/")
                .lastKnownRevision("later-revision")
                .changeEntries(
                        List.of(
                                new ChangeEntry(
                                        "first-revision",
                                        "Developer",
                                        "Change source",
                                        List.of("src/Service.java"),
                                        0,
                                        2),
                                new ChangeEntry(
                                        "later-revision", "Developer", "Update docs", List.of("docs/later.md"), 0, 3)),
                        true)
                .log(
                        List.of("java.lang.IllegalStateException: failed", " at demo.Service.run(Service.java:7)"),
                        true,
                        false,
                        2)
                .build();
    }

    @Test
    void corpusMatchesPageAndSurvivesReload(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("scope-demo");
        project.scheduleBuild2(0).get();
        project.getBuildersList().add(new FailureBuilder());
        project.scheduleBuild2(0).get();
        var run = project.scheduleBuild2(0).get();
        var original = evidence();
        var action = new InvestigationAction(run, original);
        var field = InvestigationAction.class.getDeclaredField("investigation");
        field.setAccessible(true);
        field.set(
                action, new InvestigationCase(original, new HistoryEvidence(2, 0, "Verified fixture"), 1, false, null));
        run.replaceAction(action);
        var mapper = new ObjectMapper();
        var result = mapper.createObjectNode();
        result.put("mostLikelyCause", "Inspect source")
                .put("confidence", "LOW")
                .put("reasoning", "E1 and E2")
                .put("insufficientEvidence", false);
        result.putArray("supportingEvidence").add("E1");
        result.putArray("recommendedChecks").add("Inspect source");
        var response = mapper.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content", result.toString());
        try (var mock = MockAiServer.start(response.toString())) {
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL,
                            "scope-demo-token",
                            "Synthetic test",
                            Secret.fromString("synthetic-token")));
            var config = ChangeInvestigatorGlobalConfiguration.get();
            config.setAiEnabled(true);
            config.setProviderConfig(
                    new OpenAiCompatibleProviderConfig(mock.baseUrl(), "demo-model", "scope-demo-token"));
            var wc = j.createWebClient();
            HtmlPage initial = wc.getPage(run, "change-investigation/");
            assertNull(mock.lastRequestBody);
            assertTrue(initial.asNormalizedText().contains(action.getAiScope()));
            var request = new WebRequest(
                    new URL(j.getURL(), run.getUrl() + "change-investigation/runAi?baseline=1&target=2"),
                    HttpMethod.POST);
            wc.addCrumb(request);
            HtmlPage page = wc.getPage(request);
            assertTrue(action.getAiAssessment().isCompleted());
            var body = mapper.readTree(mock.lastRequestBody);
            var corpus =
                    mapper.readTree(body.path("messages").get(1).path("content").asText());
            assertEquals(action.getAiScope(), corpus.path("evidenceScope").asText());
            assertTrue(page.asNormalizedText().contains(action.getAiScope()));
            assertEquals(2, corpus.path("changes").path("throughBuildNumber").asInt());
            assertEquals(1, corpus.path("changes").path("entries").size());
            assertFalse(corpus.path("changes").toString().contains("docs/later.md"));
            assertEquals(3, corpus.path("failureLogExcerpt").path("buildNumber").asInt());
            assertEquals(3, corpus.path("failedBuild").path("number").asInt());
            assertEquals(3, corpus.path("lastKnownRevisionBuildNumber").asInt());
            assertEquals(2, original.getChangeEntries().size());
            assertEquals(3, original.getChangeWindowEnd());
            assertEquals(Result.FAILURE, run.getResult());
            j.jenkins.reload();
            var reloaded = j.jenkins
                    .getItemByFullName("scope-demo", FreeStyleProject.class)
                    .getLastBuild()
                    .getAction(InvestigationAction.class);
            assertEquals(action.getAiScope(), reloaded.getAiScope());
            assertEquals(2, reloaded.getView().getHistory().getFirstBad());
            assertEquals(2, reloaded.getEvidence().getChangeEntries().size());
            assertTrue(reloaded.getAiAssessment().isCompleted());
        }
    }

    @Test
    void historicalAssessmentKeepsFullWindow(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("historical-scope");
        var run = project.scheduleBuild2(0).get();
        var original = evidence();
        var action = new InvestigationAction(run, original);
        var field = InvestigationAction.class.getDeclaredField("investigation");
        field.setAccessible(true);
        field.set(
                action, new InvestigationCase(original, new HistoryEvidence(2, 0, "Verified fixture"), 1, false, null));
        var assessment = InvestigationAction.class.getDeclaredField("aiAssessment");
        assessment.setAccessible(true);
        assessment.set(action, AiAssessment.failed("Synthetic historical failure"));
        run.replaceAction(action);
        run.save();
        assertEquals(original.getAiScope(), action.getAiScope());
        HtmlPage page = j.createWebClient().getPage(run, "change-investigation/");
        assertTrue(page.asNormalizedText().contains(original.getAiScope()));
        j.jenkins.reload();
        var reloaded = j.jenkins
                .getItemByFullName("historical-scope", FreeStyleProject.class)
                .getLastBuild()
                .getAction(InvestigationAction.class);
        assertEquals(original.getAiScope(), reloaded.getAiScope());
        assertTrue(reloaded.getAiAssessment().isFailed());
    }

    @Test
    void pendingRetryKeepsHistoricalPresentationWithoutBlockingPage(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("pending-scope");
        project.getBuildersList().add(new FailureBuilder());
        var run = project.scheduleBuild2(0).get();
        var original = evidence();
        var action = new InvestigationAction(run, original);
        var field = InvestigationAction.class.getDeclaredField("investigation");
        field.setAccessible(true);
        field.set(
                action, new InvestigationCase(original, new HistoryEvidence(2, 0, "Verified fixture"), 1, false, null));
        var stored = InvestigationAction.class.getDeclaredField("aiAssessment");
        stored.setAccessible(true);
        stored.set(
                action,
                AiAssessment.completed(
                        "Historical full-window interpretation",
                        io.jenkins.plugins.changeinvestigator.ai.Confidence.LOW,
                        "Stored reasoning",
                        List.of("E3"),
                        List.of("Inspect history"),
                        false,
                        "Demo provider",
                        "demo-model"));
        run.replaceAction(action);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var mapper = new ObjectMapper();
        var result = mapper.createObjectNode();
        result.put("mostLikelyCause", "New narrowed interpretation")
                .put("confidence", "LOW")
                .put("reasoning", "E1")
                .put("insufficientEvidence", false);
        result.putArray("supportingEvidence").add("E1");
        result.putArray("recommendedChecks").add("Inspect source");
        var response = mapper.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content", result.toString());
        try (var mock = MockAiServer.start(exchange -> {
            entered.countDown();
            try {
                if (!release.await(60, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Test release timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test interrupted", e);
            }
            return response.toString();
        })) {
            var config = ChangeInvestigatorGlobalConfiguration.get();
            config.setAiEnabled(true);
            config.setTimeoutSeconds(60);
            config.setProviderConfig(new OpenAiCompatibleProviderConfig(mock.baseUrl(), "demo-model", ""));
            var redirect = new java.util.concurrent.atomic.AtomicReference<String>();
            var pending = new java.util.concurrent.FutureTask<Void>(() -> {
                try (var context = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                    action.doRunAi(response(redirect));
                }
                return null;
            });
            var wc = j.createWebClient();
            HtmlPage initial = wc.getPage(run, "change-investigation/");
            assertTrue(initial.asNormalizedText().contains("Historical full-window interpretation"));
            assertTrue(initial.asNormalizedText().contains(original.getAiScope()));
            assertNull(mock.lastRequestBody);
            wc.getOptions().setTimeout(15000);
            Thread worker = new Thread(pending, "scope-retry-test");
            worker.start();
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var before = action.getAiPresentation();
                assertEquals(original.getAiScope(), before.getScope());
                assertEquals(List.of("E3"), before.getReferences());
                HtmlPage page = wc.getPage(run, "change-investigation/");
                assertEquals(200, page.getWebResponse().getStatusCode());
                assertTrue(page.asNormalizedText().contains("Historical full-window interpretation"));
                assertTrue(page.asNormalizedText().contains(original.getAiScope()));
                assertFalse(page.asNormalizedText().contains("New narrowed interpretation"));
                assertFalse(pending.isDone(), "Page rendered while provider remained blocked");
                release.countDown();
                pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
                var after = action.getAiPresentation();
                assertTrue(after.getAssessment().isCompleted());
                assertEquals(
                        "New narrowed interpretation", after.getAssessment().getMostLikelyCause());
                assertEquals(List.of("E1"), after.getReferences());
                assertNotEquals(before.getScope(), after.getScope());
                assertEquals(original.getAiScope(), before.getScope());
                assertEquals(
                        "Historical full-window interpretation",
                        before.getAssessment().getMostLikelyCause());
                assertEquals(".", redirect.get());
                HtmlPage completed = wc.getPage(run, "change-investigation/");
                assertTrue(completed.asNormalizedText().contains(after.getScope()));
                assertTrue(completed.asNormalizedText().contains("New narrowed interpretation"));
            } finally {
                release.countDown();
                worker.join(10000);
            }
        }
    }

    private static org.kohsuke.stapler.StaplerResponse2 response(
            java.util.concurrent.atomic.AtomicReference<String> redirect) {
        return (org.kohsuke.stapler.StaplerResponse2) java.lang.reflect.Proxy.newProxyInstance(
                org.kohsuke.stapler.StaplerResponse2.class.getClassLoader(),
                new Class<?>[] {org.kohsuke.stapler.StaplerResponse2.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sendRedirect2")) {
                        redirect.set((String) arguments[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class FailingSaveBuild extends hudson.model.FreeStyleBuild {
        private final boolean runtime;
        private int attempts;

        FailingSaveBuild(hudson.model.FreeStyleBuild original, boolean runtime) throws java.io.IOException {
            super(original.getParent());
            this.runtime = runtime;
            this.number = original.getNumber();
            this.result = original.getResult();
        }

        @Override
        public synchronized void save() throws java.io.IOException {
            attempts++;
            if (runtime) throw new IllegalStateException("Synthetic serialization failure");
            throw new java.io.IOException("Synthetic persistence failure");
        }
    }

    @Test
    void saveFailuresLeaveAssessmentAndInvestigationAvailable(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("save-failure");
        project.getBuildersList().add(new FailureBuilder());
        var run = project.scheduleBuild2(0).get();
        var action = new InvestigationAction(run, evidence());
        run.replaceAction(action);
        var originalView = action.getView();
        String copy = originalView.getCopyText();
        var config = ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        config.setProviderConfig(null);
        for (boolean runtime : List.of(false, true)) {
            var failing = new FailingSaveBuild(run, runtime);
            var redirect = new java.util.concurrent.atomic.AtomicReference<String>();
            action.onAttached(failing);
            try {
                assertDoesNotThrow(() -> action.doRunAi(response(redirect)));
                assertEquals(1, failing.attempts);
                assertEquals(".", redirect.get());
                assertTrue(action.getAiAssessment().isFailed());
                assertSame(originalView, action.getView());
                assertEquals(copy, action.getView().getCopyText());
                assertEquals(Result.FAILURE, run.getResult());
            } finally {
                action.onAttached(run);
            }
            HtmlPage page = j.createWebClient().getPage(run, "change-investigation/");
            assertEquals(200, page.getWebResponse().getStatusCode());
            assertTrue(page.asNormalizedText().contains("IllegalStateException"));
            assertTrue(page.asNormalizedText().contains("AI analysis unavailable"));
            assertNotNull(page.getElementById("jenkins-build-history"));
        }
    }
}

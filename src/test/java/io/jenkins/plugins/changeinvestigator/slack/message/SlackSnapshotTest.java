package io.jenkins.plugins.changeinvestigator.slack.message;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.FreeStyleBuild;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackSnapshotTest {
    private FreeStyleBuild failure(JenkinsRule j) throws Exception {
        var project = j.createFreeStyleProject("snapshot-demo");
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());
        return project.scheduleBuild2(0).get();
    }

    @Test
    void captureDoesNotStartAiOrWaitForAnUnrequestedAnalysis(JenkinsRule j) throws Exception {
        try (MockAiServer server = MockAiServer.start("{}")) {
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL,
                            "snapshot-provider",
                            "Synthetic test",
                            Secret.fromString("synthetic-provider-token")));
            var config = ChangeInvestigatorGlobalConfiguration.get();
            config.setAiEnabled(true);
            config.setProviderConfig(
                    new OpenAiCompatibleProviderConfig(server.baseUrl(), "test-model", "snapshot-provider"));
            FreeStyleBuild build = failure(j);
            var action = build.getAction(InvestigationAction.class);
            assertNotNull(action);
            assertFalse(action.isAiAnalysisRunning());
            SlackSnapshot snapshot = SlackSnapshot.capture(build, 0);
            assertNotNull(snapshot);
            assertFalse(snapshot.aiPending);
            assertEquals("", snapshot.ai);
            assertNull(server.lastAuthorizationHeader);
            assertFalse(action.hasAiAssessment());
            assertFalse(action.isAiAnalysisRunning());
        }
    }

    @Test
    void linksUseOnlyConfiguredJenkinsRootAndNativeBuildPath(JenkinsRule j) throws Exception {
        FreeStyleBuild build = failure(j);
        JenkinsLocationConfiguration location = JenkinsLocationConfiguration.get();
        location.setUrl("https://jenkins.example.invalid/context/");
        SlackSnapshot snapshot = SlackSnapshot.capture(build, 0);
        assertEquals("https://jenkins.example.invalid/context/" + build.getUrl(), snapshot.buildUrl);
        assertEquals(snapshot.buildUrl + "change-investigation/", snapshot.investigationUrl);
        location.setUrl("https://user:password@jenkins.example.invalid/");
        assertEquals("", SlackSnapshot.trustedBuildUrl(build));
        location.setUrl("https://jenkins.example.invalid/?redirect=evil");
        assertEquals("", SlackSnapshot.trustedBuildUrl(build));
    }

    @Test
    void pendingAccessorStaysTrueUntilRequestedAssessmentIsPublished(JenkinsRule j) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ObjectMapper mapper = new ObjectMapper();
        String assessment = mapper.writeValueAsString(Map.of(
                "mostLikelyCause",
                "Inspect the synthetic dependency change",
                "confidence",
                "LOW",
                "reasoning",
                "Evidence is limited",
                "supportingEvidence",
                List.of(),
                "recommendedChecks",
                List.of("Inspect the diff"),
                "insufficientEvidence",
                true));
        String response =
                mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", assessment)))));
        var executor = Executors.newSingleThreadExecutor();
        try (MockAiServer server = MockAiServer.start(exchange -> {
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) return "{}";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{}";
            }
            return response;
        })) {
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL,
                            "pending-provider",
                            "Synthetic test",
                            Secret.fromString("synthetic-provider-token")));
            var config = ChangeInvestigatorGlobalConfiguration.get();
            config.setAiEnabled(true);
            config.setProviderConfig(
                    new OpenAiCompatibleProviderConfig(server.baseUrl(), "test-model", "pending-provider"));
            FreeStyleBuild build = failure(j);
            var action = build.getAction(InvestigationAction.class);
            var client = j.createWebClient();
            org.htmlunit.html.HtmlPage page = client.getPage(build, "change-investigation/");
            assertNotNull(page.getFormByName("runAi"));
            assertNull(server.lastAuthorizationHeader);
            assertEquals(1L, entered.getCount());
            assertFalse(action.isAiAnalysisRunning());
            var request = new org.htmlunit.WebRequest(
                    new java.net.URL(j.getURL(), build.getUrl() + "change-investigation/runAi"),
                    org.htmlunit.HttpMethod.POST);
            client.addCrumb(request);
            var completed = executor.submit(() -> client.getPage(request));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertTrue(action.isAiAnalysisRunning());
            assertFalse(action.hasAiAssessment());
            SlackSnapshot pending = SlackSnapshot.capture(build, 0);
            assertTrue(pending.aiPending);
            assertTrue(pending.ai.isEmpty());
            release.countDown();
            completed.get(15, TimeUnit.SECONDS);
            assertFalse(action.isAiAnalysisRunning());
            assertTrue(action.getAiAssessment().isCompleted());
            SlackSnapshot published = SlackSnapshot.capture(build, 0);
            assertFalse(published.aiPending);
            assertTrue(published.ai.contains("Inspect the synthetic dependency change"));
            reloadBuild(build);
            assertFalse(build.getAction(InvestigationAction.class).isAiAnalysisRunning());
            assertTrue(
                    build.getAction(InvestigationAction.class).getAiAssessment().isCompleted());
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private FreeStyleBuild optedInFailure(JenkinsRule j) throws Exception {
        var slack = SlackConfiguration.get();
        slack.setCredentialId("unavailable-transport-test-credential");
        slack.setDefaultChannel("C12345678");
        slack.setEnabled(true);
        var project = j.createFreeStyleProject("automatic-snapshot-demo");
        project.setScm(FakeChangeLogSCM.none());
        project.getBuildersList().add(new FailureBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        return project.scheduleBuild2(0).get();
    }

    private void reloadBuild(FreeStyleBuild build) throws java.io.IOException {
        build.reload();
        // RunMap loading invokes RunAction2.onLoad after deserializing build actions.
        for (var action : build.getActions(jenkins.model.RunAction2.class)) action.onLoad(build);
        assertSame(build, build.getAction(InvestigationAction.class).getRun());
    }

    private void configureProvider(MockAiServer server) {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "automatic-provider",
                        "Synthetic test",
                        Secret.fromString("synthetic-provider-token")));
        var config = ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        config.setProviderConfig(
                new OpenAiCompatibleProviderConfig(server.baseUrl(), "test-model", "automatic-provider"));
    }

    @Test
    void automaticAndManualRequestsJoinAndCompletedAttemptSurvivesReload(JenkinsRule j) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        String assessment = mapper.writeValueAsString(Map.of(
                "mostLikelyCause",
                "Synthetic explanation",
                "confidence",
                "LOW",
                "reasoning",
                "Evidence is limited",
                "supportingEvidence",
                List.of(),
                "recommendedChecks",
                List.of("Inspect the diff"),
                "insufficientEvidence",
                true));
        String response =
                mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", assessment)))));
        var executor = Executors.newSingleThreadExecutor();
        try (MockAiServer server = MockAiServer.start(exchange -> {
            requests.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) return "{}";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{}";
            }
            return response;
        })) {
            configureProvider(server);
            FreeStyleBuild build = optedInFailure(j);
            var action = build.getAction(InvestigationAction.class);
            var client = j.createWebClient();
            org.htmlunit.html.HtmlPage page = client.getPage(build, "change-investigation/");
            assertNotNull(page.getFormByName("runAi"));
            assertEquals(0, requests.get());
            assertEquals(1L, entered.getCount());
            assertFalse(action.isAiAnalysisRunning());
            assertTrue(action.startSlackAiAnalysis());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertTrue(action.startSlackAiAnalysis());
            assertTrue(action.isAiAnalysisRunning());
            var request = new org.htmlunit.WebRequest(
                    new java.net.URL(j.getURL(), build.getUrl() + "change-investigation/runAi"),
                    org.htmlunit.HttpMethod.POST);
            client.addCrumb(request);
            var manual = executor.submit(() -> client.getPage(request));
            long joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean joined = false;
            while (!joined && System.nanoTime() < joinDeadline) {
                joined = Thread.getAllStackTraces().values().stream()
                        .anyMatch(stack -> java.util.Arrays.stream(stack)
                                        .anyMatch(frame ->
                                                frame.getClassName().equals(InvestigationAction.class.getName())
                                                        && frame.getMethodName().equals("doRunAi"))
                                && java.util.Arrays.stream(stack)
                                        .anyMatch(frame ->
                                                frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                                                        && frame.getMethodName().equals("timedGet")));
                if (!joined) Thread.sleep(20);
            }
            assertTrue(joined, "The explicit request must join the in-flight analysis before the provider is released");
            assertEquals(1, requests.get());
            release.countDown();
            manual.get(15, TimeUnit.SECONDS);
            assertFalse(action.isAiAnalysisRunning());
            assertTrue(action.getAiAssessment().isCompleted());
            assertFalse(action.startSlackAiAnalysis());
            reloadBuild(build);
            assertFalse(build.getAction(InvestigationAction.class).startSlackAiAnalysis());
            assertEquals(1, requests.get());
            assertEquals(hudson.model.Result.FAILURE, build.getResult());
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void manualRequestJoinsAutomaticAnalysisAfterActiveBuildReload(JenkinsRule j) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        String assessment = mapper.writeValueAsString(Map.of(
                "mostLikelyCause",
                "Synthetic explanation",
                "confidence",
                "LOW",
                "reasoning",
                "Evidence is limited",
                "supportingEvidence",
                List.of(),
                "recommendedChecks",
                List.of("Inspect the diff"),
                "insufficientEvidence",
                true));
        String response =
                mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", assessment)))));
        var executor = Executors.newSingleThreadExecutor();
        try (MockAiServer server = MockAiServer.start(exchange -> {
            requests.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) return "{}";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{}";
            }
            return response;
        })) {
            configureProvider(server);
            FreeStyleBuild build = optedInFailure(j);
            var action = build.getAction(InvestigationAction.class);
            var client = j.createWebClient();
            client.goTo(build.getUrl() + "change-investigation/");
            assertEquals(0, requests.get());
            assertFalse(action.isAiAnalysisRunning());
            assertEquals(1, entered.getCount());
            var request = new org.htmlunit.WebRequest(
                    new java.net.URL(j.getURL(), build.getUrl() + "change-investigation/runAi"),
                    org.htmlunit.HttpMethod.POST);
            client.addCrumb(request);
            assertTrue(action.startSlackAiAnalysis());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            reloadBuild(build);
            action = build.getAction(InvestigationAction.class);
            action.startSlackAiAnalysis();

            var manual = executor.submit(() -> client.getPage(request));
            long joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean joined = false;
            while (!joined && System.nanoTime() < joinDeadline) {
                joined = Thread.getAllStackTraces().values().stream()
                        .anyMatch(stack -> java.util.Arrays.stream(stack)
                                        .anyMatch(frame ->
                                                frame.getClassName().equals(InvestigationAction.class.getName())
                                                        && frame.getMethodName().equals("doRunAi"))
                                && java.util.Arrays.stream(stack)
                                        .anyMatch(frame ->
                                                frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                                                        && frame.getMethodName().equals("timedGet")));
                if (!joined) Thread.sleep(20);
            }
            assertTrue(joined, "The explicit request must join the in-flight analysis before the provider is released");
            assertEquals(1, requests.get());
            release.countDown();
            manual.get(15, TimeUnit.SECONDS);
            assertFalse(action.isAiAnalysisRunning());
            assertTrue(action.getAiAssessment().isCompleted());
            assertFalse(action.startSlackAiAnalysis());
            reloadBuild(build);
            assertFalse(build.getAction(InvestigationAction.class).startSlackAiAnalysis());
            assertEquals(1, requests.get());
            assertEquals(hudson.model.Result.FAILURE, build.getResult());
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedAutomaticAttemptDoesNotRepeatAfterReloadOrChangeBuildResult(JenkinsRule j) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (MockAiServer server = MockAiServer.start(
                exchange -> {
                    requests.incrementAndGet();
                    return "{}";
                },
                403)) {
            configureProvider(server);
            FreeStyleBuild build = optedInFailure(j);
            var action = build.getAction(InvestigationAction.class);
            action.startSlackAiAnalysis();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (action.isAiAnalysisRunning() && System.nanoTime() < deadline) Thread.sleep(50);
            assertFalse(action.isAiAnalysisRunning());
            assertNotNull(action.getAiAssessment());
            assertTrue(action.getAiAssessment().isFailed());
            assertFalse(action.startSlackAiAnalysis());
            reloadBuild(build);
            assertFalse(build.getAction(InvestigationAction.class).startSlackAiAnalysis());
            assertEquals(1, requests.get());
            assertEquals(hudson.model.Result.FAILURE, build.getResult());
        }
    }

    @Test
    void unoptedAndAiDisabledBuildsCannotStartAutomaticAnalysis(JenkinsRule j) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (MockAiServer server = MockAiServer.start(exchange -> {
            requests.incrementAndGet();
            return "{}";
        })) {
            configureProvider(server);
            FreeStyleBuild unopted = failure(j);
            assertFalse(unopted.getAction(InvestigationAction.class).startSlackAiAnalysis());
            ChangeInvestigatorGlobalConfiguration.get().setAiEnabled(false);
            FreeStyleBuild disabled = optedInFailure(j);
            assertFalse(disabled.getAction(InvestigationAction.class).startSlackAiAnalysis());
            assertEquals(0, requests.get());
            assertFalse(disabled.getAction(InvestigationAction.class).hasAiAssessment());
        }
    }
}

package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty;
import io.jenkins.plugins.changeinvestigator.slack.transport.ConnectionResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.DeliveryResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackRoute;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackRuntimeTest {
    private static volatile String mode;
    private static final List<String> threads = new CopyOnWriteArrayList<>();
    private static volatile DeliveryResult.Outcome outcome = DeliveryResult.Outcome.ACCEPTED;
    private static volatile long retryAfterSeconds = 60;
    private static final List<String> channels = new CopyOnWriteArrayList<>();
    private static final List<String> payloads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        mode = "success";
        threads.clear();
        channels.clear();
        payloads.clear();
        outcome = DeliveryResult.Outcome.ACCEPTED;
        retryAfterSeconds = 60;
    }

    @Test
    void nativeCompletionSuppressesRepeatsVerifiesRecoveryAndRecurs(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new CompileBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        jenkins.buildAndAssertSuccess(project);
        mode = "failure";
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 2, 1);
        String firstId = new EpisodeStore(project).read().active.id;
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 3, 1);
        mode = "unverified";
        jenkins.buildAndAssertSuccess(project);
        await(project, 4, 1);
        assertFalse(new EpisodeStore(project).read().active.closed);
        mode = "success";
        jenkins.buildAndAssertSuccess(project);
        await(project, 5, 2);
        assertTrue(new EpisodeStore(project).read().active.closed);
        assertEquals("1800000000.000001", threads.get(1));
        jenkins.buildAndAssertSuccess(project);
        await(project, 6, 2);
        mode = "failure";
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 7, 3);
        assertNotEquals(firstId, new EpisodeStore(project).read().active.id);
        assertEquals("root", threads.get(2));
    }

    @Test
    void globalConfigurationDoesNotOptInOrBackfillJobs(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new CompileBuilder());
        mode = "failure";
        var historical = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, historical);
        assertTrue(threads.isEmpty());
        assertEquals("", SlackRuntime.executionContext(historical));
        project.addProperty(new SlackJobProperty(true, false, ""));
        SlackRuntime.get().schedule(project, 0);
        Thread.sleep(250);
        assertTrue(threads.isEmpty());
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 2, 1);
    }

    @Test
    void uncertainAcceptanceIsNotResentAfterJobReload(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new ReloadableCompileBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        mode = "failure";
        outcome = DeliveryResult.Outcome.UNKNOWN;
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 1, 1);
        assertEquals(
                "UNKNOWN_OUTCOME",
                new EpisodeStore(project).read().active.deliveries.get(0).status);
        String episodeId = new EpisodeStore(project).read().active.id;
        project.doReload();
        assertInstanceOf(
                ReloadableCompileBuilder.class, project.getBuildersList().get(0));
        assertTrue(project.getProperty(SlackJobProperty.class).isEnabled());
        assertEquals(episodeId, new EpisodeStore(project).read().active.id);
        outcome = DeliveryResult.Outcome.ACCEPTED;
        SlackRuntime.get().schedule(project, 0);
        Thread.sleep(500);
        assertEquals(1, threads.size());
        assertEquals(
                "UNKNOWN_OUTCOME",
                new EpisodeStore(project).read().active.deliveries.get(0).status);
    }

    @Test
    void quickGlobalToggleAndChannelChangeDoNotReroutePendingBuild(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new CompileBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        mode = "failure";
        outcome = DeliveryResult.Outcome.RETRY;
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 1, 0);
        var config = SlackConfiguration.get();
        config.setEnabled(false);
        config.setEnabled(true);
        config.setDefaultChannel("C87654321");
        outcome = DeliveryResult.Outcome.ACCEPTED;
        SlackRuntime.get().schedule(project, 0);
        Thread.sleep(500);
        assertTrue(threads.isEmpty());
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 2, 1);
        assertEquals(List.of("C87654321"), channels);
    }

    @Test
    void publishedJUnitFailureRequiresActualPassingCaseNotSkippedReport(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new TestReportBuilder());
        project.getPublishersList().add(new hudson.tasks.junit.JUnitResultArchiver("reports/*.xml"));
        project.addProperty(new SlackJobProperty(true, false, ""));
        mode = "failure";
        jenkins.assertBuildStatus(Result.UNSTABLE, project.scheduleBuild2(0));
        await(project, 1, 1);
        assertEquals("junit-test", new EpisodeStore(project).read().active.check.kind);
        mode = "skipped";
        jenkins.buildAndAssertSuccess(project);
        await(project, 2, 1);
        assertFalse(new EpisodeStore(project).read().active.closed);
        mode = "success";
        jenkins.buildAndAssertSuccess(project);
        await(project, 3, 2);
        assertTrue(new EpisodeStore(project).read().active.closed);
    }

    @Test
    void automaticAiRunsOnlyForOptedInInitialAndIsIncludedOnce(JenkinsRule jenkins) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        String response = aiResponse();
        try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(exchange -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return response;
        })) {
            configure();
            configureAi(server.baseUrl());
            FreeStyleProject project = jenkins.createFreeStyleProject();
            project.getBuildersList().add(new CompileBuilder());
            mode = "failure";
            jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
            assertEquals(0, calls.get());
            project.addProperty(new SlackJobProperty(true, false, ""));
            var build = project.scheduleBuild2(0).get();
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(threads.isEmpty());
            release.countDown();
            await(project, 2, 1);
            assertTrue(payloads.get(0).contains("Inspect the synthetic dependency"));

            assertTrue(build.getAction(io.jenkins.plugins.changeinvestigator.InvestigationAction.class)
                    .hasAiAssessment());
            jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
            await(project, 3, 1);
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void automaticAiFailureFallsBackWithoutAnotherMessage(JenkinsRule jenkins) throws Exception {
        try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.startWithStatus(500, "{}")) {
            configure();
            configureAi(server.baseUrl());
            FreeStyleProject project = jenkins.createFreeStyleProject();
            project.getBuildersList().add(new CompileBuilder());
            project.addProperty(new SlackJobProperty(true, false, ""));
            mode = "failure";
            var build = project.scheduleBuild2(0).get();
            await(project, 1, 1);
            assertFalse(payloads.get(0).contains("AI Analysis"));
            assertTrue(build.getAction(io.jenkins.plugins.changeinvestigator.InvestigationAction.class)
                    .hasAiAssessment());
            SlackRuntime.get().schedule(project, 0);
            Thread.sleep(300);
            assertEquals(1, threads.size());
        }
    }

    @Test
    void automaticAiDeadlineSendsDeterministicAndLateCompletionIsSilent(JenkinsRule jenkins) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        String response = aiResponse();
        try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(exchange -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(45, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return response;
        })) {
            configure();
            configureAi(server.baseUrl());
            FreeStyleProject project = jenkins.createFreeStyleProject();
            project.getBuildersList().add(new CompileBuilder());
            project.addProperty(new SlackJobProperty(true, false, ""));
            mode = "failure";
            project.scheduleBuild2(0).get();
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(40).toNanos();
            while (threads.isEmpty() && System.nanoTime() < deadline) {
                SlackRuntime.get().schedule(project, 0);
                Thread.sleep(100);
            }
            assertEquals(1, threads.size());
            assertFalse(payloads.get(0).contains("Inspect the synthetic dependency"));
            release.countDown();
            Thread.sleep(1000);
            SlackRuntime.get().schedule(project, 0);
            Thread.sleep(500);
            assertEquals(1, threads.size());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aDifferentFailureAfterUnverifiedGreenUsesTheNewBaseline(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new CompileBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        jenkins.buildAndAssertSuccess(project);
        mode = "failure";
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 2, 1);
        mode = "unverified";
        jenkins.buildAndAssertSuccess(project);
        await(project, 3, 1);
        assertFalse(new EpisodeStore(project).read().active.closed);
        mode = "failure-new";
        var next = project.scheduleBuild2(0).get();
        await(project, 4, 2);
        var state = new EpisodeStore(project).read();
        var frozen = io.jenkins.plugins.changeinvestigator.slack.message.SlackSnapshot.fromJson(state.active.context);
        var actual = io.jenkins.plugins.changeinvestigator.slack.message.SlackSnapshot.capture(next, 0);
        assertEquals(3, frozen.getLastGood());
        assertTrue(SlackRuntime.matchingAiScope(frozen, actual));
        var previous = io.jenkins.plugins.changeinvestigator.slack.message.SlackSnapshot.fromJson(
                state.history.get(0).context);
        assertFalse(SlackRuntime.matchingAiScope(previous, actual));
    }

    @Test
    void executionIdentityIsFrozenBeforeLaterJobConfigurationChanges(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new CompileBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        mode = "failure";
        var failed = project.scheduleBuild2(0).get();
        await(project, 1, 1);
        String original = SlackRuntime.executionContext(failed);
        assertFalse(original.isBlank());
        project.getBuildersList().add(new ContextBuilder("different-build-configuration"));
        assertEquals(original, SlackRuntime.executionContext(failed));
        failed.reload();
        assertEquals(original, SlackRuntime.executionContext(failed));
        mode = "success";
        var successful = jenkins.buildAndAssertSuccess(project);
        await(project, 2, 1);
        assertNotEquals(original, SlackRuntime.executionContext(successful));
        assertFalse(new EpisodeStore(project).read().active.closed);
    }

    @Test
    void periodicScanWaitsForEvidenceCollectionBeforeAdvancingWatermark(JenkinsRule jenkins) throws Exception {
        configure();
        var listeners = hudson.model.listeners.RunListener.all();
        List.copyOf(listeners).stream()
                .filter(listener -> listener instanceof io.jenkins.plugins.changeinvestigator.InvestigationRunListener)
                .forEach(listeners::remove);
        completionEntered = new java.util.concurrent.CountDownLatch(1);
        completionRelease = new java.util.concurrent.CountDownLatch(1);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new CompileBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        mode = "failure";
        var future = project.scheduleBuild2(0);
        try {
            assertTrue(completionEntered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var run = project.getLastBuild();
            assertFalse(run.isBuilding());
            assertTrue(run.isLogUpdated());
            assertNull(run.getAction(io.jenkins.plugins.changeinvestigator.InvestigationAction.class));
            SlackRuntime.get().schedule(project, 0);
            Thread.sleep(500);
            assertEquals(0, new EpisodeStore(project).read().lastBuild);
            assertTrue(threads.isEmpty());
        } finally {
            completionRelease.countDown();
        }
        future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        await(project, 1, 1);
    }

    private static volatile java.util.concurrent.CountDownLatch completionEntered;
    private static volatile java.util.concurrent.CountDownLatch completionRelease;

    @TestExtension("periodicScanWaitsForEvidenceCollectionBeforeAdvancingWatermark")
    public static final class CompletionGate extends hudson.model.listeners.RunListener<hudson.model.Run<?, ?>> {
        @Override
        public void onCompleted(hudson.model.Run<?, ?> run, hudson.model.TaskListener listener) {
            completionEntered.countDown();
            try {
                if (!completionRelease.await(15, java.util.concurrent.TimeUnit.SECONDS)) return;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return;
            }
            new io.jenkins.plugins.changeinvestigator.InvestigationRunListener().onCompleted(run, listener);
        }
    }

    @Test
    void slackRetryReusesCompletedAiAcrossJobReload(JenkinsRule jenkins) throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        String response = aiResponse();
        try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(exchange -> {
            requests.incrementAndGet();
            return response;
        })) {
            configure();
            configureAi(server.baseUrl());
            FreeStyleProject project = jenkins.createFreeStyleProject();
            project.getBuildersList().add(new ReloadableCompileBuilder());
            project.addProperty(new SlackJobProperty(true, false, ""));
            mode = "failure";
            outcome = DeliveryResult.Outcome.RETRY;
            retryAfterSeconds = 5;
            project.scheduleBuild2(0).get();

            EpisodeEngine.State persisted = null;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                SlackRuntime.get().schedule(project, 0);
                persisted = new EpisodeStore(project).read();
                if (persisted.active != null
                        && persisted.active.deliveries.get(0).attempts == 1
                        && persisted.active.deliveries.get(0).status.equals("QUEUED")) break;
                Thread.sleep(50);
            }
            assertNotNull(persisted);
            assertNotNull(persisted.active);
            assertEquals(1, persisted.active.deliveries.get(0).attempts);
            assertEquals("QUEUED", persisted.active.deliveries.get(0).status);
            assertTrue(persisted.active.deliveries.get(0).aiRequested);
            assertTrue(persisted.active.deliveries.get(0).payload.contains("Inspect the synthetic dependency"));
            assertEquals(1, requests.get());
            assertTrue(threads.isEmpty());
            String episode = persisted.active.id;
            var config = SlackConfiguration.get();
            config.addMapping("demo-author", "U12345678");
            config.setTagResponders(true);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var context = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(persisted.active.context);
            context.put("author", "demo-author");
            persisted.active.context = mapper.writeValueAsString(context);
            var frozen = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(persisted.active.deliveries.get(0).payload);
            var blocks = (com.fasterxml.jackson.databind.node.ArrayNode) frozen.get("blocks");
            blocks.addObject()
                    .put("type", "section")
                    .putObject("text")
                    .put("type", "mrkdwn")
                    .put("verbatim", true)
                    .put("text", "Suggested responder: <@U12345678>");
            blocks.addObject()
                    .put("type", "context")
                    .putArray("elements")
                    .addObject()
                    .put("type", "plain_text")
                    .put("text", "Mapped from demo-author. A suggestion, not an assignment.");
            persisted.active.deliveries.get(0).payload = mapper.writeValueAsString(frozen);
            persisted.active.deliveries.get(0).mappingRevision = config.getMappingRevision();
            new EpisodeStore(project).save(persisted);
            String expectedPayload =
                    io.jenkins.plugins.changeinvestigator.slack.message.SlackMessageRenderer.withoutResponderMention(
                            persisted.active.deliveries.get(0).payload, "demo-author");
            config.removeMapping(config.getResponderMappings().get(0).getId());

            project.doReload();
            outcome = DeliveryResult.Outcome.ACCEPTED;
            await(project, 1, 1);
            var sent = new EpisodeStore(project).read();
            assertEquals(episode, sent.active.id);
            assertEquals(2, sent.active.deliveries.get(0).attempts);
            assertEquals("SENT", sent.active.deliveries.get(0).status);
            assertEquals(1, requests.get());
            assertTrue(payloads.get(0).contains("Inspect the synthetic dependency"));
            assertEquals(expectedPayload, payloads.get(0));
            assertFalse(payloads.get(0).contains("<@"));

            project.doReload();
            SlackRuntime.get().schedule(project, 0);
            Thread.sleep(300);
            assertEquals(1, requests.get());
            assertEquals(1, threads.size());
        }
    }

    @Test
    void mappingPolicyChangesPreserveSuppressionAndRecoveryThread(JenkinsRule jenkins) throws Exception {
        configure();
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new CompileBuilder());
        project.addProperty(new SlackJobProperty(true, false, ""));
        mode = "failure";
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 1, 1);
        var before = new EpisodeStore(project).read().active;
        var config = SlackConfiguration.get();
        long routeRevision = config.getRevision();
        long modifiedAt = config.getModifiedAtMillis();
        long policyRevision = config.getMappingRevision();
        config.addMapping("demo-author", "U12345678");
        config.setTagResponders(true);
        assertEquals(routeRevision, config.getRevision());
        assertEquals(modifiedAt, config.getModifiedAtMillis());
        assertTrue(config.getMappingRevision() > policyRevision);
        jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        await(project, 2, 1);
        assertEquals(before.id, new EpisodeStore(project).read().active.id);
        config.removeMapping(config.getResponderMappings().get(0).getId());
        config.setTagResponders(false);
        mode = "success";
        jenkins.buildAndAssertSuccess(project);
        await(project, 3, 2);
        var after = new EpisodeStore(project).read().active;
        assertEquals(before.id, after.id);
        assertTrue(after.closed);
        assertEquals(before.rootTs, threads.get(1));
        assertFalse(payloads.get(1).contains("<@"));
    }

    private static void configureAi(String url) {
        com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(
                        com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL,
                        "runtime-provider",
                        "Synthetic test",
                        hudson.util.Secret.fromString("synthetic-provider-token")));
        var config = io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        config.setProviderConfig(new io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig(
                url, "test-model", "runtime-provider"));
    }

    private static String aiResponse() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String assessment = mapper.writeValueAsString(java.util.Map.of(
                "mostLikelyCause",
                "Inspect the synthetic dependency",
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
        return mapper.writeValueAsString(java.util.Map.of(
                "choices", List.of(java.util.Map.of("message", java.util.Map.of("content", assessment)))));
    }

    private static void configure() {
        var transports = hudson.ExtensionList.lookup(SlackTransport.class);
        List.copyOf(transports).stream()
                .filter(value -> value.getClass() == SlackTransport.class)
                .forEach(transports::remove);
        var config = SlackConfiguration.get();
        config.setCredentialId("synthetic");
        config.setDefaultChannel("C12345678");
        config.setEnabled(true);
    }

    private static void await(FreeStyleProject project, int build, int sent) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            SlackRuntime.get().schedule(project, build);
            var state = new EpisodeStore(project).read();
            if (state.lastBuild >= build
                    && threads.size() == sent
                    && (state.active == null
                            || state.active.deliveries.stream().noneMatch(d -> d.status.equals("LEASED")))) return;
            Thread.sleep(100);
        }
        fail("Notification lifecycle did not converge");
    }

    public static final class CompileBuilder extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger()
                    .println("[INFO] --- maven-compiler-plugin:3.13.0:compile (default-compile) @ service ---");
            if (mode.startsWith("failure")) {
                listener.getLogger().println("[ERROR] src/main/java/Trade.java:[853,24] cannot find symbol");
                listener.getLogger()
                        .println("symbol: variable " + (mode.equals("failure-new") ? "otherMissing" : "missing"));
                return false;
            }
            if (mode.equals("success")) listener.getLogger().println("[INFO] Compiling 2 source files with javac");
            listener.getLogger().println("[INFO] BUILD SUCCESS");
            return true;
        }
    }

    /** A real persistable build step for the job-reload regression; TestBuilder is intentionally transient. */
    public static final class ReloadableCompileBuilder extends hudson.tasks.Builder {
        @org.kohsuke.stapler.DataBoundConstructor
        public ReloadableCompileBuilder() {}

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            return new CompileBuilder().perform(build, launcher, listener);
        }

        @TestExtension({"uncertainAcceptanceIsNotResentAfterJobReload", "slackRetryReusesCompletedAiAcrossJobReload"})
        public static final class DescriptorImpl extends hudson.tasks.BuildStepDescriptor<hudson.tasks.Builder> {
            public DescriptorImpl() {
                super(ReloadableCompileBuilder.class);
            }

            @Override
            public String getDisplayName() {
                return "Synthetic compilation check";
            }

            @Override
            public boolean isApplicable(Class<? extends hudson.model.AbstractProject> type) {
                return true;
            }
        }
    }

    public static final class ContextBuilder extends TestBuilder {
        private final String configuration;

        ContextBuilder(String configuration) {
            this.configuration = configuration;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger().println(configuration);
            return true;
        }
    }

    public static final class TestReportBuilder extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws java.io.IOException, InterruptedException {
            String detail = mode.equals("failure")
                    ? "<failure message='mismatch'>expected pass</failure>"
                    : mode.equals("skipped") ? "<skipped/>" : "";
            String xml = "<testsuite name='demo.TradeTest' tests='1' failures='" + (mode.equals("failure") ? 1 : 0)
                    + "' skipped='" + (mode.equals("skipped") ? 1 : 0)
                    + "'><testcase classname='demo.TradeTest' name='shouldCompile' time='0.1'>"
                    + detail + "</testcase></testsuite>";
            build.getWorkspace().child("reports").mkdirs();
            build.getWorkspace().child("reports/test.xml").write(xml, "UTF-8");
            if (mode.equals("failure")) listener.getLogger().println("demo.TradeTest.shouldCompile FAILED");
            return true;
        }
    }

    @TestExtension
    public static final class RecordingTransport extends SlackTransport {
        @Override
        public boolean isSafeToStore(String credential, String payload) {
            return true;
        }

        @Override
        public ConnectionResult checkConnection(String credential, String channel) {
            return new ConnectionResult(true, "Ready", "Demo", "#alerts", new SlackRoute("T12345678", channel));
        }

        @Override
        public DeliveryResult post(String credential, SlackRoute route, String payload, String thread, String id) {
            if (outcome == DeliveryResult.Outcome.ACCEPTED || outcome == DeliveryResult.Outcome.UNKNOWN) {
                threads.add(thread == null ? "root" : thread);
                channels.add(route.channelId());
                payloads.add(payload);
            }
            return new DeliveryResult(
                    outcome, "1800000000." + String.format("%06d", threads.size()), retryAfterSeconds, "Sent");
        }
    }
}

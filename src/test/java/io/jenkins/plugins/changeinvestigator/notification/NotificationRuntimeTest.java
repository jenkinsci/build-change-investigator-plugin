package io.jenkins.plugins.changeinvestigator.notification;

import static org.junit.jupiter.api.Assertions.*;

import hudson.ExtensionList;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.NotificationRunIdentity;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.notification.identity.TrustedIdentityEvidence;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NotificationRuntimeTest {
    @Test
    void orderingHoldUsesDurableCompletionClockWithoutReset() {
        assertTrue(NotificationRuntime.holdForEarlierRun(1_000, 500, 121_499));
        assertFalse(NotificationRuntime.holdForEarlierRun(1_000, 500, 121_500));
        assertFalse(NotificationRuntime.holdForEarlierRun(1_000, 500, 500_000));
        assertTrue(NotificationRuntime.holdForEarlierRun(1_000, 500, 1_400));
        assertTrue(NotificationRuntime.holdForEarlierRun(Long.MAX_VALUE, 1, Long.MAX_VALUE));
    }

    private static NotificationRuntime runtime() {
        return ExtensionList.lookupSingleton(NotificationRuntime.class);
    }

    private static NotificationEngine engine(FreeStyleProject job) throws Exception {
        return new NotificationEngine(job.getRootDir().toPath(), UUID.fromString(StableIdentities.jobId(job)));
    }

    private static FreeStyleBuild fail(JenkinsRule j, FreeStyleProject job) throws Exception {
        var build = job.scheduleBuild2(0).get(90, TimeUnit.SECONDS);
        j.assertBuildStatus(Result.FAILURE, build);
        assertNotNull(build.getAction(InvestigationAction.class));
        return build;
    }

    private static List<NotificationInvestigationRecord> awaitRecords(FreeStyleProject job, long occurrences)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            var records = engine(job).records();
            if (!records.isEmpty()
                    && records.stream()
                                    .mapToLong(r -> r.lifecycle().occurrenceCount())
                                    .sum()
                            >= occurrences) return records;
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail(
                "Notification records did not reach expected occurrence count for " + job.getName());
        return List.of();
    }

    @Test
    void defaultOffAndExplicitActivationDoNotBackfill(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("trusted-activation");
        job.getBuildersList().add(new CompilerFailure());
        var historical = fail(j, job);
        assertFalse(Files.exists(job.getRootDir().toPath().resolve("bci-notifications")));
        assertNull(historical.getAction(NotificationRunIdentity.class));
        runtime().arm(job);
        assertTrue(engine(job).records().isEmpty());
        runtime().schedule(job.getFullName());
        var current = fail(j, job);
        var records = awaitRecords(job, 1);
        assertEquals(1, records.size());
        assertEquals(1, records.get(0).events().size());
        assertEquals(current.getNumber(), records.get(0).lifecycle().currentOrder());
        assertNull(historical.getAction(NotificationRunIdentity.class));
        assertEquals(StableIdentities.runId(current), records.get(0).key().episodeAnchorRunId());
        assertTrue(records.get(0).destinations().isEmpty());
    }

    @Test
    void repeatedFailuresReloadAndHistoricalPageRemainIndependent(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("trusted-repeat");
        var good = j.buildAndAssertSuccess(job);
        runtime().arm(job);
        job.getBuildersList().add(new CompilerFailure());
        var first = fail(j, job);
        awaitRecords(job, 1);
        for (int i = 0; i < 9; i++) fail(j, job);
        var records = awaitRecords(job, 10);
        assertEquals(1, records.size());
        var record = records.get(0);
        assertEquals(10, record.lifecycle().occurrenceCount());
        assertEquals(1, record.semanticSequence());
        assertEquals(1, record.events().size());
        assertEquals(StableIdentities.runId(first), record.key().episodeAnchorRunId());
        String firstRun = StableIdentities.runId(first);
        // TestBuilder is intentionally transient in the harness; retain only production job configuration for reload.
        job.getBuildersList().clear();
        job.save();
        int firstNumber = first.getNumber();
        j.jenkins.reload();
        job = j.jenkins.getItemByFullName("trusted-repeat", FreeStyleProject.class);
        first = job.getBuildByNumber(firstNumber);
        assertEquals(firstRun, StableIdentities.runId(first));
        runtime().schedule(job.getFullName());
        assertEquals(record, engine(job).records().get(0));
        var client = j.createWebClient();
        client.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = client.getPage(first, "change-investigation/");
        assertNotNull(page.getElementById("jenkins-build-history"));
        assertNotNull(page.getElementById("bci-copy"));
        HtmlPage comparison = client.getPage(
                first, "change-investigation/?baseline=" + good.getNumber() + "&target=" + first.getNumber());
        assertEquals(200, comparison.getWebResponse().getStatusCode());
        assertTrue(first.getAction(InvestigationAction.class)
                .getView()
                .getCopyText()
                .contains("CollateralTrade.java"));
        assertEquals(1, engine(job).records().get(0).events().size());
    }

    @Test
    void hundredJobsKeepCallbackBoundedAndProduceOneCaseEach(JenkinsRule j) throws Exception {
        j.jenkins.setNumExecutors(12);
        List<FreeStyleProject> jobs = new ArrayList<>();
        List<hudson.model.queue.QueueTaskFuture<FreeStyleBuild>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var job = j.createFreeStyleProject("trusted-burst-" + i);
            job.getBuildersList().add(new CompilerFailure());
            runtime().arm(job);
            jobs.add(job);
        }
        for (var job : jobs) futures.add(job.scheduleBuild2(0));
        List<Long> callbackMicros = new ArrayList<>();
        for (var future : futures) {
            var build = future.get(120, TimeUnit.SECONDS);
            j.assertBuildStatus(Result.FAILURE, build);
            long start = System.nanoTime();
            runtime().onFinalized(build);
            callbackMicros.add((System.nanoTime() - start) / 1000);
        }
        callbackMicros.sort(Long::compareTo);
        long p95 = callbackMicros.get(94);
        System.out.println("Notification finalized callback p95 microseconds: " + p95);
        assertTrue(p95 < 250_000, "Callback enqueue p95 must remain below 250 ms");
        assertTrue(runtime().queuedWork() <= 250);
        for (var job : jobs) {
            var records = awaitRecords(job, 1);
            assertEquals(1, records.size());
            assertEquals(1, records.get(0).events().size());
            assertTrue(records.get(0).destinations().isEmpty());
        }
    }

    @Test
    void reconciliationRecoversUnqueuedFinalizedBuildWithoutChangingResult(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("trusted-reconcile");
        job.getBuildersList().add(new CompilerFailure());
        var build = fail(j, job);
        // Explicit activation normally excludes history; here the fixture establishes a missed live handoff boundary.
        String id = StableIdentities.jobId(job);
        new NotificationEngine(job.getRootDir().toPath(), UUID.fromString(id)).arm(0);
        Files.writeString(job.getRootDir().toPath().resolve("bci-notifications/core-armed"), "0");
        assertNull(build.getAction(NotificationRunIdentity.class));
        runtime().schedule(job.getFullName());
        assertEquals(1, awaitRecords(job, 1).size());
        assertEquals(Result.FAILURE, build.getResult());
        assertNotNull(build.getAction(NotificationRunIdentity.class));
    }

    @Test
    void workerLinkageFailureLeavesBuildAndPageUsableThenReconcilesOnce(JenkinsRule j) throws Exception {
        var producer = ExtensionList.lookup(TrustedIdentityEvidence.class).get(TrustedFixture.class);
        assertNotNull(producer);
        var job = j.createFreeStyleProject("trusted-fault");
        job.getBuildersList().add(new CompilerFailure());
        runtime().arm(job);
        producer.throwLinkage = true;
        try {
            var build = fail(j, job);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (producer.faults.get() == 0 && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(producer.faults.get() > 0, "Fault must execute inside the notification worker");
            assertEquals(Result.FAILURE, build.getResult());
            assertTrue(engine(job).records().isEmpty());
            var client = j.createWebClient();
            client.getOptions().setJavaScriptEnabled(false);
            HtmlPage page = client.getPage(build, "change-investigation/");
            assertEquals(200, page.getWebResponse().getStatusCode());
            assertNotNull(page.getElementById("bci-copy"));
            producer.throwLinkage = false;
            assertTrue(runtime().schedule(job.getFullName()));
            var records = awaitRecords(job, 1);
            assertEquals(1, records.size());
            assertEquals(1, records.get(0).events().size());
            assertTrue(runtime().schedule(job.getFullName()));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!runtime().idle() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(runtime().idle());
            assertEquals(1, engine(job).records().size());
            assertEquals(1, engine(job).records().get(0).events().size());
            assertEquals(Result.FAILURE, build.getResult());
        } finally {
            producer.throwLinkage = false;
        }
    }

    private static final class CompilerFailure extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            listener.getLogger().println("src/CollateralTrade.java:853:24: error: cannot find symbol");
            listener.getLogger().println("symbol: variable isPortolioIM");
            return false;
        }
    }

    @TestExtension
    public static final class TrustedFixture implements TrustedIdentityEvidence {
        volatile boolean throwLinkage;
        final java.util.concurrent.atomic.AtomicInteger faults = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Snapshot observe(Run<?, ?> run, String jobId, String observationId) {
            if (!run.getParent().getName().startsWith("trusted-")) return null;
            if (throwLinkage && run.getParent().getName().equals("trusted-fault")) {
                faults.incrementAndGet();
                throw new LinkageError("Synthetic notification incompatibility");
            }
            var context = ExecutionContextV1.create(
                    jobId,
                    List.of(new ExecutionContextV1.ScmSource("source", "synthetic-repository", "BRANCH", "main", null)),
                    ExecutionContextV1.Origin.NORMAL,
                    true,
                    true);
            return new Snapshot(
                    context,
                    FailureSignatureV1.Category.COMPILER,
                    Map.of(
                            "profile",
                            "COMPILER",
                            "scmSourceId",
                            "source",
                            "repositoryPath",
                            "src/CollateralTrade.java",
                            "diagnosticKind",
                            "cannot.find.symbol",
                            "discriminant",
                            "isPortolioIM"),
                    new FailureSignatureV1.ContextFields(853, 24, "compile", "src/CollateralTrade.java:853:24"),
                    "src/CollateralTrade.java",
                    null,
                    List.of("src/CollateralTrade.java"),
                    true);
        }
    }
}

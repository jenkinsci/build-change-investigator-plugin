package io.jenkins.plugins.changeinvestigator.notification.persistence;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleProject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NotificationPersistenceJenkinsTest {
    @Test
    void jobReloadPreservesIndependentDataWithoutReplayingBuilds(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("durable");
        var build = j.buildAndAssertSuccess(job);
        UUID jobId = UUID.randomUUID(), caseId = UUID.randomUUID();
        var store = new NotificationStore(job.getRootDir().toPath(), jobId);
        store.update(
                caseId,
                0,
                value -> value.put("status", "ACTIVE").put("events", 1).put("intents", 1));
        var ledger = new IngestionLedger(job.getRootDir().toPath(), jobId);
        ledger.arm(build.getNumber());
        ledger.register("future:2:v1", 2);
        job.doReload();
        var loaded = new NotificationStore(job.getRootDir().toPath(), jobId)
                .load(caseId)
                .orElseThrow();
        assertEquals(1, loaded.aggregate().path("events").asInt());
        assertEquals(1, job.getBuilds().size());
        assertEquals(hudson.model.Result.SUCCESS, job.getLastBuild().getResult());
        assertEquals(
                1,
                new IngestionLedger(job.getRootDir().toPath(), jobId).pending().size());
        assertNull(job.getLastBuild().getAction(io.jenkins.plugins.changeinvestigator.InvestigationAction.class));
    }
}

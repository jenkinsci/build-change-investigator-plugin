package io.jenkins.plugins.changeinvestigator.notification.feedback;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.security.ChangeInvestigatorPermissions;
import io.jenkins.plugins.changeinvestigator.security.NotificationPermissions;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FeedbackActionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Fixture(
            FreeStyleProject job, NotificationEngine engine, UUID caseId, long revision, long evidenceRevision) {}

    private Fixture fixture(JenkinsRule j, String name) throws Exception {
        var job = j.createFreeStyleProject(name);
        UUID jobId = UUID.fromString(StableIdentities.jobId(job));
        var engine = new NotificationEngine(
                job.getRootDir().toPath(),
                jobId,
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
        engine.arm(0);
        var record = engine.ingest(
                FeedbackHttpFixtures.input(jobId, 1, "commit-a", false), List.of(), System.currentTimeMillis());
        return new Fixture(job, engine, record.investigationId(), record.caseRevision(), record.evidenceRevision());
    }

    private void security(JenkinsRule j) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader", "builder", "configurer", "triage", "confirm", "mute", "analyst", "reviewer")
                .grant(Jenkins.READ)
                .everywhere()
                .to("blind")
                .grant(Item.BUILD)
                .everywhere()
                .to("builder")
                .grant(Item.CONFIGURE)
                .everywhere()
                .to("configurer")
                .grant(NotificationPermissions.TRIAGE)
                .everywhere()
                .to("triage", "reviewer", "blind")
                .grant(NotificationPermissions.CONFIRM)
                .everywhere()
                .to("confirm")
                .grant(NotificationPermissions.MUTE)
                .everywhere()
                .to("mute")
                .grant(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS)
                .everywhere()
                .to("analyst")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
    }

    private static List<NameValuePair> command(Fixture f, String nonce) {
        return new ArrayList<>(List.of(
                new NameValuePair("caseId", f.caseId().toString()),
                new NameValuePair("action", "ACKNOWLEDGE"),
                new NameValuePair("actionId", nonce),
                new NameValuePair("expectedRevision", Long.toString(f.revision())),
                new NameValuePair("evidenceRevision", Long.toString(f.evidenceRevision())),
                new NameValuePair("note", "Reviewing the observed failure")));
    }

    private static org.htmlunit.WebResponse post(
            JenkinsRule j, JenkinsRule.WebClient client, Fixture f, List<NameValuePair> values, boolean crumb)
            throws Exception {
        var request = new WebRequest(
                new URL(j.getURL(), f.job().getUrl() + "change-investigation-feedback/submit"), HttpMethod.POST);
        request.setRequestParameters(values);
        if (crumb) client.addCrumb(request);
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return client.getPage(request).getWebResponse();
    }

    @Test
    void permissionsHaveStableIndependentItemScopedIdsAndCurrentAclMatrix(JenkinsRule j) throws Exception {
        var f = fixture(j, "synthetic-matrix");
        security(j);
        assertEquals(
                "io.jenkins.plugins.changeinvestigator.security.NotificationPermissions.Triage",
                NotificationPermissions.TRIAGE.getId());
        assertEquals(
                "io.jenkins.plugins.changeinvestigator.security.NotificationPermissions.Confirm",
                NotificationPermissions.CONFIRM.getId());
        assertEquals(
                "io.jenkins.plugins.changeinvestigator.security.NotificationPermissions.Mute",
                NotificationPermissions.MUTE.getId());
        for (String name :
                List.of("reader", "builder", "configurer", "triage", "confirm", "mute", "analyst", "admin")) {
            try (var ignored = ACL.as2(User.getById(name, true).impersonate2())) {
                assertEquals(
                        name.equals("triage") || name.equals("admin"),
                        f.job().hasPermission(NotificationPermissions.TRIAGE),
                        name);
                assertEquals(
                        name.equals("confirm") || name.equals("admin"),
                        f.job().hasPermission(NotificationPermissions.CONFIRM),
                        name);
                assertEquals(
                        name.equals("mute") || name.equals("admin"),
                        f.job().hasPermission(NotificationPermissions.MUTE),
                        name);
            }
        }
        for (String name : List.of("reader", "builder", "configurer", "confirm", "mute", "analyst", "blind")) {
            var response = post(
                    j,
                    FeedbackHttpFixtures.client(j).login(name),
                    f,
                    command(f, UUID.randomUUID().toString()),
                    true);
            assertTrue(response.getStatusCode() == 403 || response.getStatusCode() == 404, name);
        }
        assertEquals(
                f.revision(),
                FeedbackAccess.read(f.job(), f.caseId()).orElseThrow().caseRevision());
        assertEquals(
                200,
                post(
                                j,
                                FeedbackHttpFixtures.client(j).login("admin"),
                                f,
                                command(f, UUID.randomUUID().toString()),
                                true)
                        .getStatusCode());
    }

    @Test
    void getAndMissingCrumbCannotMutateButAuthenticatedPostSucceeds(JenkinsRule j) throws Exception {
        var f = fixture(j, "synthetic-post");
        security(j);
        var client = FeedbackHttpFixtures.client(j).login("triage");
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        assertEquals(
                405,
                client.goTo(f.job().getUrl() + "change-investigation-feedback/submit", null)
                        .getWebResponse()
                        .getStatusCode());
        var fields = command(f, UUID.randomUUID().toString());
        assertEquals(403, post(j, client, f, fields, false).getStatusCode());
        var response = post(j, client, f, fields, true);
        assertEquals(200, response.getStatusCode(), response.getContentAsString());
        assertEquals(
                f.revision() + 1,
                JSON.readTree(response.getContentAsString()).path("revision").asLong());
    }

    @Test
    void nonceReplayRemainsPayloadBoundAndRechecksRevokedPermissions(JenkinsRule j) throws Exception {
        var f = fixture(j, "synthetic-replay");
        security(j);
        var client = FeedbackHttpFixtures.client(j).login("triage");
        var fields = command(f, UUID.randomUUID().toString());
        assertEquals(200, post(j, client, f, fields, true).getStatusCode());
        var duplicate = post(j, client, f, fields, true);
        assertEquals(200, duplicate.getStatusCode());
        assertTrue(
                JSON.readTree(duplicate.getContentAsString()).path("duplicate").asBoolean());
        var changed = new ArrayList<>(fields);
        changed.removeIf(v -> v.getName().equals("note"));
        changed.add(new NameValuePair("note", "Different payload"));
        assertEquals(409, post(j, client, f, changed, true).getStatusCode());
        assertEquals(
                409,
                post(j, FeedbackHttpFixtures.client(j).login("reviewer"), f, fields, true)
                        .getStatusCode());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("triage"));
        assertEquals(403, post(j, client, f, fields, true).getStatusCode());
    }

    @Test
    void foreignCaseAndForgedAuthorityFieldsAreRejectedWithoutMutation(JenkinsRule j) throws Exception {
        var first = fixture(j, "synthetic-first");
        var other = fixture(j, "synthetic-other");
        security(j);
        var client = FeedbackHttpFixtures.client(j).login("triage");
        var cross = command(other, UUID.randomUUID().toString());
        assertEquals(404, post(j, client, first, cross, true).getStatusCode());
        for (String key : List.of("actorId", "actorLabel", "job", "jobId", "destinationId", "destinations")) {
            var forged = command(first, UUID.randomUUID().toString());
            forged.add(new NameValuePair(key, "untrusted"));
            assertEquals(400, post(j, client, first, forged, true).getStatusCode(), key);
        }
        var duplicate = command(first, UUID.randomUUID().toString());
        duplicate.add(new NameValuePair("action", "CONFIRM_RESOLUTION"));
        assertEquals(400, post(j, client, first, duplicate, true).getStatusCode());
    }

    @Test
    void currentItemIdentitySurvivesRenameButRejectsDeletionAndSameNameReplacement(JenkinsRule j) throws Exception {
        var f = fixture(j, "synthetic-original");
        var bound = new FeedbackAction(f.job());
        f.job().renameTo("synthetic-renamed");
        assertSame(bound, bound.getTarget());
        security(j);
        var client = FeedbackHttpFixtures.client(j).login("triage");
        assertEquals(
                200,
                post(j, client, f, command(f, UUID.randomUUID().toString()), true)
                        .getStatusCode());
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            f.job().delete();
            j.createFreeStyleProject("synthetic-renamed");
        }
        assertEquals(
                404,
                post(j, client, f, command(f, UUID.randomUUID().toString()), true)
                        .getStatusCode());
    }

    @Test
    void concurrentReviewersCannotOverwriteTheSameRevision(JenkinsRule j) throws Exception {
        var f = fixture(j, "synthetic-concurrent");
        security(j);
        var first = FeedbackHttpFixtures.client(j).login("triage");
        var second = FeedbackHttpFixtures.client(j).login("reviewer");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var a = executor.submit(() -> {
                start.await();
                return post(j, first, f, command(f, UUID.randomUUID().toString()), true)
                        .getStatusCode();
            });
            var b = executor.submit(() -> {
                start.await();
                return post(j, second, f, command(f, UUID.randomUUID().toString()), true)
                        .getStatusCode();
            });
            start.countDown();
            assertEquals(
                    List.of(200, 409),
                    java.util.stream.Stream.of(a.get(), b.get()).sorted().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void historicalReadDoesNotAllocateIdentitiesOrNotificationDirectories(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic-history");
        var run = j.buildAndAssertSuccess(job);
        var before = Files.list(job.getRootDir().toPath());
        List<String> names;
        try (before) {
            names = before.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertTrue(FeedbackAccess.read(job, UUID.randomUUID()).isEmpty());
        assertTrue(FeedbackAccess.forRun(run).isEmpty());
        new FeedbackAction(job);
        try (var after = Files.list(job.getRootDir().toPath())) {
            assertEquals(
                    names, after.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void anonymousAndSystemCannotActEvenWithUnrestrictedPermissions(JenkinsRule j) throws Exception {
        var f = fixture(j, "synthetic-human-only");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .toEveryone());
        assertEquals(
                403,
                post(
                                j,
                                FeedbackHttpFixtures.client(j),
                                f,
                                command(f, UUID.randomUUID().toString()),
                                true)
                        .getStatusCode());
        var status = new java.util.concurrent.atomic.AtomicInteger();
        var text = new java.io.StringWriter();
        var response = (org.kohsuke.stapler.StaplerResponse2) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.kohsuke.stapler.StaplerResponse2.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setStatus")) status.set((Integer) args[0]);
                    if (method.getName().equals("getWriter")) return new java.io.PrintWriter(text);
                    return null;
                });
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            new FeedbackAction(f.job()).doSubmit(null, response);
        }
        assertEquals(403, status.get());
        assertEquals(
                f.revision(),
                FeedbackAccess.read(f.job(), f.caseId()).orElseThrow().caseRevision());
    }
}

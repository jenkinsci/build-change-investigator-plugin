package io.jenkins.plugins.changeinvestigator.notification.feedback;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.email.config.EmailConfiguration;
import io.jenkins.plugins.changeinvestigator.notification.email.config.EmailDestination;
import io.jenkins.plugins.changeinvestigator.notification.email.config.EmailMailProfile;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackConfiguration;
import io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackDestination;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FeedbackSecretsTest {
    private static final String MAIL_SECRET = "synthetic<&mail-password";
    private static final String SLACK_SECRET = "synthetic-slack-private-token";

    @AfterEach
    void stopConfigurationWorkersBeforeTemporaryHomeCleanup() throws Exception {
        List<Class<?>> types = List.of(
                io.jenkins.plugins.changeinvestigator.notification.email.EmailDispatcher.class,
                io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher.class,
                io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime.class);
        var executors = new java.util.ArrayList<java.util.concurrent.ExecutorService>();
        for (Class<?> type : types) {
            var field = type.getDeclaredField("workers");
            field.setAccessible(true);
            for (Object service : Jenkins.get().getExtensionList(type)) {
                executors.add((java.util.concurrent.ExecutorService) field.get(service));
            }
        }
        // Configuration approval can still be writing job identities when the assertions finish.
        io.jenkins.plugins.changeinvestigator.notification.email.EmailDispatcher.shutdown();
        io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher.shutdown();
        io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime.shutdown();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        for (var executor : executors) {
            long remaining = Math.max(0, deadline - System.nanoTime());
            assertTrue(
                    executor.awaitTermination(remaining, java.util.concurrent.TimeUnit.NANOSECONDS),
                    "Configuration worker must terminate before temporary Jenkins home removal");
        }
    }

    private FreeStyleProject approved(JenkinsRule j, boolean credentials) throws Exception {
        var job = j.createFreeStyleProject("synthetic-withholding");
        if (credentials) {
            var store = SystemCredentialsProvider.getInstance();
            store.getCredentials()
                    .add(new UsernamePasswordCredentialsImpl(
                            CredentialsScope.GLOBAL, "synthetic-mail", "Synthetic", "synthetic-user", MAIL_SECRET));
            store.getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL, "synthetic-slack", "Synthetic", Secret.fromString(SLACK_SECRET)));
        }
        var email = EmailConfiguration.get();
        email.setEnabled(true);
        var profile = new EmailMailProfile(
                null, "Synthetic", "smtp.example.invalid", 587, "STARTTLS_REQUIRED", false, "synthetic-mail");
        email.replaceConfiguration(
                List.of(profile),
                List.of(new EmailDestination(
                        null,
                        "Synthetic",
                        "bci@example.invalid",
                        "triage@example.invalid",
                        profile.getId(),
                        job.getFullName(),
                        true)));
        var emailVerified = EmailDestination.class.getDeclaredField("verified");
        emailVerified.setAccessible(true);
        emailVerified.setBoolean(email.getDestinations().get(0), true);
        var slack = SlackConfiguration.get();
        slack.setEnabled(true);
        slack.replaceDestinations(List.of(new SlackDestination(
                null, "Synthetic", "Synthetic", "TDEMO123", "CDEMO123", "synthetic-slack", job.getFullName(), true)));
        var slackVerified = SlackDestination.class.getDeclaredField("verified");
        slackVerified.setAccessible(true);
        slackVerified.setBoolean(slack.getDestinations().get(0), true);
        return job;
    }

    private static FeedbackRequest acknowledge(long revision, long evidence, String note) {
        return new FeedbackRequest(
                FeedbackRequest.Action.ACKNOWLEDGE,
                UUID.randomUUID(),
                revision,
                "",
                evidence,
                "",
                "",
                "",
                "",
                note,
                null,
                List.of(),
                0);
    }

    @Test
    void exactApprovedSecretsAndEscapedFormsAreWithheldBeforeAuditPersistence(JenkinsRule j) throws Exception {
        var job = approved(j, true);
        UUID jobId = UUID.fromString(StableIdentities.jobId(job));
        var engine = new NotificationEngine(
                job.getRootDir().toPath(),
                jobId,
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
        engine.arm(0);
        var record = engine.ingest(
                FeedbackHttpFixtures.input(jobId, 1, "commit-a", false), List.of(), System.currentTimeMillis());
        for (String text : List.of(MAIL_SECRET, "synthetic&lt;&amp;mail-password", SLACK_SECRET)) {
            assertTrue(FeedbackSecrets.forJob(job).test(text));
            var request = acknowledge(record.caseRevision(), record.evidenceRevision(), text);
            assertThrows(
                    java.io.IOException.class,
                    () -> engine.feedback(
                            record.investigationId(),
                            request,
                            new FeedbackActor("reviewer", "Synthetic reviewer"),
                            () -> true,
                            FeedbackSecrets.forJob(job),
                            System.currentTimeMillis()));
        }
        var saved = FeedbackAccess.read(job, record.investigationId()).orElseThrow();
        assertTrue(saved.feedback().records().isEmpty());
        String file = Files.readString(
                job.getRootDir().toPath().resolve("bci-notifications/cases/" + record.investigationId() + ".json"));
        assertFalse(file.contains(MAIL_SECRET));
        assertFalse(file.contains(SLACK_SECRET));
        assertFalse(file.contains("synthetic&lt;&amp;mail-password"));
        assertFalse(FeedbackSecrets.forJob(job).test("Reviewed the observed compiler failure"));
        engine.feedback(
                record.investigationId(),
                acknowledge(record.caseRevision(), record.evidenceRevision(), "Reviewed the observed compiler failure"),
                new FeedbackActor("reviewer", "Synthetic reviewer"),
                () -> true,
                FeedbackSecrets.forJob(job),
                System.currentTimeMillis());
        assertEquals(
                1,
                FeedbackAccess.read(job, record.investigationId())
                        .orElseThrow()
                        .feedback()
                        .records()
                        .size());
    }

    @Test
    void unavailableApprovedCredentialsFailClosedWithSafeEndpointResponse(JenkinsRule j) throws Exception {
        var job = approved(j, false);
        UUID jobId = UUID.fromString(StableIdentities.jobId(job));
        var engine = new NotificationEngine(
                job.getRootDir().toPath(),
                jobId,
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
        engine.arm(0);
        var record = engine.ingest(
                FeedbackHttpFixtures.input(jobId, 1, "commit-a", false), List.of(), System.currentTimeMillis());
        assertThrows(
                IllegalStateException.class, () -> FeedbackSecrets.forJob(job).test("Ordinary text"));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("reviewer"));
        var client = FeedbackHttpFixtures.client(j).login("reviewer");
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        var request = new WebRequest(
                new URL(j.getURL(), job.getUrl() + "change-investigation-feedback/submit"), HttpMethod.POST);
        request.setRequestParameters(List.of(
                new NameValuePair("caseId", record.investigationId().toString()),
                new NameValuePair("action", "ACKNOWLEDGE"),
                new NameValuePair("actionId", UUID.randomUUID().toString()),
                new NameValuePair("expectedRevision", Long.toString(record.caseRevision())),
                new NameValuePair("evidenceRevision", Long.toString(record.evidenceRevision())),
                new NameValuePair("note", "Ordinary review")));
        client.addCrumb(request);
        var response = client.getPage(request).getWebResponse();
        assertEquals(409, response.getStatusCode());
        assertTrue(response.getContentAsString().contains("INVESTIGATION_CHANGED"));
        assertFalse(response.getContentAsString().contains("synthetic-mail"));
        assertFalse(response.getContentAsString().contains("Oops"));
        assertTrue(FeedbackAccess.read(job, record.investigationId())
                .orElseThrow()
                .feedback()
                .records()
                .isEmpty());
    }

    @Test
    void currentCredentialRotationIsResolvedByEachNewFeedbackRequest(JenkinsRule j) throws Exception {
        var job = approved(j, true);
        var first = FeedbackSecrets.forJob(job);
        assertTrue(first.test(MAIL_SECRET));
        var store = SystemCredentialsProvider.getInstance();
        store.getCredentials()
                .removeIf(c -> c instanceof UsernamePasswordCredentialsImpl credential
                        && credential.getId().equals("synthetic-mail"));
        store.getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "synthetic-mail",
                        "Synthetic",
                        "synthetic-user",
                        "synthetic-rotated-password"));
        assertTrue(FeedbackSecrets.forJob(job).test("synthetic-rotated-password"));
        assertFalse(FeedbackSecrets.forJob(job).test("Ordinary review"));
    }
}

package io.jenkins.plugins.changeinvestigator.notification.feedback;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Item;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import io.jenkins.plugins.changeinvestigator.security.NotificationPermissions;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FeedbackViewTest {
    @Test
    void nativeReviewPageSeparatesReadTriageAndConfirmAndEscapesHumanText(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic-review-page");
        UUID jobId = UUID.fromString(StableIdentities.jobId(job));
        var engine = new NotificationEngine(
                job.getRootDir().toPath(),
                jobId,
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
        engine.arm(0);
        var record = engine.ingest(FeedbackHttpFixtures.input(jobId, 1, "commit-a", false), List.of(), 1000);
        String note = "<img src=x onerror=window.feedbackAttack()>";
        engine.feedback(
                record.investigationId(),
                new FeedbackRequest(
                        FeedbackRequest.Action.ACKNOWLEDGE,
                        UUID.randomUUID(),
                        record.caseRevision(),
                        "",
                        record.evidenceRevision(),
                        "",
                        "",
                        "",
                        "",
                        note,
                        null,
                        List.of(),
                        0),
                new FeedbackActor("reviewer", "Synthetic <reviewer>"),
                () -> true,
                text -> false,
                2000);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader", "triage", "confirm")
                .grant(NotificationPermissions.TRIAGE)
                .everywhere()
                .to("triage")
                .grant(NotificationPermissions.CONFIRM)
                .everywhere()
                .to("confirm"));
        String path = job.getUrl() + "change-investigation-feedback/?caseId=" + record.investigationId();
        for (String user : List.of("reader", "triage", "confirm")) {
            var client = j.createWebClient();
            client.getOptions().setJavaScriptEnabled(false);
            client.login(user);
            var page = client.goTo(path);
            assertTrue(page.asNormalizedText().contains("Human review history"));
            assertTrue(page.asNormalizedText().contains("Synthetic <reviewer>"));
            assertTrue(page.getByXPath("//img[@onerror]").isEmpty());
            assertTrue(
                    page.getByXPath("//script[contains(., 'feedbackAttack')]").isEmpty());
            if (user.equals("reader")) {
                assertNull(page.getElementById("bci-feedback-form"));
                assertTrue(page.asNormalizedText().contains("Read-only access"));
            } else {
                org.htmlunit.html.HtmlForm form = page.getHtmlElementById("bci-feedback-form");
                assertTrue(
                        form.getAttribute("class").contains("no-json"),
                        "Flat feedback form preserves Jenkins crumb behavior without structured JSON fields");
                org.htmlunit.html.DomElement status = page.getHtmlElementById("bci-review-result");
                org.htmlunit.html.DomElement refresh = page.getHtmlElementById("bci-review-refresh");
                assertTrue(
                        status.getByXPath(".//*[@id='bci-review-refresh']").isEmpty(),
                        "Refresh navigation must be independent of the changing status message");
                status.setTextContent("Human review recorded. Refresh to see the current investigation.");
                assertSame(
                        refresh,
                        page.getElementById("bci-review-refresh"),
                        "Updating submission status must not remove the refresh link");
                var choices = form.getSelectByName("action").getOptions().stream()
                        .map(o -> o.getValueAttribute())
                        .toList();
                if (user.equals("triage")) assertEquals(List.of("ACKNOWLEDGE", "NOT_RELATED"), choices);
                else {
                    assertTrue(choices.contains("CONFIRM_CAUSE"));
                    assertFalse(choices.contains("ACKNOWLEDGE"));
                }
            }
        }
    }

    @Test
    void deliveryCapacityWarningIsVisibleOnlyToAdministrators(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic-delivery-warning");
        UUID jobId = UUID.fromString(StableIdentities.jobId(job));
        var engine = new NotificationEngine(job.getRootDir().toPath(), jobId);
        engine.arm(0);
        var record = engine.ingest(FeedbackHttpFixtures.input(jobId, 1, "commit-a", false), List.of(), 1000);
        var store = new io.jenkins.plugins.changeinvestigator.notification.persistence.NotificationStore(
                job.getRootDir().toPath(), jobId);
        var saved = store.load(record.investigationId()).orElseThrow();
        store.update(record.investigationId(), saved.revision(), aggregate -> {
            ((com.fasterxml.jackson.databind.node.ArrayNode) aggregate.path("audit"))
                    .addObject()
                    .put("code", "HUMAN_CORRECTION_DELIVERY_UNAVAILABLE")
                    .put("at", 2000)
                    .put("revision", record.caseRevision());
            return aggregate;
        });
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
        String path = job.getUrl() + "change-investigation-feedback/?caseId=" + record.investigationId();
        for (String user : List.of("reader", "admin")) {
            var client = FeedbackHttpFixtures.client(j).login(user);
            var page = client.goTo(path);
            assertEquals(200, page.getWebResponse().getStatusCode());
            String text = page.asNormalizedText();
            assertEquals(
                    user.equals("admin"), text.contains("A correction at review revision " + record.caseRevision()));
            assertEquals(user.equals("admin"), text.contains("it will not be replayed automatically"));
        }
    }

    @Test
    void historicalInvestigationPageRendersWithoutAllocatingFeedbackState(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic-historical-review");
        var run = j.buildAndAssertSuccess(job);
        var action = new InvestigationAction(
                run,
                BuildInvestigationEvidence.builder()
                        .jobFullName(job.getFullName())
                        .failedBuildNumber(run.getNumber())
                        .failedBuildResult("FAILURE")
                        .build());
        run.addAction(action);
        run.save();
        assertNull(action.getFeedback());
        var client = j.createWebClient();
        client.getOptions().setJavaScriptEnabled(false);
        var page = client.getPage(run, "change-investigation/");
        assertEquals(200, page.getWebResponse().getStatusCode());
        assertTrue(page.asNormalizedText().contains("Build Change Investigation"));
        assertFalse(Files.exists(job.getRootDir().toPath().resolve("bci-notification-job-id")));
        assertFalse(Files.exists(job.getRootDir().toPath().resolve("bci-notifications")));
    }

    @Test
    void corruptOrOversizedStoredCaseShowsUnavailableWithoutMutationAndBadQueryIs400(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("synthetic-corrupt-review");
        UUID jobId = UUID.fromString(StableIdentities.jobId(job));
        var engine = new NotificationEngine(
                job.getRootDir().toPath(),
                jobId,
                j.jenkins.getRootDir().toPath(),
                UUID.fromString(StableIdentities.controllerId()));
        engine.arm(0);
        var record = engine.ingest(FeedbackHttpFixtures.input(jobId, 1, "commit-a", false), List.of(), 1000);
        var file = job.getRootDir().toPath().resolve("bci-notifications/cases/" + record.investigationId() + ".json");
        var client = j.createWebClient();
        client.getOptions().setJavaScriptEnabled(false);
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        String path = job.getUrl() + "change-investigation-feedback/";
        for (String contents :
                List.of(
                        "{malformed",
                        "null",
                        "",
                        " "
                                .repeat(io.jenkins.plugins.changeinvestigator.notification.persistence.NotificationStore
                                                .MAX_RECORD_BYTES
                                        + 1))) {
            Files.writeString(file, contents);
            var page = client.goTo(path + "?caseId=" + record.investigationId());
            assertEquals(200, page.getWebResponse().getStatusCode());
            assertTrue(page.asNormalizedText().contains("Human review is unavailable"));
            assertFalse(page.asNormalizedText().contains("Oops"));
            assertEquals(contents, Files.readString(file));
        }
        assertEquals(
                400,
                client.goTo(path + "?caseId=invalid", null).getWebResponse().getStatusCode());
        assertEquals(
                400,
                client.goTo(path + "?caseId=" + record.investigationId() + "&caseId=" + UUID.randomUUID(), null)
                        .getWebResponse()
                        .getStatusCode());
        assertEquals(400, client.goTo(path, null).getWebResponse().getStatusCode());
    }
}

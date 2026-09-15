package io.jenkins.plugins.changeinvestigator.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.net.URL;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class SlackResponderManagementTest {
    private static final String PATH = "manage/bci-slack-responders/";

    @Test
    void emptyStateAndFilteredNoMatchAreDistinct(JenkinsRule j) throws Exception {
        var client = j.createWebClient();
        var empty = client.goTo(PATH);
        assertTrue(empty.asNormalizedText().contains("0 matching mappings · 0 total"));
        assertTrue(empty.getByXPath("//*[@role='alert']").isEmpty());
        assertTrue(empty.getFormByName("mapping").asNormalizedText().contains("Save mapping"));
        assertTrue(empty.asNormalizedText().contains("No responder mappings configured yet."));
        assertFalse(empty.asNormalizedText().contains("No mappings match this search."));
        assertTrue(empty.getByXPath("//nav[@aria-label='Mapping pages']").isEmpty());
        assertEquals("#mapping-editor", empty.getAnchorByText("New mapping").getHrefAttribute());
        SlackConfiguration.get().addMapping("Alex", "U12345678");
        assertTrue(client.goTo(PATH).asNormalizedText().contains("1 matching mapping · 1 total"));
        var filtered = client.goTo(PATH + "?q=unmatched");
        assertTrue(filtered.asNormalizedText().contains("No mappings match this search."));
        assertFalse(filtered.asNormalizedText().contains("No responder mappings configured yet."));
        assertTrue(filtered.getByXPath("//nav[@aria-label='Mapping pages']").isEmpty());
        var failedValidation = j.submit(client.goTo(PATH)
                .getFormByName("validate-"
                        + SlackConfiguration.get().getResponderMappings().get(0).getId()));
        assertTrue(failedValidation.asNormalizedText().contains("Verify the Slack connection in System configuration"));
        assertEquals(1, SlackConfiguration.get().getResponderMappings().size());
    }

    @Test
    void nativeFormsAddEditRemoveAndRejectDuplicates(JenkinsRule j) throws Exception {
        var client = j.createWebClient();
        var form = client.goTo(PATH).getFormByName("mapping");
        form.getInputByName("identity").setValue("Alex Morrison");
        form.getInputByName("slackUser").setValue("U12345678");
        j.submit(form);
        var config = SlackConfiguration.get();
        var entry = config.getResponderMappings().get(0);
        assertFalse(entry.getId().isBlank());
        form = client.goTo(PATH + "?id=" + entry.getId()).getFormByName("mapping");
        assertTrue(form.asNormalizedText().contains("Save changes"));
        form.getInputByName("slackUser").setValue("U87654321");
        j.submit(form);
        assertEquals(entry.getId(), config.getResponderMappings().get(0).getId());
        assertEquals(
                "U87654321",
                new SlackConfiguration().getResponderMappings().get(0).getSlackUser());
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        form = client.goTo(PATH).getFormByName("mapping");
        form.getInputByName("identity").setValue(" alex morrison ");
        form.getInputByName("slackUser").setValue("U12345678");
        HtmlPage invalid = j.submit(form);
        assertEquals(400, invalid.getWebResponse().getStatusCode());
        assertEquals(1, config.getResponderMappings().size());
        assertNotNull(invalid.getFirstByXPath("//*[@role='alert']"));
        assertNotNull(invalid.getFirstByXPath("//form[@name='mapping']//*[@id='mapping-identity-error']"));
        assertTrue(invalid.getByXPath("//div[contains(@class,'jenkins-alert-danger')]")
                .isEmpty());
        j.submit(client.goTo(PATH).getFormByName("remove-" + entry.getId()));
        assertTrue(config.getResponderMappings().isEmpty());
    }

    @Test
    void searchAndPaginationBoundLargeMappingLists(JenkinsRule j) throws Exception {
        for (int i = 0; i < 110; i++) SlackConfiguration.get().addMapping("Engineer " + i, "U12345678");
        var client = j.createWebClient();
        var page = client.goTo(PATH);
        assertEquals(
                25,
                page.getByXPath("//table[@id='responder-mappings']/tbody/tr").size());
        page = client.goTo(PATH + "?page=5");
        assertEquals(
                10,
                page.getByXPath("//table[@id='responder-mappings']/tbody/tr").size());
        page = client.goTo(PATH + "?q=Engineer%20109");
        assertEquals(
                1, page.getByXPath("//table[@id='responder-mappings']/tbody/tr").size());
        assertTrue(page.asNormalizedText().contains("Engineer 109"));
        assertTrue(page.asNormalizedText().contains("1 matching mapping · 110 total"));
    }

    @Test
    void fieldErrorsRequireSubmissionAndStayBesideTheInput(JenkinsRule j) throws Exception {
        var client = j.createWebClient();
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        var initial = client.goTo(PATH);
        assertTrue(initial.getByXPath("//*[@role='alert']").isEmpty());
        var form = initial.getFormByName("mapping");
        form.getInputByName("identity").setValue(" ");
        form.getInputByName("slackUser").setValue("U12345678");
        HtmlPage invalid = j.submit(form);
        assertEquals(400, invalid.getWebResponse().getStatusCode());
        assertNotNull(invalid.getFirstByXPath(
                "//input[@id='mapping-identity']/following-sibling::div[@id='mapping-identity-error']"));
        assertTrue(invalid.getByXPath("//div[contains(@class,'jenkins-alert-danger')]")
                .isEmpty());
        assertTrue(client.goTo(PATH).getByXPath("//*[@role='alert']").isEmpty());
        form = client.goTo(PATH).getFormByName("mapping");
        form.getInputByName("identity").setValue("Alex");
        form.getInputByName("slackUser").setValue("invalid");
        invalid = j.submit(form);
        assertEquals(400, invalid.getWebResponse().getStatusCode());
        assertNotNull(invalid.getFirstByXPath(
                "//input[@id='mapping-member']/following-sibling::div[@id='mapping-member-error']"));
        assertTrue(SlackConfiguration.get().getResponderMappings().isEmpty());
    }

    @Test
    void administrationAndPostCrumbRequired(JenkinsRule j) throws Exception {
        j.jenkins.setCrumbIssuer(new hudson.security.csrf.DefaultCrumbIssuer(false));
        SlackConfiguration.get().addMapping("Alex", "U12345678");
        String id = SlackConfiguration.get().getResponderMappings().get(0).getId();
        var client = j.createWebClient();
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        assertEquals(405, client.goTo(PATH + "remove?id=" + id).getWebResponse().getStatusCode());
        assertEquals(
                405, client.goTo(PATH + "validate?id=" + id).getWebResponse().getStatusCode());
        WebRequest noCrumb = new WebRequest(new URL(j.getURL(), PATH + "remove?id=" + id), HttpMethod.POST);
        assertEquals(403, client.getPage(noCrumb).getWebResponse().getStatusCode());
        WebRequest noValidationCrumb = new WebRequest(new URL(j.getURL(), PATH + "validate?id=" + id), HttpMethod.POST);
        assertEquals(403, client.getPage(noValidationCrumb).getWebResponse().getStatusCode());
        assertEquals(1, SlackConfiguration.get().getResponderMappings().size());
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));
        var action = j.jenkins.getExtensionList(SlackResponderManagement.class).get(0);
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, action::getTarget);
            assertThrows(AccessDeniedException.class, () -> action.doSave(null, null, "", "Other", "U87654321"));
            assertThrows(AccessDeniedException.class, () -> action.doRemove(null, null, id));
            assertThrows(AccessDeniedException.class, () -> action.doValidate(null, null, id));
        }
        client.login("reader");
        assertEquals(403, client.goTo(PATH).getWebResponse().getStatusCode());
    }
}

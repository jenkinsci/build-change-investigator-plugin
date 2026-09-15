package io.jenkins.plugins.changeinvestigator.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.slack.transport.ConnectionResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackRoute;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SlackWorkspaceVerificationTest {
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile String proofOverride;
    private static volatile int failureMode;

    @BeforeEach
    void prepare() {
        TOKENS.clear();
        TOKENS.put("saved-bot", "xoxb-synthetic-saved-token");
        TOKENS.put("new-bot", "xoxb-synthetic-new-token");
        CALLS.set(0);
        proofOverride = null;
        failureMode = 0;
    }

    @Test
    void verifiedUnsavedSelectionBecomesVisibleOnlyWhenThatCredentialIsSaved(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setCredentialId("saved-bot");
        assertEquals("", config.getVerifiedWorkspace());
        assertEquals(FormValidation.Kind.OK, config.doTestConnection("new-bot", "#alerts").kind);
        assertEquals("", config.getVerifiedWorkspace());
        config.setCredentialId("new-bot");
        assertEquals("Demo Workspace", config.getVerifiedWorkspace());
        config.load();
        assertEquals("Demo Workspace", config.getVerifiedWorkspace());
        assertEquals(1, CALLS.get());
    }

    @Test
    void secretRotationOrCredentialRemovalInvalidatesCachedWorkspaceWithoutNetwork(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setCredentialId("saved-bot");
        config.doTestConnection("saved-bot", "#alerts");
        assertTrue(config.isWorkspaceVerified());
        TOKENS.put("saved-bot", "xoxb-synthetic-rotated-token");
        assertEquals("", config.getVerifiedWorkspace());
        assertFalse(config.isWorkspaceVerified());
        TOKENS.remove("saved-bot");
        assertEquals("", config.getVerifiedWorkspace());
        assertEquals(1, CALLS.get());
    }

    @Test
    void mismatchedProofCannotPublishVerifiedMarkupOrPersistWorkspace(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setCredentialId("saved-bot");
        proofOverride = SlackTransport.get().credentialFingerprint("new-bot");
        FormValidation result = config.doTestConnection("saved-bot", "#alerts");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertFalse(result.renderHtml().contains("data-workspace"));
        assertEquals("", config.getVerifiedWorkspace());
        config.load();
        assertEquals("", config.getVerifiedWorkspace());
    }

    @Test
    void verificationMarkupContainsNamesButNoRouteIdsOrSecretFingerprint(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setCredentialId("saved-bot");
        String html = config.doTestConnection("saved-bot", "#alerts").renderHtml();
        assertTrue(html.contains("Connected to Demo Workspace"));
        assertTrue(html.contains("Ready to post to #alerts"));
        assertFalse(html.contains("T12345678"));
        assertFalse(html.contains("C12345678"));
        assertFalse(html.contains(TOKENS.get("saved-bot")));
        assertFalse(html.contains(SlackTransport.get().credentialFingerprint("saved-bot")));
        long revision = config.getRevision();
        for (int i = 0; i < 5; i++) assertEquals("Demo Workspace", config.getVerifiedWorkspace());
        assertEquals(revision, config.getRevision());
        assertEquals(1, CALLS.get());
    }

    @Test
    void failedRecheckClearsKnownWorkspaceProofWithoutChangingRevision(JenkinsRule j) {
        var config = SlackConfiguration.get();
        config.setCredentialId("saved-bot");
        long revision = config.getRevision();
        for (int mode = 1; mode <= 3; mode++) {
            failureMode = 0;
            proofOverride = null;
            assertEquals(FormValidation.Kind.OK, config.doTestConnection("saved-bot", "#alerts").kind);
            assertEquals("Demo Workspace", config.getVerifiedWorkspace());
            failureMode = mode;
            if (mode == 3) proofOverride = SlackTransport.get().credentialFingerprint("new-bot");
            FormValidation failure = config.doTestConnection("saved-bot", "#alerts");
            assertEquals(FormValidation.Kind.ERROR, failure.kind);
            assertTrue(failure.renderHtml().contains("bci-slack-verification-failed"));
            assertTrue(failure.renderHtml().contains("data-credential=\"saved-bot\""));
            assertFalse(failure.renderHtml().contains("data-workspace"));
            assertFalse(failure.renderHtml().contains("provider diagnostic"));
            assertEquals("", config.getVerifiedWorkspace());
            config.load();
            assertEquals("", config.getVerifiedWorkspace());
            assertEquals(revision, config.getRevision());
        }
    }

    @Test
    void verificationMetadataKeepsLegacyXmlNamesWithoutStoringTheBotSecret(JenkinsRule j) throws Exception {
        var config = SlackConfiguration.get();
        config.setCredentialId("saved-bot");
        assertEquals(FormValidation.Kind.OK, config.doTestConnection("saved-bot", "#alerts").kind);
        String digest = SlackTransport.get().credentialFingerprint("saved-bot");
        config.setResponderMappings(java.util.List.of(ResponderMapping.associated(
                new ResponderMapping("Demo Engineer", "U12345678"),
                "T12345678",
                "Demo Workspace",
                "demo",
                "VERIFIED",
                "saved-bot",
                digest)));
        var path = j.jenkins.getRootDir().toPath().resolve(SlackConfiguration.class.getName() + ".xml");
        String xml = java.nio.file.Files.readString(path);
        assertTrue(xml.contains("<intendedCredential>saved-bot</intendedCredential>"));
        assertTrue(xml.contains("<verifiedCredentialFingerprint>" + digest + "</verifiedCredentialFingerprint>"));
        assertFalse(xml.contains("<intendedCredentialId>"));
        assertFalse(xml.contains("<verifiedAuthenticationDigest>"));
        assertFalse(xml.contains(TOKENS.get("saved-bot")));
        var restored = new SlackConfiguration();
        assertEquals("Demo Workspace", restored.getVerifiedWorkspace());
        var mapping = restored.getResponderMappings().get(0);
        assertEquals("saved-bot", mapping.intendedCredentialId());
        assertEquals(digest, mapping.intendedFingerprint());
        assertEquals("VERIFIED", mapping.getVerificationStatus());
        TOKENS.put("saved-bot", "xoxb-synthetic-rotated-token");
        assertEquals("", restored.getVerifiedWorkspace());
    }

    @TestExtension
    public static final class WorkspaceTransport extends SlackTransport {
        public WorkspaceTransport() {
            super(URI.create("http://127.0.0.1/"), TOKENS::get);
        }

        @Override
        public ConnectionResult checkConnection(String credential, String channel) {
            CALLS.incrementAndGet();
            if (failureMode == 1) return new ConnectionResult(false, "Unable to connect to Slack.", "", "", null);
            if (failureMode == 2) throw new IllegalStateException("provider diagnostic");
            String proof = proofOverride == null ? credentialFingerprint(credential) : proofOverride;
            return new ConnectionResult(
                    true,
                    "Connected and ready to post.",
                    "Demo Workspace",
                    "#alerts",
                    new SlackRoute("T12345678", "C12345678"),
                    0,
                    proof);
        }
    }
}

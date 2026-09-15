package io.jenkins.plugins.changeinvestigator.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import hudson.ExtensionList;
import io.jenkins.plugins.changeinvestigator.slack.transport.ConnectionResult;
import io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackRoute;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResponderMappingVerificationTest {
    private static volatile boolean available;
    private static volatile String workspace;
    private static volatile String fingerprint;
    private static volatile boolean staleProof;
    private static volatile int requests;
    private static volatile boolean invalid;
    private static volatile boolean connectionAvailable;
    private static volatile java.util.concurrent.CountDownLatch memberEntered;
    private static volatile java.util.concurrent.CountDownLatch memberRelease;

    @BeforeEach
    void reset() {
        available = true;
        workspace = "T12345678";
        fingerprint = "proof";
        staleProof = false;
        invalid = false;
        connectionAvailable = true;
        memberEntered = null;
        memberRelease = null;
        requests = 0;
    }

    private SlackConfiguration configure() {
        var transports = ExtensionList.lookup(SlackTransport.class);
        List.copyOf(transports).stream()
                .filter(value -> value.getClass() == SlackTransport.class)
                .forEach(transports::remove);
        var config = SlackConfiguration.get();
        config.setCredentialId("synthetic-bot");
        config.setTagResponders(true);
        config.doTestConnection("synthetic-bot", "#alerts");
        return config;
    }

    @Test
    void explicitVerificationPersistsFriendlyNameAndWorkspaceWithoutChangingRouting(JenkinsRule jenkins) {
        var config = configure();
        long routing = config.getRevision();
        config.addMapping("Demo Engineer", "U12345678");
        var mapping = config.getResponderMappings().get(0);
        assertEquals("Demo Engineer Slack", mapping.getDisplayName());
        assertEquals("Demo workspace", mapping.getWorkspaceName());
        assertTrue(config.isMappingWorkspaceCurrent(mapping));
        assertEquals("U12345678", config.mappedSlackUser("demo engineer", workspace));
        assertEquals(routing, config.getRevision());
        int beforeRead = requests;
        config.load();
        assertEquals(mapping.getId(), config.getResponderMappings().get(0).getId());
        assertEquals("Demo Engineer Slack", config.getResponderMappings().get(0).getDisplayName());
        assertEquals(beforeRead, requests);
    }

    @Test
    void workspaceSwitchCannotReuseOldMappingUntilExplicitValidation(JenkinsRule jenkins) {
        var config = configure();
        config.addMapping("Demo Engineer", "U12345678");
        String id = config.getResponderMappings().get(0).getId();
        workspace = "T87654321";
        fingerprint = "new-proof";
        config.doTestConnection("synthetic-bot", "#alerts");
        assertFalse(
                config.isMappingWorkspaceCurrent(config.getResponderMappings().get(0)));
        assertNull(config.mappedSlackUser("Demo Engineer", workspace));
        int beforeRead = requests;
        assertEquals("T12345678", config.getResponderMappings().get(0).getWorkspaceId());
        assertEquals(beforeRead, requests);
        assertTrue(config.validateMapping(id));
        assertEquals("U12345678", config.mappedSlackUser("Demo Engineer", workspace));
    }

    @Test
    void outagesAllowShapeValidEditsAndPreserveUnchangedMemberMetadata(JenkinsRule jenkins) {
        var config = configure();
        config.addMapping("Demo Engineer", "U12345678");
        String id = config.getResponderMappings().get(0).getId();
        available = false;
        config.updateMapping(id, "Renamed Engineer", "U12345678");
        assertEquals("Demo Engineer Slack", config.getResponderMappings().get(0).getDisplayName());
        assertEquals("T12345678", config.getResponderMappings().get(0).getWorkspaceId());
        assertFalse(config.validateMapping(id));
        assertEquals(
                "Needs validation",
                config.getMappingStatus(config.getResponderMappings().get(0)));
        assertNull(config.mappedSlackUser("Renamed Engineer", workspace));
        config.updateMapping(id, "Renamed Engineer", "U87654321");
        assertEquals("T12345678", config.getResponderMappings().get(0).getWorkspaceId());
        assertEquals(
                "Needs validation",
                config.getMappingStatus(config.getResponderMappings().get(0)));
        assertEquals("", config.getResponderMappings().get(0).getDisplayName());
        assertNull(config.mappedSlackUser("Renamed Engineer", workspace));
        config.addMapping("Another Engineer", "U11223344");
        assertEquals(2, config.getResponderMappings().size());
        assertThrows(IllegalArgumentException.class, () -> config.addMapping("another engineer", "U44332211"));
    }

    @Test
    void staleCredentialProofCannotBindNewMapping(JenkinsRule jenkins) {
        var config = configure();
        staleProof = true;
        config.addMapping("Demo Engineer", "U12345678");
        assertEquals("T12345678", config.getResponderMappings().get(0).getWorkspaceId());
        assertEquals(
                "Needs validation",
                config.getMappingStatus(config.getResponderMappings().get(0)));
        assertNull(config.mappedSlackUser("Demo Engineer", workspace));
    }

    @Test
    void sourcesAreUniqueWithinWorkspaceAndMultipleSourcesMayShareMember(JenkinsRule jenkins) {
        var config = configure();
        config.addMapping("Demo Engineer", "U12345678");
        String firstId = config.getResponderMappings().get(0).getId();
        config.addMapping("Second Identity", "U12345678");
        assertEquals("U12345678", config.mappedSlackUser("Second Identity", "T12345678"));
        assertThrows(IllegalArgumentException.class, () -> config.addMapping(" demo ENGINEER ", "U87654321"));
        workspace = "T87654321";
        fingerprint = "new-proof";
        config.doTestConnection("synthetic-bot", "#alerts");
        config.addMapping("Demo Engineer", "U87654321");
        assertEquals("U12345678", config.mappedSlackUser("Demo Engineer", "T12345678"));
        assertEquals("U87654321", config.mappedSlackUser("Demo Engineer", "T87654321"));
        assertThrows(IllegalArgumentException.class, () -> config.validateMapping(firstId));
        assertEquals("T12345678", config.getResponderMappings().get(0).getWorkspaceId());
    }

    @Test
    void workspaceProofRechecksPendingIntentAsynchronouslyButNeverForeignWorkspace(JenkinsRule jenkins)
            throws Exception {
        var config = configure();
        config.addMapping("Existing", "U12345678");
        String oldId = config.getResponderMappings().get(0).getId();
        workspace = "T87654321";
        fingerprint = "next-proof";
        available = false;
        config.setCredentialId("next-bot");
        config.addMapping("Pending", "U87654321");
        assertEquals("", config.getResponderMappings().get(1).getWorkspaceId());
        available = true;
        config.doTestConnection("next-bot", "#alerts");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && !"Verified"
                        .equals(config.getMappingStatus(
                                config.getResponderMappings().get(1)))) Thread.sleep(20);
        assertEquals(
                "Verified",
                config.getMappingStatus(config.getResponderMappings().get(1)));
        assertEquals(
                "Workspace mismatch",
                config.getMappingStatus(config.getResponderMappings().get(0)));
        assertEquals(oldId, config.getResponderMappings().get(0).getId());
        assertEquals("T12345678", config.getResponderMappings().get(0).getWorkspaceId());
    }

    @Test
    void unavailableBulkValidationStopsAfterOneRowAndLeavesAllPending(JenkinsRule jenkins) throws Exception {
        var config = configure();
        config.addMapping("First", "U12345678");
        config.addMapping("Second", "U87654321");
        available = false;
        int before = requests;
        config.doTestConnection("synthetic-bot", "#alerts");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && requests == before) Thread.sleep(20);
        Thread.sleep(100);
        assertEquals(before + 1, requests);
        assertTrue(config.getResponderMappings().stream()
                .allMatch(value -> "Needs validation".equals(config.getMappingStatus(value))));
        assertNull(config.mappedSlackUser("First", workspace));
    }

    @Test
    void authenticatedInvalidMemberStatusDisablesMention(JenkinsRule jenkins) {
        var config = configure();
        config.addMapping("Demo Engineer", "U12345678");
        String id = config.getResponderMappings().get(0).getId();
        invalid = true;
        assertFalse(config.validateMapping(id));
        assertEquals(
                "Invalid", config.getMappingStatus(config.getResponderMappings().get(0)));
        assertNull(config.mappedSlackUser("Demo Engineer", workspace));
    }

    @Test
    void unboundIntentNeverAutomaticallyFollowsAReusedCredentialId(JenkinsRule jenkins) {
        var config = configure();
        config.setCredentialId("unverified-bot");
        available = false;
        config.addMapping("Pending", "U12345678");
        var mapping = config.getResponderMappings().get(0);
        assertTrue(
                SlackConfiguration.eligibleForAutomaticValidation(mapping, "unverified-bot", fingerprint, "T12345678"));
        assertFalse(SlackConfiguration.eligibleForAutomaticValidation(
                mapping, "unverified-bot", "replacement-proof", "T87654321"));
        assertFalse(SlackConfiguration.eligibleForAutomaticValidation(
                new ResponderMapping("Legacy", "U87654321"), "unverified-bot", fingerprint, "T12345678"));
    }

    @Test
    void unchangedCredentialSaveDoesNotResetOrRevalidateMappings(JenkinsRule jenkins) throws Exception {
        var config = configure();
        config.addMapping("Demo Engineer", "U12345678");
        int before = requests;
        long policy = config.getMappingRevision();
        long routing = config.getRevision();
        config.setCredentialId(config.getCredentialId());
        Thread.sleep(150);
        assertEquals(before, requests);
        assertEquals(policy, config.getMappingRevision());
        assertEquals(routing, config.getRevision());
        assertEquals(
                "Verified",
                config.getMappingStatus(config.getResponderMappings().get(0)));
    }

    @Test
    void lateAutomaticAndExplicitResultsCannotRestoreInvalidatedProof(JenkinsRule jenkins) throws Exception {
        var config = configure();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (boolean automatic : new boolean[] {false, true}) {
                config.setResponderMappings(List.of());
                connectionAvailable = true;
                config.doTestConnection("synthetic-bot", "#alerts");
                config.addMapping("Demo Engineer", "U12345678");
                String id = config.getResponderMappings().get(0).getId();
                memberEntered = new java.util.concurrent.CountDownLatch(1);
                memberRelease = new java.util.concurrent.CountDownLatch(1);
                var future = executor.submit(() -> {
                    try (var ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                        return config.validateMappingResult(id, automatic);
                    }
                });
                assertTrue(memberEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                connectionAvailable = false;
                config.doTestConnection("synthetic-bot", "#alerts");
                memberRelease.countDown();
                assertEquals(
                        MemberVerification.Status.NEEDS_VALIDATION,
                        future.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(
                        "NEEDS_VALIDATION", config.getResponderMappings().get(0).getVerificationStatus());
                assertNull(config.mappedSlackUser("Demo Engineer", workspace));
                memberEntered = null;
                memberRelease = null;
            }
        } finally {
            if (memberRelease != null) memberRelease.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @TestExtension
    public static final class VerificationTransport extends SlackTransport {
        @Override
        public String credentialFingerprint(String credential) {
            return fingerprint;
        }

        @Override
        public ConnectionResult checkConnection(String credential, String channel) {
            if (!connectionAvailable) return new ConnectionResult(false, "Unavailable", "", "", null);
            return new ConnectionResult(
                    true, "Ready", "Demo workspace", "#alerts", new SlackRoute(workspace, "C12345678"), 0, fingerprint);
        }

        @Override
        public MemberVerification verifyMember(String credential, String member) {
            requests++;
            var entered = memberEntered;
            var release = memberRelease;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return new MemberVerification(false, "", "", "", "");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new MemberVerification(false, "", "", "", "");
                }
            }
            if (!available) return new MemberVerification(false, "", "", "", "");
            return new MemberVerification(
                    available && !invalid,
                    member,
                    workspace,
                    staleProof ? "stale" : fingerprint,
                    "Demo Engineer Slack",
                    invalid
                            ? MemberVerification.Status.INVALID
                            : available
                                    ? MemberVerification.Status.VERIFIED
                                    : MemberVerification.Status.NEEDS_VALIDATION);
        }
    }
}

package io.jenkins.plugins.changeinvestigator.notification.identity;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleProject;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class StableIdentitiesTest {
    @Test
    void renameCopyRecreateAndReloadPreserveCorrectIdentities(JenkinsRule j) throws Exception {
        String controller = StableIdentities.controllerId();
        assertEquals(controller, StableIdentities.controllerId());
        var original = j.createFreeStyleProject("original");
        String id = StableIdentities.jobId(original);
        j.configRoundtrip(original);
        assertEquals(id, StableIdentities.jobId(original));
        original.renameTo("renamed");
        assertEquals(id, StableIdentities.jobId(original));
        var copy = (FreeStyleProject) j.jenkins.copy((hudson.model.TopLevelItem) original, "copy");
        assertNotEquals(id, StableIdentities.jobId(copy));
        var build = j.buildAndAssertSuccess(original);
        String runId = StableIdentities.runId(build);
        build.reload();
        assertEquals(runId, StableIdentities.runId(build));
        original.doReload();
        assertEquals(id, StableIdentities.jobId(original));
        original.delete();
        var recreated = j.createFreeStyleProject("renamed");
        assertNotEquals(id, StableIdentities.jobId(recreated));
    }

    @Test
    void restoredDeliveryApprovalNeedsThisBootAndAdministrator(JenkinsRule j) throws Exception {
        var controller = java.util.UUID.fromString(StableIdentities.controllerId());
        var boot = java.util.UUID.randomUUID();
        var guard = new ControllerDeliveryGuard(j.jenkins.getRootDir().toPath(), controller, boot);
        assertFalse(guard.canDispatch());
        guard.approve();
        assertTrue(guard.canDispatch());
        assertTrue(new ControllerDeliveryGuard(j.jenkins.getRootDir().toPath(), controller, boot).canDispatch());
        assertFalse(
                new ControllerDeliveryGuard(j.jenkins.getRootDir().toPath(), controller, java.util.UUID.randomUUID())
                        .canDispatch());
        assertFalse(ControllerDeliveryGuard.canDispatch(
                new ControllerDeliveryGuard.Approval(2, controller, boot), controller, boot));
        assertFalse(ControllerDeliveryGuard.canDispatch(
                new ControllerDeliveryGuard.Approval(1, controller, boot), java.util.UUID.randomUUID(), boot));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .to("reader"));
        try (var ignored = hudson.security.ACL.as2(
                hudson.model.User.getById("reader", true).impersonate2())) {
            assertThrows(org.springframework.security.access.AccessDeniedException.class, guard::approve);
        }
    }

    @Test
    void copiedUuidIsQuarantinedRatherThanCorrelated(JenkinsRule j) throws Exception {
        var original = j.createFreeStyleProject("one");
        StableIdentities.jobId(original);
        var second = j.createFreeStyleProject("two");
        var copied = (NotificationJobIdentity) hudson.model.Items.XSTREAM2.fromXML(
                hudson.model.Items.XSTREAM2.toXML(original.getProperty(NotificationJobIdentity.class)));
        second.addProperty(copied);
        assertThrows(IOException.class, () -> StableIdentities.jobId(second));
        assertTrue(original.getProperty(NotificationJobIdentity.class).isQuarantined());
        assertTrue(second.getProperty(NotificationJobIdentity.class).isQuarantined());
        second.delete();
        assertThrows(IOException.class, () -> StableIdentities.jobId(original));
    }
}

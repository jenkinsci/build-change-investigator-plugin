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

    @Test
    void jobMonitorHolderCanAllocateWhileAnotherAllocatorWaits(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("identity-lock-order");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "identity-contender");
            thread.setDaemon(true);
            return thread;
        });
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        java.util.concurrent.Future<String> result;
        String expected;
        try {
            synchronized (job) {
                result = executor.submit(() -> {
                    worker.set(Thread.currentThread());
                    return StableIdentities.jobId(job);
                });
                awaitBlockedWithoutClassMonitor(worker, job);
                // This is the same job -> identity order used by approved runtime activation.
                expected = StableIdentities.jobId(job);
            }
            assertEquals(expected, result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void runMonitorHolderCanAllocateWhileAnotherAllocatorWaits(JenkinsRule j) throws Exception {
        var run = j.buildAndAssertSuccess(j.createFreeStyleProject("run-identity-lock-order"));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "run-identity-contender");
            thread.setDaemon(true);
            return thread;
        });
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        java.util.concurrent.Future<String> result;
        String expected;
        try {
            synchronized (run) {
                result = executor.submit(() -> {
                    worker.set(Thread.currentThread());
                    return StableIdentities.runId(run);
                });
                awaitBlockedWithoutClassMonitor(worker, run);
                expected = StableIdentities.runId(run);
            }
            assertEquals(expected, result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitBlockedWithoutClassMonitor(
            java.util.concurrent.atomic.AtomicReference<Thread> worker, Object monitor) throws Exception {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread thread = worker.get();
            if (thread != null) {
                var info = bean.getThreadInfo(new long[] {thread.getId()}, true, true)[0];
                if (info != null
                        && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockInfo() != null
                        && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) {
                    // Fail before attempting the inverted acquisition, so the old implementation cannot hang teardown.
                    assertTrue(java.util.Arrays.stream(info.getLockedMonitors())
                            .noneMatch(
                                    m -> m.getIdentityHashCode() == System.identityHashCode(StableIdentities.class)));
                    return;
                }
            }
            Thread.sleep(10);
        }
        fail("Identity contender did not reach the expected job/run monitor");
    }

    @Test
    void concurrentDuplicateDetectionQuarantinesBothJobsWithoutCrossJobSaveLocks(JenkinsRule j) throws Exception {
        var first = j.createFreeStyleProject("duplicate-one");
        String id = StableIdentities.jobId(first);
        var second = j.createFreeStyleProject("duplicate-two");
        second.addProperty(new NotificationJobIdentity(id));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            var one = pool.submit(() -> {
                gate.await();
                assertThrows(IOException.class, () -> StableIdentities.jobId(first));
                return null;
            });
            var two = pool.submit(() -> {
                gate.await();
                assertThrows(IOException.class, () -> StableIdentities.jobId(second));
                return null;
            });
            gate.countDown();
            one.get(5, java.util.concurrent.TimeUnit.SECONDS);
            two.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(first.getProperty(NotificationJobIdentity.class).isQuarantined());
        assertTrue(second.getProperty(NotificationJobIdentity.class).isQuarantined());
        first.doReload();
        second.doReload();
        assertThrows(IOException.class, () -> StableIdentities.jobId(first));
        assertThrows(IOException.class, () -> StableIdentities.jobId(second));
    }

    @Test
    void duplicateInventoryIncludesJobsHiddenFromTheCallingReviewer(JenkinsRule j) throws Exception {
        var hidden = j.createFreeStyleProject("hidden-owner");
        String id = StableIdentities.jobId(hidden);
        var visible = j.createFreeStyleProject("visible-review");
        visible.addProperty(new NotificationJobIdentity(id));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .to("reviewer")
                .grant(hudson.model.Item.READ)
                .onItems(visible)
                .to("reviewer"));
        try (var context = hudson.security.ACL.as2(
                hudson.model.User.getById("reviewer", true).impersonate2())) {
            assertFalse(hidden.hasPermission(hudson.model.Item.READ));
            assertTrue(visible.hasPermission(hudson.model.Item.READ));
            var failure = assertThrows(IOException.class, () -> StableIdentities.jobId(visible));
            assertFalse(failure.getMessage().contains(hidden.getName()));
            assertEquals("reviewer", jenkins.model.Jenkins.getAuthentication2().getName());
        }
        assertTrue(hidden.getProperty(NotificationJobIdentity.class).isQuarantined());
        assertTrue(visible.getProperty(NotificationJobIdentity.class).isQuarantined());
    }
}

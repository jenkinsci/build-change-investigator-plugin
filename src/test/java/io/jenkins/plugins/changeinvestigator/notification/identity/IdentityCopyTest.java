package io.jenkins.plugins.changeinvestigator.notification.identity;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleProject;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class IdentityCopyTest {
    @Test
    void nativeCopyPublicationDoesNotPermanentlyQuarantineItsSource(JenkinsRule j) throws Exception {
        var original = j.createFreeStyleProject("copy-race-original");
        String id = StableIdentities.jobId(original);
        var listener = hudson.ExtensionList.lookupSingleton(BlockingCopyListener.class);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var copying = executor.submit(() -> {
                try (var ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                    return (FreeStyleProject) j.jenkins.copy((hudson.model.TopLevelItem) original, "copy-race-child");
                }
            });
            assertTrue(listener.published.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertSame(listener.copy, j.jenkins.getItemByFullName("copy-race-child"));
            assertEquals(
                    id, listener.copy.getProperty(NotificationJobIdentity.class).getId());
            assertThrows(IOException.class, () -> StableIdentities.jobId(original));
            assertThrows(IOException.class, () -> StableIdentities.jobId(listener.copy));
            listener.resume.countDown();
            var copy = copying.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(id, StableIdentities.jobId(original));
            assertNotEquals(id, StableIdentities.jobId(copy));
            assertFalse(new java.io.File(original.getRootDir(), "bci-notification-job-quarantined").exists());
            original.doReload();
            copy.doReload();
            assertEquals(id, StableIdentities.jobId(original));
            assertNotEquals(id, StableIdentities.jobId(copy));
        } finally {
            listener.resume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void provisionalXmlImportNeverAllocatesEvenAfterSourceIsRemoved(JenkinsRule j) throws Exception {
        var original = j.createFreeStyleProject("xml-source");
        String id = StableIdentities.jobId(original);
        var xml = hudson.model.Items.getConfigFile(original).asString();
        var imported = (FreeStyleProject) j.jenkins.createProjectFromXML(
                "xml-import", new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(id, imported.getProperty(NotificationJobIdentity.class).getId());
        assertThrows(IOException.class, () -> StableIdentities.jobId(original));
        assertThrows(IOException.class, () -> StableIdentities.jobId(imported));
        assertFalse(new java.io.File(original.getRootDir(), "bci-notification-job-quarantined").exists());
        original.delete();
        assertThrows(IOException.class, () -> StableIdentities.jobId(imported));
        assertFalse(new java.io.File(imported.getRootDir(), "bci-notification-job-id").exists());
    }

    @Test
    void authoritativeDuplicateMarkersStillQuarantineAnXmlLoadedProperty(JenkinsRule j) throws Exception {
        var original = j.createFreeStyleProject("persisted-source");
        String id = StableIdentities.jobId(original);
        var xml = hudson.model.Items.getConfigFile(original).asString();
        var imported = (FreeStyleProject) j.jenkins.createProjectFromXML(
                "persisted-import",
                new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        java.nio.file.Files.writeString(imported.getRootDir().toPath().resolve("bci-notification-job-id"), id);
        assertThrows(IOException.class, () -> StableIdentities.jobId(original));
        assertThrows(IOException.class, () -> StableIdentities.jobId(imported));
        assertTrue(new java.io.File(original.getRootDir(), "bci-notification-job-quarantined").isFile());
        assertTrue(new java.io.File(imported.getRootDir(), "bci-notification-job-quarantined").isFile());
    }

    @Test
    void startupLoadedMarkerlessDuplicateRetainsLegacyQuarantine(JenkinsRule j) throws Exception {
        var original = j.createFreeStyleProject("reload-source");
        StableIdentities.jobId(original);
        var xml = hudson.model.Items.getConfigFile(original).asString();
        var imported = (FreeStyleProject) j.jenkins.createProjectFromXML(
                "reload-import",
                new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> StableIdentities.jobId(imported));
        imported.doReload();
        assertThrows(IOException.class, () -> StableIdentities.jobId(imported));
        // Item reload is itself an XML update; a controller reload performs ordinary item loading.
        assertTrue(imported.getProperty(NotificationJobIdentity.class).isProvisionalXmlLoad());
        assertFalse(new java.io.File(original.getRootDir(), "bci-notification-job-quarantined").exists());
        j.jenkins.reload();
        var loadedOriginal = j.jenkins.getItemByFullName("reload-source", FreeStyleProject.class);
        var loadedImport = j.jenkins.getItemByFullName("reload-import", FreeStyleProject.class);
        assertFalse(loadedImport.getProperty(NotificationJobIdentity.class).isProvisionalXmlLoad());
        assertThrows(IOException.class, () -> StableIdentities.jobId(loadedImport));
        assertThrows(IOException.class, () -> StableIdentities.jobId(loadedOriginal));
        assertTrue(new java.io.File(original.getRootDir(), "bci-notification-job-quarantined").isFile());
        assertTrue(new java.io.File(imported.getRootDir(), "bci-notification-job-quarantined").isFile());
    }

    @Test
    void normalLegacyMarkerlessIdentityStillMigrates(JenkinsRule j) throws Exception {
        var job = j.createFreeStyleProject("legacy-identity");
        String id = java.util.UUID.randomUUID().toString();
        job.addProperty(new NotificationJobIdentity(id));
        j.jenkins.reload();
        job = j.jenkins.getItemByFullName("legacy-identity", FreeStyleProject.class);
        assertFalse(job.getProperty(NotificationJobIdentity.class).isProvisionalXmlLoad());
        assertFalse(new java.io.File(job.getRootDir(), "bci-notification-job-id").exists());
        assertEquals(id, StableIdentities.jobId(job));
        assertEquals(
                id,
                java.nio.file.Files.readString(job.getRootDir().toPath().resolve("bci-notification-job-id"))
                        .trim());
    }

    @org.jvnet.hudson.test.TestExtension(value = "nativeCopyPublicationDoesNotPermanentlyQuarantineItsSource")
    public static final class BlockingCopyListener extends hudson.model.listeners.ItemListener {
        final java.util.concurrent.CountDownLatch published = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        volatile FreeStyleProject copy;

        @Override
        public void onCopied(hudson.model.Item source, hudson.model.Item item) {
            copy = (FreeStyleProject) item;
            published.countDown();
            try {
                if (!resume.await(20, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Copy regression did not release the listener");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}

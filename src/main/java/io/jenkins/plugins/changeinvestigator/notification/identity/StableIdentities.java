package io.jenkins.plugins.changeinvestigator.notification.identity;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.listeners.ItemListener;
import hudson.util.TextFile;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import jenkins.model.Jenkins;

/** Stable local identities. Authoritative item IDs survive replacement of job configuration properties. */
public final class StableIdentities {
    private StableIdentities() {}

    public static synchronized String controllerId() throws IOException {
        TextFile file = new TextFile(new File(Jenkins.get().getRootDir(), "bci-notification-controller-id"));
        if (!file.exists()) file.write(UUID.randomUUID().toString());
        return valid(file.readTrim());
    }

    private static String valid(String value) throws IOException {
        try {
            if (!UUID.fromString(value).toString().equals(value))
                throw new IllegalArgumentException("Noncanonical identity");
            return value;
        } catch (RuntimeException e) {
            throw new IOException("Invalid notification identity", e);
        }
    }

    private static TextFile identityFile(Job<?, ?> job) {
        return new TextFile(new File(job.getRootDir(), "bci-notification-job-id"));
    }

    private static TextFile quarantineFile(Job<?, ?> job) {
        return new TextFile(new File(job.getRootDir(), "bci-notification-job-quarantined"));
    }

    private static String existingId(Job<?, ?> job) throws IOException {
        TextFile file = identityFile(job);
        if (file.exists()) return valid(file.readTrim());
        NotificationJobIdentity property = job.getProperty(NotificationJobIdentity.class);
        return property == null ? null : valid(property.getId());
    }

    private static void quarantine(Job<?, ?> job) throws IOException {
        quarantineFile(job).write("true");
        NotificationJobIdentity property = job.getProperty(NotificationJobIdentity.class);
        if (property != null) {
            property.quarantine();
            job.save();
        }
    }

    public static synchronized String jobId(Job<?, ?> job) throws IOException {
        if (quarantineFile(job).exists()) throw new IOException("Notification job identity quarantined");
        NotificationJobIdentity property = job.getProperty(NotificationJobIdentity.class);
        String id = existingId(job);
        if (id == null) id = UUID.randomUUID().toString();
        if (property != null && (!id.equals(property.getId()) || property.isQuarantined())) {
            quarantine(job);
            throw new IOException("Conflicting notification job identity quarantined");
        }
        for (Job<?, ?> other : Jenkins.get().getAllItems(Job.class)) {
            if (other != job && id.equals(existingId(other))) {
                quarantine(job);
                quarantine(other);
                throw new IOException("Duplicate notification job identity quarantined");
            }
        }
        if (!identityFile(job).exists()) identityFile(job).write(id);
        if (property == null) {
            job.addProperty(new NotificationJobIdentity(id));
            job.save();
        }
        return id;
    }

    public static synchronized String runId(Run<?, ?> run) throws IOException {
        NotificationRunIdentity identity = run.getAction(NotificationRunIdentity.class);
        if (identity == null) {
            identity = new NotificationRunIdentity();
            run.addAction(identity);
            run.save();
        }
        return valid(identity.getId());
    }

    @Extension
    public static final class CopyListener extends ItemListener {
        @Override
        public void onCopied(Item source, Item item) {
            if (item instanceof Job<?, ?> job) {
                try {
                    if (existingId(job) == null
                            && !(source instanceof Job<?, ?> original && existingId(original) != null)) return;
                    String fresh = UUID.randomUUID().toString();
                    identityFile(job).write(fresh);
                    java.nio.file.Files.deleteIfExists(
                            new File(job.getRootDir(), "bci-notification-job-quarantined").toPath());
                    job.removeProperty(NotificationJobIdentity.class);
                    job.addProperty(new NotificationJobIdentity(fresh));
                    job.save();
                } catch (IOException e) {
                    java.util.logging.Logger.getLogger(StableIdentities.class.getName())
                            .warning("Notification copy identity allocation failed");
                }
            }
        }
    }
}

package io.jenkins.plugins.changeinvestigator.notification.feedback;

import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.NotificationInvestigationRecord;
import io.jenkins.plugins.changeinvestigator.notification.identity.NotificationJobIdentity;
import io.jenkins.plugins.changeinvestigator.notification.identity.NotificationRunIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import jenkins.model.Jenkins;

/** Side-effect-free access to existing identities and persisted case evidence. */
public final class FeedbackAccess {
    private FeedbackAccess() {}

    public static Optional<UUID> existingJobId(Job<?, ?> job) throws IOException {
        Path root = job.getRootDir().toPath();
        Path marker = root.resolve("bci-notification-job-id");
        if (Files.exists(root.resolve("bci-notification-job-quarantined"), LinkOption.NOFOLLOW_LINKS))
            return Optional.empty();
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 40)
            throw new IOException("Invalid existing job identity");
        String value = Files.readString(marker).trim();
        UUID id;
        try {
            id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid existing job identity");
        }
        NotificationJobIdentity property = job.getProperty(NotificationJobIdentity.class);
        if (property == null || property.isQuarantined() || !value.equals(property.getId())) return Optional.empty();
        return Optional.of(id);
    }
    /** Re-resolves the persisted identity and rejects duplicate, removed or replaced item incarnations. */
    public static boolean authoritative(Job<?, ?> boundJob, UUID id) throws IOException {
        if (id == null || !existingJobId(boundJob).filter(id::equals).isPresent()) return false;
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            if (Jenkins.get().getItemByFullName(boundJob.getFullName(), Job.class) != boundJob) return false;
            Job<?, ?> matched = null;
            for (Job<?, ?> candidate : Jenkins.get().getAllItems(Job.class)) {
                if (existingJobId(candidate).filter(id::equals).isPresent()) {
                    if (matched != null) return false;
                    matched = candidate;
                }
            }
            return matched == boundJob;
        }
    }

    public static Optional<NotificationInvestigationRecord> read(Job<?, ?> job, UUID caseId) throws IOException {
        job.checkPermission(Item.READ);
        var id = existingJobId(job);
        if (id.isEmpty() || !authoritative(job, id.get())) return Optional.empty();
        return NotificationEngine.peek(job.getRootDir().toPath(), id.get(), caseId);
    }

    public static Optional<NotificationInvestigationRecord> forRun(Run<?, ?> run) throws IOException {
        Job<?, ?> job = run.getParent();
        job.checkPermission(Item.READ);
        var id = existingJobId(job);
        NotificationRunIdentity runIdentity = run.getAction(NotificationRunIdentity.class);
        if (id.isEmpty() || runIdentity == null || !authoritative(job, id.get())) return Optional.empty();
        Path root = job.getRootDir().toPath().resolve("bci-notifications");
        Path cases = root.resolve("cases");
        if (!Files.exists(cases, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(cases, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid case directory");
        NotificationInvestigationRecord found = null;
        int count = 0;
        try (var paths = Files.newDirectoryStream(cases, "*.json")) {
            for (Path path : paths) {
                if (++count > 100) throw new IOException("Case read bound reached");
                Path fileName = path.getFileName();
                if (fileName == null) throw new IOException("Invalid case identity");
                String name = fileName.toString();
                UUID caseId;
                try {
                    caseId = UUID.fromString(name.substring(0, name.length() - 5));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid case identity");
                }
                var saved = NotificationEngine.peek(job.getRootDir().toPath(), id.get(), caseId);
                if (saved.isEmpty()) continue;
                var record = saved.get();
                boolean matches = record.events().stream()
                        .anyMatch(e -> runIdentity
                                        .getId()
                                        .equals(e.snapshot()
                                                .path("build")
                                                .path("runId")
                                                .asText())
                                || runIdentity
                                        .getId()
                                        .equals(e.snapshot()
                                                .path("recovery")
                                                .path("build")
                                                .path("runId")
                                                .asText()));
                if (matches) {
                    if (found != null) throw new IOException("Ambiguous case identity");
                    found = record;
                }
            }
        }
        return Optional.ofNullable(found);
    }
}

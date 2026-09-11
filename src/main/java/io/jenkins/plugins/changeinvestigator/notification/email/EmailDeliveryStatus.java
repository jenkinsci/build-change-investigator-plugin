package io.jenkins.plugins.changeinvestigator.notification.email;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.RootAction;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerProxy;

/** Read-only, administrator-only operational status; no recipient details or raw provider errors. */
@Extension
public final class EmailDeliveryStatus implements RootAction, StaplerProxy {
    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    @Override
    public String getIconFileName() {
        return Jenkins.get().hasPermission(Jenkins.ADMINISTER) ? "symbol-notifications plugin-ionicons-api" : null;
    }

    @Override
    public String getDisplayName() {
        return "BCI Email delivery";
    }

    @Override
    public String getUrlName() {
        return "bci-email-delivery";
    }

    public String getControllerStatus() {
        return io.jenkins.plugins.changeinvestigator.notification.email.config.EmailConfiguration.get()
                .getDeliveryStatus();
    }

    public List<Row> getRows() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        List<Row> rows = new ArrayList<>();
        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (rows.size() >= 100) break;
            if (!java.nio.file.Files.isRegularFile(
                    job.getRootDir().toPath().resolve("bci-email-active"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                continue;
            try {
                var engine = new NotificationEngine(
                        job.getRootDir().toPath(),
                        UUID.fromString(StableIdentities.jobId(job)),
                        Jenkins.get().getRootDir().toPath(),
                        UUID.fromString(StableIdentities.controllerId()));
                for (var record : engine.records())
                    for (var state : record.destinations()) {
                        if (!"EMAIL".equals(state.transport())) continue;
                        if (rows.size() >= 100) break;
                        rows.add(new Row(
                                job.getFullName(),
                                record.investigationId().toString(),
                                message(EmailOutbox.status(state))));
                    }
            } catch (java.io.IOException | RuntimeException | LinkageError e) {
                rows.add(new Row(
                        job.getFullName(),
                        "Unavailable",
                        "Delivery state requires administrator review; no raw error is displayed."));
            }
        }
        return List.copyOf(rows);
    }

    static String message(String code) {
        return switch (code) {
            case "UNKNOWN_OUTCOME" ->
                "Delivery outcome uncertain. Manual reconciliation required. Dependent replies are paused.";
            case "THREAD_UNAVAILABLE" ->
                "Email conversation unavailable. Updates are paused; no replacement is sent automatically.";
            case "SENT" -> "SMTP accepted; receipt retained. Inbox delivery is not guaranteed.";
            case "RETRY_WAIT" -> "Waiting for a bounded retry after a definite rejection.";
            case "FAILED_PERMANENT" -> "Delivery stopped safely. Review the approved destination and credential.";
            case "CANCELLED", "SUPPRESSED" -> "Delivery cancelled or suppressed by current policy.";
            case "LEASED" -> "Attempt in progress; lease persisted.";
            default -> "Queued or waiting for policy eligibility.";
        };
    }

    public record Row(String job, String investigation, String status) {
        public String getJob() {
            return job;
        }

        public String getInvestigation() {
            return investigation;
        }

        public String getStatus() {
            return status;
        }
    }
}

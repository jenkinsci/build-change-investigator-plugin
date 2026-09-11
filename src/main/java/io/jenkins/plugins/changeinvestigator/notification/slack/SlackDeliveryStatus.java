package io.jenkins.plugins.changeinvestigator.notification.slack;

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
public final class SlackDeliveryStatus implements RootAction, StaplerProxy {
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
        return "BCI Slack delivery";
    }

    @Override
    public String getUrlName() {
        return "bci-slack-delivery";
    }

    public String getControllerStatus() {
        return io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackConfiguration.get()
                .getDeliveryStatus();
    }

    public List<Row> getRows() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        List<Row> rows = new ArrayList<>();
        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (rows.size() >= 100) break;
            if (!java.nio.file.Files.isRegularFile(
                    job.getRootDir().toPath().resolve("bci-slack-active"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                continue;
            try {
                var engine = new NotificationEngine(
                        job.getRootDir().toPath(),
                        UUID.fromString(StableIdentities.jobId(job)),
                        Jenkins.get().getRootDir().toPath(),
                        UUID.fromString(StableIdentities.controllerId()));
                for (var record : engine.records())
                    for (var state : record.destinations()) {
                        if (rows.size() >= 100) break;
                        rows.add(new Row(
                                job.getFullName(),
                                record.investigationId().toString(),
                                message(SlackOutbox.status(state))));
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
                "Thread unavailable. Replies are paused; no replacement thread will be created automatically.";
            case "SENT" -> "Sent; receipt retained.";
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

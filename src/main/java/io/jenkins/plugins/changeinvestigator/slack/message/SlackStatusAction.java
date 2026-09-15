package io.jenkins.plugins.changeinvestigator.slack.message;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;

/** Read-only delivery summary; viewing it never schedules work or contacts Slack. */
public final class SlackStatusAction implements Action {
    private final Job<?, ?> job;

    public SlackStatusAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        job.checkPermission(Item.READ);
        return job;
    }

    @Override
    public String getIconFileName() {
        return "symbol-chatbubble-ellipses-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Slack notifications";
    }

    @Override
    public String getUrlName() {
        return "bci-slack";
    }

    public String getStatus() {
        job.checkPermission(Item.READ);
        String status = io.jenkins.plugins.changeinvestigator.slack.lifecycle.SlackRuntime.safeStatus(job);
        return status.isBlank() ? "No investigation notification yet." : status;
    }

    @Extension
    public static final class Factory extends TransientActionFactory<Job> {
        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Collection<? extends Action> createFor(Job target) {
            var property = (SlackJobProperty) target.getProperty(SlackJobProperty.class);
            return property != null && property.isEnabled() ? List.of(new SlackStatusAction(target)) : List.of();
        }
    }
}

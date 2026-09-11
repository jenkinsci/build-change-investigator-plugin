package io.jenkins.plugins.changeinvestigator.notification.email.config;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest2;

/** Job owners select approved destination IDs, never external routing or credentials. */
public final class EmailJobProperty extends JobProperty<Job<?, ?>> {
    public enum Mode {
        INHERIT,
        OFF,
        CUSTOM
    }

    private final Mode mode;
    private final String destinationIds;
    private final boolean recovery;
    private final boolean aiOnly;

    public EmailJobProperty(String mode, String destinationIds, boolean recovery, boolean aiOnly) {
        this.mode = mode == null || mode.isBlank() ? Mode.INHERIT : Mode.valueOf(mode);
        this.destinationIds = destinationIds == null ? "" : destinationIds.trim();
        this.recovery = recovery;
        this.aiOnly = aiOnly;

        selectedIds();
    }

    @DataBoundConstructor
    public EmailJobProperty(String mode, List<String> destinationIds, boolean recovery, boolean aiOnly) {
        this(mode, destinationIds == null ? "" : String.join(",", destinationIds), recovery, aiOnly);
    }

    public boolean isSelected(String id) {
        return selectedIds().contains(UUID.fromString(id));
    }

    public List<UUID> selectedIds() {
        if (destinationIds.isBlank()) return List.of();
        if (destinationIds.length() > 369)
            throw new IllegalArgumentException("At most ten destinations may be selected");
        List<UUID> ids = Arrays.stream(destinationIds.split("[,\\s]+"))
                .map(UUID::fromString)
                .distinct()
                .toList();
        if (ids.size() > 10) throw new IllegalArgumentException("At most ten destinations may be selected");
        return ids;
    }

    public Mode getMode() {
        return mode;
    }

    public String getDestinationIds() {
        return destinationIds;
    }

    public boolean isRecovery() {
        return recovery;
    }

    public boolean isAiOnly() {
        return aiOnly;
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Build Change Investigator Email policy";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> type) {
            return true;
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest2 request, JSONObject form) throws FormException {
            if (request == null) throw new FormException("Job context required", "destinationIds");
            Job<?, ?> job = request.findAncestorObject(Job.class);
            if (job == null) throw new FormException("Job context required", "destinationIds");
            job.checkPermission(Item.CONFIGURE);
            try {
                EmailJobProperty property = request.bindJSON(EmailJobProperty.class, form);
                if (property.mode == Mode.CUSTOM) {
                    for (UUID id : property.selectedIds()) {
                        if (EmailConfiguration.get().approved(job, id).isEmpty())
                            throw new IllegalArgumentException("Destination is not approved for this job");
                    }
                }
                return property;
            } catch (IllegalArgumentException e) {
                throw new FormException("Select only destinations approved for this job", "destinationIds");
            }
        }

        public hudson.util.ListBoxModel doFillModeItems() {
            var values = new hudson.util.ListBoxModel();
            values.add("Inherit approved scope defaults", "INHERIT");
            values.add("Off", "OFF");
            values.add("Custom", "CUSTOM");
            return values;
        }

        public List<EmailDestination> approvedFor(Job<?, ?> job) {
            if (job == null || !job.hasPermission(Item.CONFIGURE)) return List.of();
            return EmailConfiguration.get().approvedFor(job);
        }
    }
}

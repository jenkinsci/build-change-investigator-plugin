package io.jenkins.plugins.changeinvestigator.notification.identity;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import java.util.UUID;

/** Opaque item identity; copying a job must allocate a different identity. */
public final class NotificationJobIdentity extends JobProperty<Job<?, ?>> {
    private String id;
    private boolean quarantined;
    private transient boolean provisionalXmlLoad;

    public NotificationJobIdentity() {
        this(UUID.randomUUID().toString());
    }

    NotificationJobIdentity(String id) {
        UUID.fromString(id);
        this.id = id;
    }

    @Override
    protected void setOwner(Job<?, ?> owner) {
        super.setOwner(owner);
        // XML loading is provisional, not proof that Jenkins will complete a native copy.
        provisionalXmlLoad = hudson.model.Items.currentlyUpdatingByXml();
    }

    boolean isProvisionalXmlLoad() {
        return provisionalXmlLoad;
    }

    public String getId() {
        return id;
    }

    public boolean isQuarantined() {
        return quarantined;
    }

    void quarantine() {
        quarantined = true;
    }

    @Override
    public NotificationJobIdentity reconfigure(
            org.kohsuke.stapler.StaplerRequest2 request, net.sf.json.JSONObject form) {
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Notification job identity";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> type) {
            return false;
        }
    }
}

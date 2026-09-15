package io.jenkins.plugins.changeinvestigator.slack.config;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Run;
import hudson.model.listeners.ItemListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.io.IOException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Explicit per-job opt-in, scoped to builds scheduled after enabling notifications. */
public final class SlackJobProperty extends JobProperty<Job<?, ?>> {
    private boolean enabled;
    private final boolean useChannelOverride;
    private final String channel;
    private int enabledAfterBuild = -1;
    private long revision = 1;
    private long enabledAtMillis;
    private long modifiedAtMillis = System.currentTimeMillis();

    @DataBoundConstructor
    public SlackJobProperty(boolean enabled, boolean useChannelOverride, String channel) {
        this.enabled = enabled;
        this.enabledAtMillis = enabled ? System.currentTimeMillis() : 0;
        this.useChannelOverride = useChannelOverride;
        this.channel = ResponderMapping.bounded(channel, 100);
    }

    @Override
    protected void setOwner(Job<?, ?> job) {
        super.setOwner(job);
        if (enabled && enabledAfterBuild < 0) enabledAfterBuild = job.getNextBuildNumber() - 1;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isUseChannelOverride() {
        return useChannelOverride;
    }

    public String getChannel() {
        return channel == null ? "" : channel;
    }

    public int getEnabledAfterBuild() {
        return enabledAfterBuild;
    }

    public long getRevision() {
        return revision;
    }

    public long getEnabledAtMillis() {
        return enabledAtMillis;
    }

    public long getModifiedAtMillis() {
        return modifiedAtMillis;
    }

    public static boolean eligible(Run<?, ?> run) {
        SlackJobProperty property = run.getParent().getProperty(SlackJobProperty.class);
        return property != null
                && property.enabled
                && property.enabledAfterBuild >= 0
                && run.getNumber() > property.enabledAfterBuild
                && run.getStartTimeInMillis() >= property.enabledAtMillis;
    }

    public static String effectiveChannel(Job<?, ?> job) {
        SlackJobProperty property = job.getProperty(SlackJobProperty.class);
        return property != null && property.useChannelOverride
                ? property.getChannel()
                : SlackConfiguration.get().getDefaultChannel();
    }

    @Override
    public SlackJobProperty reconfigure(StaplerRequest2 request, JSONObject form) throws FormException {
        owner.checkPermission(Item.CONFIGURE);
        if (form == null || form.isNullObject()) return null;
        try {
            SlackJobProperty replacement = request.bindJSON(SlackJobProperty.class, form);
            replacement.enabledAfterBuild =
                    replacement.enabled ? (enabled ? enabledAfterBuild : owner.getNextBuildNumber() - 1) : -1;
            replacement.enabledAtMillis =
                    replacement.enabled && enabled ? enabledAtMillis : replacement.enabledAtMillis;
            replacement.revision = revision
                    + (replacement.enabled != enabled
                                    || replacement.useChannelOverride != useChannelOverride
                                    || !replacement.getChannel().equals(getChannel())
                            ? 1
                            : 0);
            replacement.modifiedAtMillis =
                    replacement.revision == revision ? modifiedAtMillis : System.currentTimeMillis();
            return replacement;
        } catch (IllegalArgumentException failure) {
            throw new FormException("Could not save Slack notification settings. Check the supplied values.", null);
        }
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Build Change Investigator Notifications";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> type) {
            return true;
        }

        public boolean isSlackConfigured() {
            return SlackConfiguration.get().isConfigured();
        }

        public String getGlobalChannel() {
            return SlackConfiguration.get().getDefaultChannel();
        }

        @RequirePOST
        public FormValidation doCheckChannel(
                @org.kohsuke.stapler.AncestorInPath Job<?, ?> job, @QueryParameter String value) {
            if (job == null) return FormValidation.ok();
            job.checkPermission(Item.CONFIGURE);
            return value == null || value.isBlank() || SlackTransport.isValidChannel(value)
                    ? FormValidation.ok()
                    : FormValidation.error("Enter a Slack channel name or channel ID.");
        }
    }

    @Extension
    public static final class CopyListener extends ItemListener {
        @Override
        public void onCopied(Item source, Item item) {
            if (item instanceof Job<?, ?> job) {
                SlackJobProperty property = job.getProperty(SlackJobProperty.class);
                if (property != null) {
                    property.enabled = false;
                    property.enabledAfterBuild = -1;
                    property.enabledAtMillis = 0;
                    property.modifiedAtMillis = System.currentTimeMillis();
                    property.revision++;
                    try {
                        job.save();
                    } catch (IOException failure) {
                        java.util.logging.Logger.getLogger(CopyListener.class.getName())
                                .warning("Could not persist disabled Slack notifications for a copied job.");
                    }
                }
            }
        }
    }
}

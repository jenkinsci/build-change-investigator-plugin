package io.jenkins.plugins.changeinvestigator.notification.slack.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.Extension;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime;
import io.jenkins.plugins.changeinvestigator.notification.slack.SlackTransport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Administrator approval is required before investigation summaries leave Jenkins. */
@Extension
public class SlackConfiguration extends GlobalConfiguration {
    private List<SlackDestination> destinations = List.of();
    private boolean enabled;
    private java.util.Map<String, Long> generationHistory = new java.util.HashMap<>();

    public SlackConfiguration() {
        load();
    }

    public static SlackConfiguration get() {
        return GlobalConfiguration.all().get(SlackConfiguration.class);
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    /** Turning delivery off and on never revives an older disclosure approval. */
    public synchronized void setEnabled(boolean value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (enabled == value) return;
        List<SlackDestination> replacement = getDestinations().stream()
                .map(SlackDestination::nextDisclosureGeneration)
                .toList();
        if (generationHistory == null) generationHistory = new java.util.HashMap<>();
        for (SlackDestination destination : replacement)
            generationHistory.put(destination.getId(), destination.getGeneration());
        destinations = replacement;
        enabled = value;
        save();
        io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher.configurationChanged();
    }

    public synchronized List<SlackDestination> getDestinations() {
        return destinations == null ? List.of() : List.copyOf(destinations);
    }

    /** Replaces the approved list atomically. Generations and verification are never request-bound. */
    public synchronized void replaceDestinations(List<SlackDestination> requested) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (requested == null || requested.size() > 100)
            throw new IllegalArgumentException("At most 100 Slack destinations are allowed");
        List<SlackDestination> replacement = new ArrayList<>();
        java.util.Map<String, Long> history =
                generationHistory == null ? new java.util.HashMap<>() : new java.util.HashMap<>(generationHistory);
        HashSet<UUID> ids = new HashSet<>();
        for (SlackDestination value : requested) {
            if (value == null) throw new IllegalArgumentException("Invalid Slack destination entry");
            if (!ids.add(value.identity())) throw new IllegalArgumentException("Duplicate Slack destination ID");
            SlackDestination old = getDestinations().stream()
                    .filter(d -> d.identity().equals(value.identity()))
                    .findFirst()
                    .orElse(null);
            SlackDestination next = old == null && history.containsKey(value.getId())
                    ? value.afterRetiredGeneration(history.get(value.getId()))
                    : value.reconciled(old);
            replacement.add(next);
            history.put(next.getId(), next.getGeneration());
        }
        if (history.size() > 1000)
            throw new IllegalArgumentException("Slack destination identity history bound reached");
        destinations = List.copyOf(replacement);
        generationHistory = history;
        save();
        io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher.configurationChanged();
    }

    @Override
    public boolean configure(StaplerRequest2 request, JSONObject form) throws FormException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            List<SlackDestination> requested = request.bindJSONToList(SlackDestination.class, form.get("destinations"));
            synchronized (this) {
                replaceDestinations(requested);
                setEnabled(form.optBoolean("enabled", false));
            }
            return true;
        } catch (IllegalArgumentException e) {
            throw new FormException("Invalid Slack destination configuration", "destinations");
        }
    }

    public List<SlackDestination> approvedFor(Job<?, ?> job) {
        return getDestinations().stream()
                .filter(d -> d.isEnabled() && d.isVerified() && d.allows(job.getFullName()))
                .toList();
    }

    public Optional<SlackDestination> approved(Job<?, ?> job, UUID id) {
        return approvedFor(job).stream().filter(d -> d.identity().equals(id)).findFirst();
    }

    public EffectivePolicy policy(Job<?, ?> job) {
        if (!isEnabled()) return new EffectivePolicy(List.of(), true, false, false);
        SlackJobProperty configured = job.getProperty(SlackJobProperty.class);
        if (configured != null && configured.getMode() == SlackJobProperty.Mode.OFF)
            return new EffectivePolicy(List.of(), true, false, false);
        List<UUID> allowed =
                approvedFor(job).stream().map(SlackDestination::identity).toList();
        if (configured == null || configured.getMode() == SlackJobProperty.Mode.INHERIT)
            return new EffectivePolicy(allowed.stream().limit(10).toList(), true, false, false);
        return new EffectivePolicy(
                configured.selectedIds().stream().filter(allowed::contains).toList(),
                configured.isRecovery(),
                configured.isAiOnly(),
                configured.isMentions());
    }

    public List<SlackDestination> resolvedDestinations(Job<?, ?> job) {
        List<UUID> ids = policy(job).destinationIds();
        return approvedFor(job).stream().filter(d -> ids.contains(d.identity())).toList();
    }

    public Optional<SlackDestination> authorize(Job<?, ?> job, UUID id, long generation) {
        return resolvedDestinations(job).stream()
                .filter(d -> d.identity().equals(id) && d.getGeneration() == generation)
                .findFirst();
    }

    /** Resolves at attempt time in the current job context. Never caches or serializes a secret. */
    public Optional<Secret> resolveToken(Job<?, ?> job, SlackDestination destination) {
        return authorize(job, destination.identity(), destination.getGeneration())
                .flatMap(current -> credential(job, current.getCredentialId()));
    }

    private Optional<Secret> credential(Job<?, ?> job, String id) {
        try {
            List<StringCredentials> values = job == null
                    ? CredentialsProvider.lookupCredentialsInItemGroup(
                            StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of())
                    : CredentialsProvider.lookupCredentialsInItem(StringCredentials.class, job, ACL.SYSTEM2, List.of());
            StringCredentials credential = CredentialsMatchers.firstOrNull(values, CredentialsMatchers.withId(id));
            return credential == null ? Optional.empty() : Optional.of(credential.getSecret());
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private Optional<Secret> credentialForScope(SlackDestination destination) {
        try {
            String path = destination.getScope().endsWith("/**")
                    ? destination.getScope().substring(0, destination.getScope().length() - 3)
                    : destination.getScope();
            hudson.model.Item item = Jenkins.get().getItemByFullName(path);
            if (item instanceof Job<?, ?> job) return credential(job, destination.getCredentialId());
            if (item instanceof hudson.model.ItemGroup<?> folder) {
                StringCredentials value = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentialsInItemGroup(
                                StringCredentials.class, folder, ACL.SYSTEM2, List.of()),
                        CredentialsMatchers.withId(destination.getCredentialId()));
                return value == null ? Optional.empty() : Optional.of(value.getSecret());
            }
            return credential(null, destination.getCredentialId());
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** Authenticates and checks channel membership without posting investigation or test data. */
    @RequirePOST
    public FormValidation doTestConnection(@QueryParameter String destinationId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            UUID id = UUID.fromString(destinationId);
            SlackDestination target = getDestinations().stream()
                    .filter(d -> d.identity().equals(id))
                    .findFirst()
                    .orElseThrow();
            Optional<Secret> secret = credentialForScope(target);
            if (secret.isEmpty())
                return FormValidation.error(
                        "Slack credential is unavailable. Use an administrator-managed secret text credential.");
            var result = checkConnection(secret.get().getPlainText(), target.getChannelId());
            if (!result.usable()
                    || !target.getWorkspaceId().equals(result.workspaceId())
                    || !target.getChannelId().equals(result.channelId()))
                return FormValidation.error(
                        "Slack authentication or approved workspace/channel validation failed. Check the bot credential and channel membership.");
            synchronized (this) {
                List<SlackDestination> replacement = new ArrayList<>(getDestinations());
                int index = replacement.indexOf(target);
                if (index < 0)
                    return FormValidation.error("Slack configuration changed. Test the saved destination again.");
                replacement.set(index, target.verifiedCopy());
                destinations = List.copyOf(replacement);
                save();
            }
            io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher.configurationChanged();
            return FormValidation.ok("Slack workspace and channel validated without posting a message.");
        } catch (RuntimeException | LinkageError e) {
            return FormValidation.error(
                    "Slack connection could not be validated. Check the saved configuration and try again.");
        }
    }

    protected SlackTransport.ConnectionResult checkConnection(String token, String channelId) {
        return new SlackTransport().testConnection(token, channelId);
    }

    @RequirePOST
    public FormValidation doRearmDelivery() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            Jenkins.get()
                    .getExtensionList(NotificationRuntime.class)
                    .get(0)
                    .deliveryGuard()
                    .approve();
            io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher.configurationChanged();
            return FormValidation.ok(
                    "Slack delivery approved for this controller boot. Queued work will be rechecked before sending.");
        } catch (IOException | RuntimeException | LinkageError e) {
            return FormValidation.error("Delivery could not be armed. No approval was assumed.");
        }
    }

    public String getDeliveryStatus() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (!isEnabled()) return "Slack delivery globally disabled";
        try {
            return Jenkins.get()
                            .getExtensionList(NotificationRuntime.class)
                            .get(0)
                            .deliveryGuard()
                            .canDispatch()
                    ? "Delivery armed for this controller boot"
                    : "Delivery paused — administrator re-arm required after restart or restore";
        } catch (IOException | RuntimeException | LinkageError e) {
            return "Delivery paused — controller approval unavailable";
        }
    }
}

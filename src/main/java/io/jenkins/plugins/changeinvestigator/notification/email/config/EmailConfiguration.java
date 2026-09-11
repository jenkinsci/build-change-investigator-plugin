package io.jenkins.plugins.changeinvestigator.notification.email.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime;
import io.jenkins.plugins.changeinvestigator.notification.email.EmailTransport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Administrator approval is required before investigation summaries leave Jenkins. */
@Extension
public class EmailConfiguration extends GlobalConfiguration {
    private List<EmailDestination> destinations = List.of();
    private boolean enabled;
    private List<EmailMailProfile> profiles = List.of();
    private java.util.Map<String, Long> generationHistory = new java.util.HashMap<>();

    public EmailConfiguration() {
        load();
    }

    public static EmailConfiguration get() {
        return GlobalConfiguration.all().get(EmailConfiguration.class);
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    /** Turning delivery off and on never revives an older disclosure approval. */
    public synchronized void setEnabled(boolean value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (enabled == value) return;
        List<EmailDestination> replacement = getDestinations().stream()
                .map(EmailDestination::nextDisclosureGeneration)
                .toList();
        if (generationHistory == null) generationHistory = new java.util.HashMap<>();
        for (EmailDestination destination : replacement)
            generationHistory.put(destination.getId(), destination.getGeneration());
        destinations = replacement;
        enabled = value;
        save();
        io.jenkins.plugins.changeinvestigator.notification.email.EmailDispatcher.configurationChanged();
    }

    public synchronized List<EmailDestination> getDestinations() {
        return destinations == null ? List.of() : List.copyOf(destinations);
    }

    /** Replaces the approved list atomically. Generations and verification are never request-bound. */
    public synchronized List<EmailMailProfile> getProfiles() {
        return profiles == null ? List.of() : List.copyOf(profiles);
    }

    public synchronized void replaceDestinations(List<EmailDestination> requested) {
        replaceConfiguration(getProfiles(), requested);
    }

    public synchronized void replaceConfiguration(
            List<EmailMailProfile> requestedProfiles, List<EmailDestination> requested) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (requested == null || requested.size() > 100)
            throw new IllegalArgumentException("At most 100 Email destinations are allowed");
        if (requestedProfiles == null
                || requestedProfiles.size() > 25
                || requestedProfiles.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("At most 25 valid SMTP profiles are allowed");
        java.util.Map<String, EmailMailProfile> approvedProfiles = new java.util.HashMap<>();
        for (EmailMailProfile profile : requestedProfiles)
            if (approvedProfiles.put(profile.getId(), profile) != null)
                throw new IllegalArgumentException("Duplicate SMTP profile ID");
        List<EmailDestination> replacement = new ArrayList<>();
        java.util.Map<String, Long> history =
                generationHistory == null ? new java.util.HashMap<>() : new java.util.HashMap<>(generationHistory);
        HashSet<UUID> ids = new HashSet<>();
        for (EmailDestination value : requested) {
            if (value == null) throw new IllegalArgumentException("Invalid Email destination entry");
            if (!ids.add(value.identity())) throw new IllegalArgumentException("Duplicate Email destination ID");
            EmailDestination old = getDestinations().stream()
                    .filter(d -> d.identity().equals(value.identity()))
                    .findFirst()
                    .orElse(null);
            EmailDestination next = old == null && history.containsKey(value.getId())
                    ? value.afterRetiredGeneration(history.get(value.getId()))
                    : value.reconciled(old);
            EmailMailProfile nextProfile = approvedProfiles.get(next.getProfileId());
            if (nextProfile == null) throw new IllegalArgumentException("Select an approved SMTP profile");
            EmailMailProfile oldProfile = old == null
                    ? null
                    : getProfiles().stream()
                            .filter(p -> p.getId().equals(old.getProfileId()))
                            .findFirst()
                            .orElse(null);
            if (old != null && !nextProfile.sameRoute(oldProfile))
                next = next.nextDisclosureGeneration().unverifiedCopy();
            else if (old != null
                    && oldProfile != null
                    && !nextProfile.getCredentialId().equals(oldProfile.getCredentialId()))
                next = next.unverifiedCopy();
            replacement.add(next);
            history.put(next.getId(), next.getGeneration());
        }
        if (history.size() > 1000)
            throw new IllegalArgumentException("Email destination identity history bound reached");
        destinations = List.copyOf(replacement);
        profiles = List.copyOf(requestedProfiles);
        generationHistory = history;
        save();
        io.jenkins.plugins.changeinvestigator.notification.email.EmailDispatcher.configurationChanged();
    }

    @Override
    public boolean configure(StaplerRequest2 request, JSONObject form) throws FormException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            List<EmailDestination> requested = request.bindJSONToList(EmailDestination.class, form.get("destinations"));
            synchronized (this) {
                replaceConfiguration(request.bindJSONToList(EmailMailProfile.class, form.get("profiles")), requested);
                setEnabled(form.optBoolean("enabled", false));
            }
            return true;
        } catch (IllegalArgumentException e) {
            throw new FormException("Invalid Email destination configuration", "destinations");
        }
    }

    public List<EmailDestination> approvedFor(Job<?, ?> job) {
        return getDestinations().stream()
                .filter(d -> d.isEnabled() && d.isVerified() && d.allows(job.getFullName()))
                .toList();
    }

    public Optional<EmailDestination> approved(Job<?, ?> job, UUID id) {
        return approvedFor(job).stream().filter(d -> d.identity().equals(id)).findFirst();
    }

    public EffectiveEmailPolicy policy(Job<?, ?> job) {
        if (!isEnabled()) return new EffectiveEmailPolicy(List.of(), true, false);
        EmailJobProperty configured = job.getProperty(EmailJobProperty.class);
        if (configured != null && configured.getMode() == EmailJobProperty.Mode.OFF)
            return new EffectiveEmailPolicy(List.of(), true, false);
        List<UUID> allowed =
                approvedFor(job).stream().map(EmailDestination::identity).toList();
        if (configured == null || configured.getMode() == EmailJobProperty.Mode.INHERIT)
            return new EffectiveEmailPolicy(allowed.stream().limit(10).toList(), true, false);
        return new EffectiveEmailPolicy(
                configured.selectedIds().stream().filter(allowed::contains).toList(),
                configured.isRecovery(),
                configured.isAiOnly());
    }

    public List<EmailDestination> resolvedDestinations(Job<?, ?> job) {
        List<UUID> ids = policy(job).destinationIds();
        return approvedFor(job).stream().filter(d -> ids.contains(d.identity())).toList();
    }

    public Optional<EmailDestination> authorize(Job<?, ?> job, UUID id, long generation) {
        return resolvedDestinations(job).stream()
                .filter(d -> d.identity().equals(id) && d.getGeneration() == generation)
                .findFirst();
    }

    /** Resolves current credentials only after current destination authorization. */
    public synchronized Optional<EmailTransport.Settings> resolveSettings(Job<?, ?> job, EmailDestination destination) {
        return authorize(job, destination.identity(), destination.getGeneration())
                .flatMap(current -> settings(job, current));
    }

    private Optional<EmailTransport.Settings> settings(Job<?, ?> job, EmailDestination destination) {
        try {
            EmailMailProfile profile = getProfiles().stream()
                    .filter(p -> p.getId().equals(destination.getProfileId()))
                    .findFirst()
                    .orElseThrow();
            String username = "";
            String password = "";
            if (!profile.getCredentialId().isEmpty()) {
                List<StandardUsernamePasswordCredentials> values;
                if (job != null) {
                    values = CredentialsProvider.lookupCredentialsInItem(
                            StandardUsernamePasswordCredentials.class, job, ACL.SYSTEM2, List.of());
                } else {
                    String scope = destination.getScope();
                    String path = scope.endsWith("/**") ? scope.substring(0, scope.length() - 3) : scope;
                    hudson.model.Item item = Jenkins.get().getItemByFullName(path);
                    if (item instanceof Job<?, ?> scopedJob) {
                        values = CredentialsProvider.lookupCredentialsInItem(
                                StandardUsernamePasswordCredentials.class, scopedJob, ACL.SYSTEM2, List.of());
                    } else {
                        hudson.model.ItemGroup<?> group =
                                item instanceof hudson.model.ItemGroup<?> folder ? folder : Jenkins.get();
                        values = CredentialsProvider.lookupCredentialsInItemGroup(
                                StandardUsernamePasswordCredentials.class, group, ACL.SYSTEM2, List.of());
                    }
                }
                StandardUsernamePasswordCredentials credential =
                        CredentialsMatchers.firstOrNull(values, CredentialsMatchers.withId(profile.getCredentialId()));
                if (credential == null) return Optional.empty();
                username = credential.getUsername();
                password = credential.getPassword().getPlainText();
            }
            return Optional.of(new EmailTransport.Settings(
                    profile.getHost(),
                    profile.getPort(),
                    EmailTransport.TlsMode.valueOf(profile.getTlsMode()),
                    profile.isAllowInternal(),
                    username,
                    password));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** Opens and authenticates the saved SMTP route without submitting any message. */
    @RequirePOST
    public FormValidation doTestConnection(@QueryParameter String destinationId) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            UUID id = UUID.fromString(destinationId);
            EmailDestination target = getDestinations().stream()
                    .filter(d -> d.identity().equals(id))
                    .findFirst()
                    .orElseThrow();
            var settings = settings(null, target);
            if (settings.isEmpty())
                return FormValidation.error(
                        "Email configuration or credential is unavailable. Check the saved SMTP profile.");
            var result = checkConnection(settings.get());
            if (!result.usable())
                return FormValidation.error(
                        "SMTP connection or authentication could not be validated. Check the approved mail profile.");
            synchronized (this) {
                List<EmailDestination> replacement = new ArrayList<>(getDestinations());
                int index = replacement.indexOf(target);
                if (index < 0)
                    return FormValidation.error("Email configuration changed. Test the saved destination again.");
                replacement.set(index, target.verifiedCopy());
                destinations = List.copyOf(replacement);
                save();
            }
            io.jenkins.plugins.changeinvestigator.notification.email.EmailDispatcher.configurationChanged();
            return FormValidation.ok(
                    "SMTP connection validated without sending mail. Recipient acceptance is checked only during delivery.");
        } catch (RuntimeException | LinkageError e) {
            return FormValidation.error(
                    "Email connection could not be validated. Check the saved configuration and try again.");
        }
    }

    protected EmailTransport.ConnectionResult checkConnection(EmailTransport.Settings settings) {
        return new EmailTransport().testConnection(settings);
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
            io.jenkins.plugins.changeinvestigator.notification.email.EmailDispatcher.configurationChanged();
            return FormValidation.ok(
                    "Email delivery approved for this controller boot. Queued work will be rechecked before sending.");
        } catch (IOException | RuntimeException | LinkageError e) {
            return FormValidation.error("Delivery could not be armed. No approval was assumed.");
        }
    }

    public String getDeliveryStatus() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (!isEnabled()) return "Email delivery globally disabled";
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

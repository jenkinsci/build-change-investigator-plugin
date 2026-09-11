package io.jenkins.plugins.changeinvestigator.notification.email.config;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import java.util.UUID;
import org.kohsuke.stapler.DataBoundConstructor;

/** One administrator-approved mailbox and source disclosure scope. */
public final class EmailDestination extends AbstractDescribableImpl<EmailDestination> {
    private final String id, name, sender, recipient, profileId, scope;
    private final boolean enabled;
    private long generation = 1;
    private boolean verified;

    @DataBoundConstructor
    public EmailDestination(
            String id, String name, String sender, String recipient, String profileId, String scope, boolean enabled) {
        this.id = id == null || id.isBlank()
                ? UUID.randomUUID().toString()
                : UUID.fromString(id).toString();
        this.name = label(name, 80);
        this.sender = mailbox(sender);
        this.recipient = mailbox(recipient);
        if (profileId == null) throw new IllegalArgumentException("Select a saved SMTP profile");
        this.profileId = UUID.fromString(profileId).toString();
        this.scope = validScope(scope);
        this.enabled = enabled;
    }

    static String label(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(SafeContent.text(value, max)))
            throw new IllegalArgumentException("Invalid email configuration field");
        return value;
    }

    static String mailbox(String value) {
        return io.jenkins.plugins.changeinvestigator.notification.email.EmailMessage.address(value);
    }

    private static String validScope(String value) {
        String scope = label(value, 512);
        String path = scope.endsWith("/**") ? scope.substring(0, scope.length() - 3) : scope;
        if (path.isBlank()
                || path.startsWith("/")
                || path.endsWith("/")
                || path.contains("//")
                || path.contains("*")
                || path.contains("\\")
                || path.contains("..")) throw new IllegalArgumentException("Use an exact job path or folder/** scope");
        return scope;
    }

    EmailDestination reconciled(EmailDestination previous) {
        EmailDestination copy = new EmailDestination(id, name, sender, recipient, profileId, scope, enabled);
        if (previous != null) {
            boolean semantic = !sender.equals(previous.sender)
                    || !recipient.equals(previous.recipient)
                    || !profileId.equals(previous.profileId)
                    || !scope.equals(previous.scope)
                    || enabled != previous.enabled;
            copy.generation = semantic ? Math.addExact(previous.generation, 1) : previous.generation;
            copy.verified = !semantic && previous.verified;
        }
        return copy;
    }

    EmailDestination verifiedCopy() {
        EmailDestination copy = reconciled(this);
        copy.verified = true;
        return copy;
    }

    EmailDestination unverifiedCopy() {
        EmailDestination copy = reconciled(this);
        copy.verified = false;
        return copy;
    }

    EmailDestination afterRetiredGeneration(long retired) {
        EmailDestination copy = reconciled(null);
        copy.generation = Math.addExact(retired, 1);
        return copy;
    }

    EmailDestination nextDisclosureGeneration() {
        EmailDestination copy = reconciled(this);
        copy.generation = Math.addExact(generation, 1);
        return copy;
    }

    public boolean allows(String jobFullName) {
        return scope.endsWith("/**")
                ? jobFullName.startsWith(scope.substring(0, scope.length() - 2))
                : scope.equals(jobFullName);
    }

    public UUID identity() {
        return UUID.fromString(id);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getScope() {
        return scope;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getGeneration() {
        return generation;
    }

    public boolean isVerified() {
        return verified;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<EmailDestination> {
        @Override
        public String getDisplayName() {
            return "Approved email destination";
        }
    }
}

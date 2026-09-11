package io.jenkins.plugins.changeinvestigator.notification.email.config;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import io.jenkins.plugins.changeinvestigator.notification.email.EmailTransport;
import java.util.UUID;
import org.kohsuke.stapler.DataBoundConstructor;

/** Administrator-controlled SMTP route. Only a credential reference is persisted. */
public final class EmailMailProfile extends AbstractDescribableImpl<EmailMailProfile> {
    private final String id, name, host, tlsMode, credentialId;
    private final int port;
    private final boolean allowInternal;

    @DataBoundConstructor
    public EmailMailProfile(
            String id, String name, String host, int port, String tlsMode, boolean allowInternal, String credentialId) {
        this.id = id == null || id.isBlank()
                ? UUID.randomUUID().toString()
                : UUID.fromString(id).toString();
        this.name = EmailDestination.label(name, 80);
        if (host == null || host.length() > 253 || !host.matches("[A-Za-z0-9][A-Za-z0-9.:-]*") || host.contains(".."))
            throw new IllegalArgumentException("Invalid SMTP host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SMTP port");
        this.host = host;
        this.port = port;
        if (tlsMode == null) throw new IllegalArgumentException("Select SMTP transport security");
        this.tlsMode = EmailTransport.TlsMode.valueOf(tlsMode).name();
        this.allowInternal = allowInternal;
        this.credentialId =
                credentialId == null || credentialId.isBlank() ? "" : EmailDestination.label(credentialId, 128);
        if (this.tlsMode.equals("PLAIN_INTERNAL") && (!allowInternal || !this.credentialId.isEmpty()))
            throw new IllegalArgumentException(
                    "Plain SMTP requires explicitly approved internal routing and cannot use authentication");
    }

    boolean sameRoute(EmailMailProfile other) {
        return other != null
                && host.equals(other.host)
                && port == other.port
                && tlsMode.equals(other.tlsMode)
                && allowInternal == other.allowInternal;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getTlsMode() {
        return tlsMode;
    }

    public boolean isAllowInternal() {
        return allowInternal;
    }

    public String getCredentialId() {
        return credentialId;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<EmailMailProfile> {
        @Override
        public String getDisplayName() {
            return "Approved SMTP profile";
        }

        public hudson.util.ListBoxModel doFillTlsModeItems() {
            var values = new hudson.util.ListBoxModel();
            values.add("Require STARTTLS", "STARTTLS_REQUIRED");
            values.add("Implicit TLS", "IMPLICIT_TLS");
            values.add("Plain SMTP — explicitly approved internal relay only", "PLAIN_INTERNAL");
            return values;
        }
    }
}

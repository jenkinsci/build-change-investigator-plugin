package io.jenkins.plugins.changeinvestigator.notification.email;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;

/** Maintained Jenkins Jakarta Mail provider with explicit SMTP acceptance boundaries. */
public final class EmailTransport {
    private static final ThreadPoolExecutor OPERATIONS = new ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),
            runnable -> {
                Thread thread = new Thread(runnable, "bci-email-transport");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private final long deadlineMillis;
    private final int timeoutMillis;

    public EmailTransport() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    EmailTransport(Duration deadline, Duration timeout) {
        if (deadline.toMillis() < 1
                || deadline.toMillis() > 30000
                || timeout.toMillis() < 1
                || timeout.toMillis() > 10000) throw new IllegalArgumentException("Invalid mail timeout");
        deadlineMillis = deadline.toMillis();
        timeoutMillis = (int) timeout.toMillis();
    }

    public enum TlsMode {
        STARTTLS_REQUIRED,
        IMPLICIT_TLS,
        PLAIN_INTERNAL
    }

    public enum Status {
        SENT,
        RETRYABLE,
        PERMANENT_FAILURE,
        UNKNOWN_OUTCOME
    }

    public record Outcome(Status status, String safeCode) {}

    public record ConnectionResult(boolean usable, String safeCode) {}

    /** Ephemeral settings: callers resolve the current credential immediately before each attempt. */
    public record Settings(
            String host, int port, TlsMode tls, boolean allowInternal, String username, String password) {
        public Settings {
            if (host == null
                    || host.length() > 253
                    || !host.matches("[A-Za-z0-9][A-Za-z0-9.:-]*")
                    || port < 1
                    || port > 65535
                    || tls == null
                    || (tls == TlsMode.PLAIN_INTERNAL && !allowInternal)
                    || ((username == null || username.isEmpty()) != (password == null || password.isEmpty()))
                    || (username != null
                            && (username.length() > 256 || username.codePoints().anyMatch(Character::isISOControl)))
                    || (password != null && password.length() > 4096)
                    || (tls == TlsMode.PLAIN_INTERNAL && username != null && !username.isEmpty()))
                throw new IllegalArgumentException("Invalid approved mail settings");
        }

        @Override
        public String toString() {
            return "Approved mail settings";
        }
    }

    public Outcome send(Settings settings, String recipient, String frozenMimeBase64) {
        if (settings == null) return outcome(Status.PERMANENT_FAILURE, "INVALID_CONFIGURATION");
        try {
            MimeMessage message =
                    EmailMessage.decode(Session.getInstance(new Properties()), frozenMimeBase64, recipient);
            Object content = message.getContent();
            if (!(content instanceof jakarta.mail.Multipart parts)
                    || parts.getCount() != 2
                    || !parts.getBodyPart(0).isMimeType("text/plain")
                    || !parts.getBodyPart(1).isMimeType("text/html"))
                return outcome(Status.PERMANENT_FAILURE, "INVALID_MIME");
            if (settings.password() != null && !settings.password().isEmpty()) {
                var headers = message.getAllHeaderLines();
                while (headers.hasMoreElements())
                    if (EmailMessage.containsSecret(headers.nextElement(), settings.password()))
                        return outcome(Status.PERMANENT_FAILURE, "SECRET_PAYLOAD_WITHHELD");
                if (EmailMessage.containsSecret(message.getSubject(), settings.password()))
                    return outcome(Status.PERMANENT_FAILURE, "SECRET_PAYLOAD_WITHHELD");
                for (int i = 0; i < parts.getCount(); i++)
                    if (EmailMessage.containsSecret(
                            parts.getBodyPart(i).getContent().toString(), settings.password()))
                        return outcome(Status.PERMANENT_FAILURE, "SECRET_PAYLOAD_WITHHELD");
            }
        } catch (MessagingException | IOException | RuntimeException | LinkageError e) {
            return outcome(Status.PERMANENT_FAILURE, "INVALID_MIME");
        }
        return execute(settings, recipient, frozenMimeBase64);
    }

    /** Connect, negotiate TLS and authenticate only. No MAIL, RCPT or DATA command is issued. */
    public ConnectionResult testConnection(Settings settings) {
        if (settings == null) return new ConnectionResult(false, "INVALID_CONFIGURATION");
        Outcome result = execute(settings, null, null);
        return new ConnectionResult(
                result.status() == Status.SENT,
                result.status() == Status.SENT ? "CONNECTION_VERIFIED" : result.safeCode());
    }

    private Outcome execute(Settings settings, String recipient, String mime) {
        Attempt attempt = new Attempt();
        Future<Outcome> future;
        try {
            future = OPERATIONS.submit(() -> perform(settings, recipient, mime, attempt));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return outcome(Status.RETRYABLE, "TRANSPORT_BUSY");
        }
        try {
            return future.get(deadlineMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            attempt.close();
            future.cancel(true);
            return uncertain(attempt);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException failed) {
            attempt.close();
            future.cancel(true);
            return uncertain(attempt);
        }
    }

    private Outcome perform(Settings settings, String recipient, String mime, Attempt attempt) {
        ObservedTransport transport = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(timeoutMillis));
            properties.setProperty("mail.smtp.timeout", Integer.toString(timeoutMillis));
            properties.setProperty("mail.smtp.writetimeout", Integer.toString(timeoutMillis));
            properties.setProperty("mail.smtp.quitwait", "false");
            properties.setProperty("mail.smtp.sendpartial", "false");
            properties.setProperty("mail.smtp.reportsuccess", "false");
            properties.setProperty("mail.smtp.allow8bitmime", "false");
            properties.setProperty("mail.smtp.chunksize", "0");
            properties.setProperty("mail.smtp.localhost", "localhost");
            properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            properties.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
            properties.setProperty("mail.smtp.socketFactory.fallback", "false");
            properties.put(
                    "mail.smtp.socketFactory",
                    new GuardedSocketFactory(
                            settings.allowInternal(), settings.tls() == TlsMode.PLAIN_INTERNAL, attempt));
            properties.setProperty("mail.debug", "false");
            properties.setProperty("mail.debug.auth", "false");
            properties.setProperty("mail.debug.auth.username", "false");
            properties.setProperty("mail.debug.auth.password", "false");
            properties.setProperty(
                    "mail.smtp.auth",
                    Boolean.toString(
                            settings.username() != null && !settings.username().isEmpty()));
            properties.setProperty("mail.smtp.auth.mechanisms", "PLAIN LOGIN");
            properties.setProperty(
                    "mail.smtp.starttls.enable", Boolean.toString(settings.tls() == TlsMode.STARTTLS_REQUIRED));
            properties.setProperty(
                    "mail.smtp.starttls.required", Boolean.toString(settings.tls() == TlsMode.STARTTLS_REQUIRED));
            properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(settings.tls() == TlsMode.IMPLICIT_TLS));
            Session session = Session.getInstance(properties);
            transport = new ObservedTransport(session, attempt);
            transport.connect(settings.host(), settings.port(), settings.username(), settings.password());
            if (recipient == null) return outcome(Status.SENT, "CONNECTION_VERIFIED");
            MimeMessage message = EmailMessage.decode(session, mime, recipient);
            transport.sendMessage(message, new Address[] {new InternetAddress(recipient, true)});
            return outcome(Status.SENT, "ACCEPTED");
        } catch (MessagingException failure) {
            int code = rejectionCode(failure);
            if (code == 0 && !attempt.dataStarted && transport != null) code = transport.getLastReturnCode();
            if (code >= 400 && code < 500) return outcome(Status.RETRYABLE, "SMTP_TRANSIENT_REJECTION");
            if (code >= 500 && code < 600) return outcome(Status.PERMANENT_FAILURE, "SMTP_PERMANENT_REJECTION");
            if (failure instanceof AuthenticationFailedException)
                return outcome(Status.PERMANENT_FAILURE, "AUTHENTICATION_REJECTED");
            return uncertain(attempt);
        } catch (RuntimeException | LinkageError failure) {
            return uncertain(attempt);
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException | RuntimeException ignored) {
                }
            }
            attempt.close();
        }
    }

    private static int rejectionCode(MessagingException failure) {
        Exception current = failure;
        for (int i = 0; i < 12 && current != null; i++) {
            if (current instanceof SMTPAddressFailedException rejected) return rejected.getReturnCode();
            if (current instanceof SMTPSendFailedException rejected) return rejected.getReturnCode();
            current = current instanceof MessagingException nested ? nested.getNextException() : null;
        }
        return 0;
    }

    private static Outcome uncertain(Attempt attempt) {
        return attempt.dataStarted
                ? outcome(Status.UNKNOWN_OUTCOME, "ACCEPTANCE_UNKNOWN")
                : outcome(Status.RETRYABLE, "PRE_ACCEPTANCE_UNAVAILABLE");
    }

    private static Outcome outcome(Status status, String code) {
        return new Outcome(status, code);
    }

    private static final class Attempt {
        private final AtomicReference<Socket> socket = new AtomicReference<>();
        private volatile boolean closed;
        private volatile boolean dataStarted;

        private void close() {
            closed = true;
            Socket active = socket.get();
            if (active != null)
                try {
                    active.close();
                } catch (IOException ignored) {
                }
        }
    }

    private static final class ObservedTransport extends SMTPTransport {
        private final Attempt attempt;

        private ObservedTransport(Session session, Attempt attempt) {
            super(session, null);
            this.attempt = attempt;
        }

        @Override
        protected OutputStream data() throws MessagingException {
            OutputStream output = super.data();
            attempt.dataStarted = true;
            return output;
        }
    }

    private static final class GuardedSocketFactory extends SocketFactory {
        private final boolean allowInternal;
        private final boolean internalOnly;
        private final Attempt attempt;

        private GuardedSocketFactory(boolean allowInternal, boolean internalOnly, Attempt attempt) {
            this.allowInternal = allowInternal;
            this.internalOnly = internalOnly;
            this.attempt = attempt;
        }

        @Override
        public Socket createSocket() throws IOException {
            GuardedSocket socket = new GuardedSocket(allowInternal, internalOnly, attempt);
            if (!attempt.socket.compareAndSet(null, socket) || attempt.closed) {
                socket.close();
                throw new IOException("Mail attempt ended");
            }
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(local, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(local, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }
    }

    /** Checks the exact resolved connect address; TLS retains the original hostname for verification. */
    private static final class GuardedSocket extends Socket {
        private final boolean allowInternal;
        private final boolean internalOnly;
        private final Attempt attempt;
        private InputStream bounded;

        private GuardedSocket(boolean allowInternal, boolean internalOnly, Attempt attempt) {
            this.allowInternal = allowInternal;
            this.internalOnly = internalOnly;
            this.attempt = attempt;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress target)
                    || target.isUnresolved()
                    || attempt.closed
                    || !allowed(target.getAddress(), allowInternal)
                    || internalOnly && allowed(target.getAddress(), false))
                throw new IOException("Mail endpoint not approved");
            super.connect(endpoint, Math.min(Math.max(timeout, 1), 10000));
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 10000);
        }

        @Override
        public synchronized InputStream getInputStream() throws IOException {
            if (bounded == null)
                bounded = new FilterInputStream(super.getInputStream()) {
                    private int remaining = 131072;

                    @Override
                    public int read() throws IOException {
                        if (remaining <= 0) throw new IOException("Mail response limit");
                        int value = in.read();
                        if (value >= 0) remaining--;
                        return value;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (remaining <= 0) throw new IOException("Mail response limit");
                        int count = in.read(bytes, offset, Math.min(length, remaining));
                        if (count > 0) remaining -= count;
                        return count;
                    }
                };
            return bounded;
        }
    }

    static boolean allowed(InetAddress address, boolean allowInternal) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
        if (allowInternal) return true;
        byte[] bytes = address.getAddress();
        return !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !(bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                && !(bytes.length == 4
                        && ((bytes[0] & 255) == 0
                                || (bytes[0] & 255) >= 224
                                || (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127));
    }
}

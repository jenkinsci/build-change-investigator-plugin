package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import jenkins.test.https.KeyStoreManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Real local TLS handshakes; only this fixture's generated certificate is trusted. */
@ResourceLock("default-ssl-context")
class EmailTransportTlsTest {
    @TempDir
    static Path temporary;

    private static SSLContext serverContext;
    private static SSLContext trustedContext;
    private SSLContext originalContext;
    private final EmailTransport transport = new EmailTransport(Duration.ofSeconds(8), Duration.ofSeconds(3));

    @BeforeAll
    static void createSyntheticCertificate() throws Exception {
        String executable =
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        Path store = temporary.resolve("synthetic-mail.p12");
        Process process = new ProcessBuilder(
                        keytool.toString(),
                        "-genkeypair",
                        "-alias",
                        "synthetic-mail",
                        "-keystore",
                        store.toString(),
                        "-storetype",
                        "PKCS12",
                        "-storepass",
                        "synthetic-store-password",
                        "-keypass",
                        "synthetic-store-password",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-noprompt")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Synthetic certificate generation timed out");
        }
        assertEquals(0, process.exitValue());
        KeyStoreManager manager = new KeyStoreManager(store, "synthetic-store-password", "PKCS12");
        serverContext = manager.buildServerSSLContext();
        trustedContext = manager.buildClientSSLContext();
    }

    @BeforeEach
    void trustOnlyForTest() throws Exception {
        originalContext = SSLContext.getDefault();
        SSLContext.setDefault(trustedContext);
    }

    @AfterEach
    void restoreTrust() {
        SSLContext.setDefault(originalContext);
    }

    @Test
    void implicitTlsPreservesHostnameVerificationAndSubmitsMime() throws Exception {
        try (TlsSmtp smtp = new TlsSmtp(true, 235)) {
            assertEquals(
                    EmailTransport.Status.SENT,
                    transport
                            .send(smtp.settings("localhost", false), "triage@example.invalid", mime())
                            .status());
            assertEquals(1, smtp.messages.size());
            assertTrue(List.of("TLSv1.2", "TLSv1.3").contains(smtp.protocol));
            assertTrue(smtp.messages.get(0).contains("Message-ID: <bci."));
        }
    }

    @Test
    void startTlsUpgradesBeforeAuthenticationAndDelivery() throws Exception {
        try (TlsSmtp smtp = new TlsSmtp(false, 235)) {
            assertEquals(
                    EmailTransport.Status.SENT,
                    transport
                            .send(smtp.settings("localhost", true), "triage@example.invalid", mime())
                            .status());
            assertEquals(1, smtp.messages.size());
            int tls = smtp.commands.indexOf("STARTTLS");
            int auth = -1;
            for (int i = 0; i < smtp.commands.size(); i++)
                if (smtp.commands.get(i).startsWith("AUTH ")) auth = i;
            assertTrue(tls >= 0 && auth > tls);
            assertTrue(smtp.authenticatedAfterTls);
        }
    }

    @Test
    void trustedCertificateWithWrongHostnameFailsBeforeEnvelope() throws Exception {
        try (TlsSmtp smtp = new TlsSmtp(true, 235)) {
            assertFalse(
                    transport.testConnection(smtp.settings("127.0.0.1", false)).usable());
            assertTrue(smtp.commands.stream()
                    .noneMatch(command -> command.startsWith("MAIL") || command.startsWith("AUTH")));
            assertEquals(0, smtp.messages.size());
        }
    }

    @Test
    void untrustedCertificateIsNotAccepted() throws Exception {
        SSLContext.setDefault(originalContext);
        try (TlsSmtp smtp = new TlsSmtp(false, 235)) {
            assertFalse(
                    transport.testConnection(smtp.settings("localhost", false)).usable());
            assertTrue(smtp.commands.stream()
                    .noneMatch(command -> command.startsWith("MAIL") || command.startsWith("AUTH")));
            assertEquals(0, smtp.messages.size());
        }
    }

    @Test
    void authenticatedConnectionCheckRemainsNonposting() throws Exception {
        try (TlsSmtp smtp = new TlsSmtp(false, 235)) {
            assertTrue(
                    transport.testConnection(smtp.settings("localhost", true)).usable());
            assertTrue(smtp.authenticatedAfterTls);
            assertTrue(smtp.commands.stream()
                    .noneMatch(command ->
                            command.startsWith("MAIL") || command.startsWith("RCPT") || command.equals("DATA")));
        }
    }

    @Test
    void authenticationRejectionDistinguishesTransientAndPermanent() throws Exception {
        try (TlsSmtp smtp = new TlsSmtp(false, 454)) {
            assertEquals(
                    EmailTransport.Status.RETRYABLE,
                    transport
                            .send(smtp.settings("localhost", true), "triage@example.invalid", mime())
                            .status());
            assertEquals(0, smtp.messages.size());
        }
        try (TlsSmtp smtp = new TlsSmtp(false, 535)) {
            assertEquals(
                    EmailTransport.Status.PERMANENT_FAILURE,
                    transport
                            .send(smtp.settings("localhost", true), "triage@example.invalid", mime())
                            .status());
            assertEquals(0, smtp.messages.size());
        }
    }

    private static String mime() {
        return EmailMessage.prepare(
                        UUID.randomUUID(),
                        "bci@example.invalid",
                        "triage@example.invalid",
                        "[BCI] Synthetic regression",
                        "Inspect changed source",
                        "<p>Inspect changed source</p>",
                        null,
                        List.of(),
                        1700000000000L)
                .mimeBase64();
    }

    private static final class TlsSmtp implements AutoCloseable {
        private final boolean implicit;
        private final ServerSocket listener;
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final Thread worker;
        private volatile Socket active;
        private volatile String protocol;
        private volatile boolean authenticatedAfterTls;

        private TlsSmtp(boolean implicit, int authCode) throws IOException {
            this.implicit = implicit;
            listener = implicit
                    ? serverContext
                            .getServerSocketFactory()
                            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                    : new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(
                    () -> {
                        try (Socket socket = listener.accept()) {
                            active = socket;
                            socket.setSoTimeout(5000);
                            conversation(socket, authCode);
                        } catch (IOException ignored) {
                        }
                    },
                    "synthetic-smtp-tls");
            worker.setDaemon(true);
            worker.start();
        }

        private EmailTransport.Settings settings(String host, boolean auth) {
            return new EmailTransport.Settings(
                    host,
                    listener.getLocalPort(),
                    implicit ? EmailTransport.TlsMode.IMPLICIT_TLS : EmailTransport.TlsMode.STARTTLS_REQUIRED,
                    true,
                    auth ? "synthetic-user" : null,
                    auth ? "synthetic-password" : null);
        }

        private void conversation(Socket initial, int authCode) throws IOException {
            Socket socket = initial;
            boolean tls = implicit;
            if (tls) {
                ((SSLSocket) socket).startHandshake();
                protocol = ((SSLSocket) socket).getSession().getProtocol();
            }
            BufferedReader input = input(socket);
            OutputStream output = socket.getOutputStream();
            line(output, "220 synthetic SMTP");
            String command;
            while ((command = input.readLine()) != null) {
                commands.add(command);
                if (command.startsWith("EHLO")) {
                    line(output, "250-synthetic");
                    line(output, tls ? "250 AUTH PLAIN LOGIN" : "250 STARTTLS");
                } else if (command.equals("STARTTLS") && !tls) {
                    line(output, "220 begin TLS");
                    SSLSocket secured = (SSLSocket) serverContext
                            .getSocketFactory()
                            .createSocket(socket, "localhost", listener.getLocalPort(), true);
                    secured.setUseClientMode(false);
                    secured.startHandshake();
                    active = secured;
                    socket = secured;
                    protocol = secured.getSession().getProtocol();
                    input = input(secured);
                    output = secured.getOutputStream();
                    tls = true;
                } else if (command.startsWith("AUTH PLAIN")) {
                    authenticatedAfterTls = tls;
                    line(output, authCode + " synthetic authentication result");
                } else if (command.startsWith("MAIL") || command.startsWith("RCPT")) {
                    line(output, "250 accepted");
                } else if (command.equals("DATA")) {
                    line(output, "354 start data");
                    StringBuilder message = new StringBuilder();
                    String row;
                    while ((row = input.readLine()) != null && !row.equals("."))
                        message.append(row).append("\r\n");
                    if (row == null) return;
                    messages.add(message.toString());
                    line(output, "250 accepted");
                } else if (command.equals("QUIT")) {
                    line(output, "221 goodbye");
                    return;
                } else line(output, "250 reset");
            }
        }

        private static BufferedReader input(Socket socket) throws IOException {
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        private static void line(OutputStream output, String value) throws IOException {
            output.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            if (active != null) active.close();
            worker.interrupt();
            worker.join(5000);
        }
    }
}

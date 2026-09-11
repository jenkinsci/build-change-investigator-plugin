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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EmailTransportTest {
    private static final String FROM = "bci@example.invalid";
    private static final String TO = "triage@example.invalid";
    private final EmailTransport transport = new EmailTransport(Duration.ofSeconds(3), Duration.ofMillis(500));

    private static String mime(String recipient) {
        return EmailMessage.prepare(
                        UUID.randomUUID(),
                        FROM,
                        recipient,
                        "[BCI] Synthetic regression",
                        "Inspect changed source",
                        "<p>Inspect changed source</p>",
                        null,
                        List.of(),
                        1700000000000L)
                .mimeBase64();
    }

    @Test
    void successfulSingleRecipientSubmissionAndSafeRetryPreserveFrozenMessage() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.ACCEPT)) {
            String frozen = mime(TO);
            assertEquals(
                    EmailTransport.Status.SENT,
                    transport.send(smtp.settings(), TO, frozen).status());
            assertEquals(1, smtp.messages.size());
            assertTrue(smtp.messages.get(0).contains("Message-ID: <bci."));
            assertFalse(smtp.messages.get(0).contains("Bcc:"));
            assertEquals(
                    1,
                    smtp.commands.stream().filter(c -> c.startsWith("RCPT TO:")).count());
            assertTrue(smtp.commands.contains("RCPT TO:<" + TO + ">"));
        }
    }

    @Test
    void nonpostingConnectionChecksIssueNoEnvelopeOrData() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.ACCEPT)) {
            assertTrue(transport.testConnection(smtp.settings()).usable());
            assertEquals(0, smtp.messages.size());
            assertTrue(smtp.commands.stream()
                    .noneMatch(c -> c.startsWith("MAIL") || c.startsWith("RCPT") || c.equals("DATA")));
        }
    }

    @Test
    void transientRecipientRejectionIsRetryableAndNeverTransmitsData() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.RCPT_450)) {
            assertEquals(
                    EmailTransport.Status.RETRYABLE,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
            assertEquals(0, smtp.messages.size());
            assertFalse(smtp.commands.contains("DATA"));
        }
    }

    @Test
    void permanentRecipientRejectionStopsAndDoesNotLeakOtherRecipient() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.RCPT_550)) {
            assertEquals(
                    EmailTransport.Status.PERMANENT_FAILURE,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
            assertEquals(0, smtp.messages.size());
        }
    }

    @Test
    void separateRecipientTransactionsKeepAcceptedAWhenBIsRejected() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.REJECT_SECOND_RECIPIENT)) {
            String other = "other@example.invalid";
            assertEquals(
                    EmailTransport.Status.SENT,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
            assertEquals(
                    EmailTransport.Status.PERMANENT_FAILURE,
                    transport.send(smtp.settings(), other, mime(other)).status());
            assertEquals(1, smtp.messages.size());
            assertFalse(smtp.messages.get(0).contains(other));
            assertEquals(2, smtp.connections.get());
        }
    }

    @Test
    void dataCommandRejectionIsConclusiveBeforeMessageTransmission() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.DATA_451)) {
            assertEquals(
                    EmailTransport.Status.RETRYABLE,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
            assertEquals(0, smtp.messages.size());
        }
    }

    @Test
    void explicitFinalDataResponsesRetainRejectionTaxonomy() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.FINAL_451)) {
            assertEquals(
                    EmailTransport.Status.RETRYABLE,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
            assertEquals(1, smtp.messages.size());
        }
        try (FakeSmtp smtp = new FakeSmtp(Mode.FINAL_550)) {
            assertEquals(
                    EmailTransport.Status.PERMANENT_FAILURE,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
        }
    }

    @Test
    void lostFinalAcknowledgmentIsUnknownWithoutInternalResend() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.DROP_FINAL)) {
            var result = transport.send(smtp.settings(), TO, mime(TO));
            assertEquals(EmailTransport.Status.UNKNOWN_OUTCOME, result.status());
            assertEquals("ACCEPTANCE_UNKNOWN", result.safeCode());
            assertEquals(1, smtp.messages.size());
            assertEquals(1, smtp.connections.get());
        }
    }

    @Test
    void slowFinalAcknowledgmentIsBoundedAndUnknown() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.SLOW_FINAL)) {
            long started = System.nanoTime();
            assertEquals(
                    EmailTransport.Status.UNKNOWN_OUTCOME,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
            assertEquals(1, smtp.messages.size());
        }
    }

    @Test
    void connectionDropBeforeDataIsSafeToRetry() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.DROP_RCPT)) {
            assertEquals(
                    EmailTransport.Status.RETRYABLE,
                    transport.send(smtp.settings(), TO, mime(TO)).status());
            assertEquals(0, smtp.messages.size());
        }
    }

    @Test
    void tlsRequiredDoesNotDowngradeToPlaintext() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.ACCEPT)) {
            var settings = new EmailTransport.Settings(
                    "127.0.0.1",
                    smtp.server.getLocalPort(),
                    EmailTransport.TlsMode.STARTTLS_REQUIRED,
                    true,
                    null,
                    null);
            assertFalse(transport.testConnection(settings).usable());
            assertTrue(smtp.commands.stream().noneMatch(c -> c.startsWith("MAIL") || c.startsWith("AUTH")));
        }
    }

    @Test
    void privateEndpointRequiresExplicitApprovalAndPlainAuthIsRejected() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.ACCEPT)) {
            var settings = new EmailTransport.Settings(
                    "127.0.0.1",
                    smtp.server.getLocalPort(),
                    EmailTransport.TlsMode.STARTTLS_REQUIRED,
                    false,
                    null,
                    null);
            assertFalse(transport.testConnection(settings).usable());
            assertEquals(0, smtp.connections.get());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EmailTransport.Settings(
                            "127.0.0.1",
                            25,
                            EmailTransport.TlsMode.PLAIN_INTERNAL,
                            true,
                            "synthetic",
                            "synthetic-password"));
            assertFalse(EmailTransport.allowed(InetAddress.getByName("169.254.169.254"), false));
            assertFalse(EmailTransport.allowed(InetAddress.getByName("100.64.0.1"), false));
            assertFalse(EmailTransport.allowed(InetAddress.getByName("fc00::1"), false));
            assertFalse(EmailTransport.allowed(InetAddress.getByName("0.0.0.0"), true));
        }
    }

    @Test
    void currentPasswordInEncodedMimeIsWithheldBeforeAnyConnection() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.ACCEPT)) {
            String password = "synthetic-new-password";
            var settings = new EmailTransport.Settings(
                    "127.0.0.1",
                    smtp.server.getLocalPort(),
                    EmailTransport.TlsMode.STARTTLS_REQUIRED,
                    true,
                    "synthetic",
                    password);
            String frozen = EmailMessage.prepare(
                            UUID.randomUUID(),
                            FROM,
                            TO,
                            "Synthetic",
                            "echo " + password,
                            "<p>Safe</p>",
                            null,
                            List.of(),
                            0)
                    .mimeBase64();
            assertEquals(
                    "SECRET_PAYLOAD_WITHHELD",
                    transport.send(settings, TO, frozen).safeCode());
            assertEquals(0, smtp.connections.get());
            assertFalse(settings.toString().contains(password));
        }
    }

    @Test
    void escapedPasswordIsWithheldBeforeAnyConnection() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.ACCEPT)) {
            String password = "synthetic<secret>&\"'";
            var settings = new EmailTransport.Settings(
                    "127.0.0.1",
                    smtp.server.getLocalPort(),
                    EmailTransport.TlsMode.STARTTLS_REQUIRED,
                    true,
                    "synthetic",
                    password);
            String frozen = EmailMessage.prepare(
                            UUID.randomUUID(),
                            FROM,
                            TO,
                            "Synthetic",
                            "synthetic‹secret›&\"'",
                            "<p>synthetic&lt;secret&gt;&amp;&quot;&#39;</p>",
                            null,
                            List.of(),
                            0)
                    .mimeBase64();
            assertEquals(
                    "SECRET_PAYLOAD_WITHHELD",
                    transport.send(settings, TO, frozen).safeCode());
            assertEquals(0, smtp.connections.get());
        }
    }

    @Test
    void oversizedResponseAndWholeOperationTimeoutAreBounded() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp(Mode.HUGE_BANNER)) {
            assertFalse(transport.testConnection(smtp.settings()).usable());
            assertEquals(0, smtp.messages.size());
        }
        try (FakeSmtp smtp = new FakeSmtp(Mode.SLOW_BANNER)) {
            EmailTransport deadline = new EmailTransport(Duration.ofMillis(100), Duration.ofSeconds(2));
            long started = System.nanoTime();
            assertFalse(deadline.testConnection(smtp.settings()).usable());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
        }
    }

    private enum Mode {
        ACCEPT,
        RCPT_450,
        RCPT_550,
        REJECT_SECOND_RECIPIENT,
        DATA_451,
        FINAL_451,
        FINAL_550,
        DROP_FINAL,
        SLOW_FINAL,
        DROP_RCPT,
        HUGE_BANNER,
        SLOW_BANNER
    }

    private static final class FakeSmtp implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private final Thread worker;
        private volatile boolean closed;

        private FakeSmtp(Mode mode) throws IOException {
            worker = new Thread(
                    () -> {
                        while (!closed) {
                            try (Socket socket = server.accept()) {
                                sockets.add(socket);
                                connections.incrementAndGet();
                                conversation(socket, mode);
                            } catch (IOException ignored) {
                            }
                        }
                    },
                    "synthetic-smtp");
            worker.setDaemon(true);
            worker.start();
        }

        private EmailTransport.Settings settings() {
            return new EmailTransport.Settings(
                    "127.0.0.1", server.getLocalPort(), EmailTransport.TlsMode.PLAIN_INTERNAL, true, null, null);
        }

        private void conversation(Socket socket, Mode mode) throws IOException {
            socket.setSoTimeout(2000);
            OutputStream output = socket.getOutputStream();
            if (mode == Mode.SLOW_BANNER) {
                pause();
                return;
            }
            if (mode == Mode.HUGE_BANNER) {
                line(output, "220 " + "x".repeat(150000));
                return;
            }
            line(output, "220 synthetic SMTP");
            BufferedReader input =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String command;
            while ((command = input.readLine()) != null) {
                commands.add(command);
                if (command.startsWith("EHLO")) line(output, "250 synthetic");
                else if (command.startsWith("MAIL")) line(output, "250 sender accepted");
                else if (command.startsWith("RCPT")) {
                    if (mode == Mode.DROP_RCPT) return;
                    if (mode == Mode.RCPT_450) line(output, "450 temporary rejection");
                    else if (mode == Mode.RCPT_550
                            || mode == Mode.REJECT_SECOND_RECIPIENT && command.contains("other@"))
                        line(output, "550 recipient rejected");
                    else line(output, "250 recipient accepted");
                } else if (command.equals("DATA")) {
                    if (mode == Mode.DATA_451) {
                        line(output, "451 not accepted");
                        continue;
                    }
                    line(output, "354 start data");
                    StringBuilder message = new StringBuilder();
                    String row;
                    while ((row = input.readLine()) != null && !row.equals("."))
                        message.append(row).append("\r\n");
                    if (row == null) return;
                    messages.add(message.toString());
                    if (mode == Mode.DROP_FINAL) return;
                    if (mode == Mode.SLOW_FINAL) {
                        pause();
                        return;
                    }
                    line(
                            output,
                            mode == Mode.FINAL_451
                                    ? "451 not accepted"
                                    : mode == Mode.FINAL_550 ? "550 not accepted" : "250 accepted");
                } else if (command.equals("QUIT")) {
                    line(output, "221 goodbye");
                    return;
                } else line(output, "250 reset");
            }
        }

        private static void line(OutputStream output, String value) throws IOException {
            output.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static void pause() {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            for (Socket socket : sockets) socket.close();
            worker.interrupt();
            worker.join(2000);
        }
    }
}

package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailMessageTest {
    private static final String FROM = "bci@example.invalid";
    private static final String TO = "triage@example.invalid";

    @Test
    void initialAndUpdateHaveUsableUtf8AlternativesAndRootThreadHeaders() throws Exception {
        UUID delivery = UUID.randomUUID();
        var root = EmailMessage.prepare(
                delivery,
                FROM,
                TO,
                "[BCI] Synthetic — regression",
                "Exact failure → check first",
                "<p>Exact failure → check first</p>",
                null,
                List.of(),
                1700000000000L);
        MimeMessage message = EmailMessage.decode(Session.getInstance(new Properties()), root.mimeBase64(), TO);
        assertEquals(EmailMessage.messageId(delivery, FROM), message.getMessageID());
        assertEquals("[BCI] Synthetic — regression", message.getSubject());
        assertNull(message.getHeader("References"));
        assertNull(message.getHeader("In-Reply-To"));
        assertEquals(1, message.getAllRecipients().length);
        Multipart parts = (Multipart) message.getContent();
        assertEquals(2, parts.getCount());
        assertTrue(parts.getBodyPart(0).getContentType().toLowerCase().contains("charset=utf-8"));
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(parts.getBodyPart(1).isMimeType("text/html"));
        assertEquals("Exact failure → check first", parts.getBodyPart(0).getContent());
        var update = EmailMessage.prepare(
                UUID.randomUUID(),
                FROM,
                TO,
                "Re: " + message.getSubject(),
                "Recovered; fix unknown",
                "<p>Recovered; fix unknown</p>",
                root.messageId(),
                List.of(root.messageId()),
                1700000010000L);
        MimeMessage reply = EmailMessage.decode(Session.getInstance(new Properties()), update.mimeBase64(), TO);
        assertEquals(root.messageId(), reply.getHeader("In-Reply-To", null));
        assertEquals(root.messageId(), reply.getHeader("References", null));
    }

    @Test
    void frozenBytesAndOpaqueIdSurviveReloadWithoutSaveChanges() throws Exception {
        UUID delivery = UUID.randomUUID();
        var prepared = EmailMessage.prepare(
                delivery, FROM, TO, "[BCI] Synthetic", "failure", "<p>failure</p>", null, List.of(), 1700000000000L);
        byte[] frozen = Base64.getDecoder().decode(prepared.mimeBase64());
        MimeMessage reloaded = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(frozen));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        reloaded.writeTo(output);
        assertArrayEquals(frozen, output.toByteArray());
        assertEquals(prepared.messageId(), reloaded.getMessageID());
        assertTrue(prepared.messageId().matches("<bci\\.[a-f0-9-]+@example\\.invalid>"));
        assertFalse(prepared.messageId().contains("Synthetic"));
    }

    @Test
    void referencesRetainRootAndOnlyMostRecentNineteenDistinctIds() throws Exception {
        String root = EmailMessage.messageId(UUID.randomUUID(), FROM);
        List<String> references = new ArrayList<>();
        references.add(root);
        for (int n = 0; n < 30; n++) references.add(EmailMessage.messageId(UUID.randomUUID(), FROM));
        var prepared = EmailMessage.prepare(
                UUID.randomUUID(), FROM, TO, "Re: Synthetic", "update", "<p>update</p>", root, references, 1000);
        MimeMessage message = EmailMessage.decode(Session.getInstance(new Properties()), prepared.mimeBase64(), TO);
        String[] actual = message.getHeader("References", null).split("\\s+");
        assertEquals(20, actual.length);
        assertEquals(root, actual[0]);
        assertEquals(references.get(12), actual[1]);
        assertEquals(references.get(30), actual[19]);
    }

    @Test
    void rejectsHeaderInjectionListsGroupsAndDisplayNames() {
        for (String invalid : List.of(
                "a@example.invalid\r\nBcc: other@example.invalid",
                "a@example.invalid,b@example.invalid",
                "Team <a@example.invalid>",
                "team:a@example.invalid;",
                "a@example.invalid\n"))
            assertThrows(IllegalArgumentException.class, () -> EmailMessage.address(invalid));
        assertThrows(
                IllegalArgumentException.class,
                () -> EmailMessage.prepare(
                        UUID.randomUUID(),
                        FROM,
                        TO,
                        "subject\r\nBcc:other@example.invalid",
                        "ok",
                        "ok",
                        null,
                        List.of(),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> EmailMessage.prepare(
                        UUID.randomUUID(), FROM, TO, "ok", "ok", "ok", "<job/private@example.invalid>", List.of(), 0));
    }

    @Test
    void secretDetectionMatchesEscapingAndRendererNormalization() {
        String password = "synthetic<secret>&\"'";
        assertTrue(EmailMessage.containsSecret("<p>synthetic&lt;secret&gt;&amp;&quot;&#39;</p>", password));
        assertTrue(EmailMessage.containsSecret("synthetic‹secret›&\"'", password));
        assertTrue(EmailMessage.containsSecret("cafésecret", "cafe\u0301\u202esecret"));
        assertTrue(EmailMessage.containsSecret("syntheticsecret", "synthetic\u001b[31msecret"));
        assertTrue(EmailMessage.containsSecret("secret", "  secret  "));
        assertFalse(EmailMessage.containsSecret("safe unrelated text", password));
        assertFalse(EmailMessage.containsSecret("safe", ""));
    }

    @Test
    void boundsEncodedMimeAndRejectsCrossRecipientReplay() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> EmailMessage.prepare(
                        UUID.randomUUID(),
                        FROM,
                        TO,
                        "Synthetic",
                        "😀".repeat(8192),
                        "界".repeat(32768),
                        null,
                        List.of(),
                        0));
        var prepared =
                EmailMessage.prepare(UUID.randomUUID(), FROM, TO, "Synthetic", "ok", "<p>ok</p>", null, List.of(), 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> EmailMessage.decode(
                        Session.getInstance(new Properties()), prepared.mimeBase64(), "other@example.invalid"));
        assertTrue(Base64.getDecoder().decode(prepared.mimeBase64()).length <= EmailMessage.MAX_BYTES);
    }
}

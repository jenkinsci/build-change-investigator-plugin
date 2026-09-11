package io.jenkins.plugins.changeinvestigator.notification.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Frozen single-recipient MIME, assembled before the durable submission lease. */
public final class EmailMessage {
    public static final int MAX_BYTES = 65536;
    public static final int MAX_BASE64 = 87384;

    private EmailMessage() {}

    /** Recognizes the same normalization and escaping used by the notification renderer. */
    public static boolean containsSecret(String content, String password) {
        if (content == null || password == null || password.isEmpty()) return false;
        String normalized = java.text.Normalizer.normalize(password, java.text.Normalizer.Form.NFC)
                .replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder clean = new StringBuilder();
        normalized.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t' || (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT))
                clean.appendCodePoint(c);
        });
        for (String value : List.of(
                password,
                normalized,
                clean.toString(),
                clean.toString().trim(),
                clean.toString().replace("://", "：//"))) {
            if (value.isEmpty()) continue;
            String escaped = value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
            if (content.contains(value)
                    || content.contains(escaped)
                    || content.contains(value.replace('<', '‹').replace('>', '›'))) return true;
        }
        return false;
    }

    public record Prepared(String messageId, String mimeBase64) {}

    public static Prepared prepare(
            UUID deliveryId,
            String from,
            String recipient,
            String subject,
            String plainText,
            String html,
            String rootMessageId,
            List<String> recentReferences,
            long createdAt) {
        String id = messageId(deliveryId, from);
        address(recipient);
        header(subject, 240);
        if (plainText == null
                || html == null
                || plainText.length() > 16384
                || html.length() > 32768
                || createdAt < 0
                || recentReferences == null
                || recentReferences.size() > 100) {
            throw new IllegalArgumentException("Invalid email content bounds");
        }
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(address(from), true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(address(recipient), true));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setSentDate(new Date(createdAt));
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart text = new MimeBodyPart();
            text.setText(plainText, StandardCharsets.UTF_8.name());
            text.setHeader("Content-Transfer-Encoding", "base64");
            alternative.addBodyPart(text);
            MimeBodyPart rich = new MimeBodyPart();
            rich.setText(html, StandardCharsets.UTF_8.name(), "html");
            rich.setHeader("Content-Transfer-Encoding", "base64");
            alternative.addBodyPart(rich);
            message.setContent(alternative);
            if (rootMessageId != null) {
                validMessageId(rootMessageId);
                List<String> chain = new ArrayList<>();
                for (String reference : recentReferences) {
                    validMessageId(reference);
                    if (!reference.equals(rootMessageId) && !reference.equals(id)) {
                        chain.remove(reference);
                        chain.add(reference);
                    }
                }
                if (chain.size() > 19) chain = new ArrayList<>(chain.subList(chain.size() - 19, chain.size()));
                chain.add(0, rootMessageId);
                message.setHeader("In-Reply-To", rootMessageId);
                message.setHeader("References", jakarta.mail.internet.MimeUtility.fold(12, String.join(" ", chain)));
            } else if (!recentReferences.isEmpty()) {
                throw new IllegalArgumentException("References require a root");
            }
            message.saveChanges();
            // saveChanges creates a temporary provider ID; replace it before freezing any bytes.
            message.setHeader("Message-ID", id);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Email exceeds MIME limit");
            return new Prepared(id, Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (MessagingException | java.io.IOException e) {
            throw new IllegalArgumentException("Email MIME could not be prepared");
        }
    }

    public static String messageId(UUID deliveryId, String from) {
        if (deliveryId == null) throw new IllegalArgumentException("Missing delivery identity");
        String sender = address(from);
        return "<bci." + deliveryId + "@"
                + sender.substring(sender.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT) + ">";
    }

    /** An approved destination is one bare mailbox, never an address list or group. */
    public static String address(String value) {
        header(value, 254);
        if (!value.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"))
            throw new IllegalArgumentException("Use one bare email address");
        String local = value.substring(0, value.lastIndexOf('@'));
        String domain = value.substring(value.lastIndexOf('@') + 1);
        if (local.length() > 64 || local.startsWith(".") || local.endsWith(".") || local.contains(".."))
            throw new IllegalArgumentException("Invalid mailbox local part");
        for (String label : domain.split("\\.", -1))
            if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
                throw new IllegalArgumentException("Invalid mailbox domain");
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (address.isGroup() || address.getPersonal() != null || !value.equals(address.getAddress()))
                throw new IllegalArgumentException("Use one bare email address");
            return value;
        } catch (MessagingException e) {
            throw new IllegalArgumentException("Invalid email address");
        }
    }

    public static void header(String value, int limit) {
        if (value == null
                || value.isBlank()
                || value.length() > limit * 2
                || value.codePointCount(0, value.length()) > limit
                || value.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("Invalid email header");
    }

    public static void validMessageId(String value) {
        if (value == null
                || value.length() > 320
                || !value.matches(
                        "<bci\\.[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}@[A-Za-z0-9][A-Za-z0-9.-]*>"))
            throw new IllegalArgumentException("Invalid opaque message identity");
    }

    static MimeMessage decode(Session session, String base64, String recipient) throws MessagingException {
        address(recipient);
        if (base64 == null || base64.length() > MAX_BASE64) throw new IllegalArgumentException("Invalid MIME size");
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid MIME size");
        MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(bytes));
        if (message.getAllRecipients() == null
                || message.getAllRecipients().length != 1
                || !recipient.equals(((InternetAddress) message.getAllRecipients()[0]).getAddress())
                || message.getRecipients(Message.RecipientType.TO) == null
                || message.getHeader("Cc") != null
                || message.getHeader("Bcc") != null
                || message.getFrom() == null
                || message.getFrom().length != 1
                || message.getHeader("Sender") != null
                || message.getHeader("Reply-To") != null
                || !message.isMimeType("multipart/alternative")) {
            throw new IllegalArgumentException("Invalid single-recipient MIME");
        }
        address(((InternetAddress) message.getFrom()[0]).getAddress());
        header(message.getSubject(), 240);
        validMessageId(message.getMessageID());
        return message;
    }
}

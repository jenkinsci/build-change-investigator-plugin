package io.jenkins.plugins.changeinvestigator.notification.identity;

import io.jenkins.plugins.changeinvestigator.evidence.SecretRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Versioned, bounded canonical primitives. No raw diagnostic is hashed before validation. */
public final class IdentityCanonicalizer {
    private static final Pattern ANSI =
            Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\))");

    private IdentityCanonicalizer() {}

    public static String normalize(String value) {
        if (value == null) return null;
        if (value.length() > 8192) throw new IllegalArgumentException("Identity input exceeds bound");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("MISSING_CONTEXT");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("MISSING_CONTEXT");
        }
        String clean =
                ANSI.matcher(value.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        StringBuilder result = new StringBuilder();
        clean.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t') result.append(' ');
            else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT)
                result.appendCodePoint(cp);
        });
        return Normalizer.normalize(result.toString().trim(), Normalizer.Form.NFC);
    }

    public static String critical(String value, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_CONTEXT");
        if (value.length() > max) throw new IllegalArgumentException("TRUNCATED_CRITICAL_FIELD");
        String normalized = normalize(value);
        if (!normalized.equals(
                io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(normalized, max)))
            throw new IllegalArgumentException("REDACTED_CRITICAL_FIELD");
        String redacted = SecretRedactor.redact(SecretRedactor.redactMultiline(normalized));
        if (!redacted.equals(normalized)
                || normalized.toUpperCase(java.util.Locale.ROOT).contains("REDACTED")
                || normalized.contains("[TRUNCATED")
                || normalized.contains("[LINE TRUNCATED")) {
            throw new IllegalArgumentException("REDACTED_CRITICAL_FIELD");
        }
        if (normalized.isBlank()) throw new IllegalArgumentException("MISSING_CONTEXT");
        return normalized;
    }

    /** Canonical JSON for a sorted, flat string/null map; escaping prevents delimiter aliases. */
    public static String canonical(Map<String, String> fields) {
        return canonicalOrdered(new TreeMap<>(fields));
    }

    public static String canonicalOrdered(Map<String, String> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (var entry : fields.entrySet()) {
            if (!first) out.append(',');
            first = false;
            quote(out, entry.getKey());
            out.append(':');
            if (entry.getValue() == null) out.append("null");
            else quote(out, entry.getValue());
        }
        return out.append('}').toString();
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    public static String digest(String canonical) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

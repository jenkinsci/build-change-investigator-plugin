package io.jenkins.plugins.changeinvestigator.notification.event;

import io.jenkins.plugins.changeinvestigator.evidence.SecretRedactor;
import java.net.URI;
import java.text.Normalizer;

/** Data minimization before notification snapshots enter durable storage. */
public final class SafeContent {
    private SafeContent() {}

    public static String text(String input, int limit) {
        if (input == null) return null;
        if (input.length() > 8192) return "[Content withheld: input limit]";
        String value = Normalizer.normalize(input, Normalizer.Form.NFC)
                .replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder clean = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t' || (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT))
                clean.appendCodePoint(c);
        });
        value = SecretRedactor.redactMultiline(clean.toString());
        StringBuilder redacted = new StringBuilder();
        for (String line : value.split("\n", -1)) {
            if (!redacted.isEmpty()) redacted.append('\n');
            redacted.append(SecretRedactor.redact(line)
                    .replaceAll("(?i)(https?://)[^/\\s@]+@", "$1[REDACTED]@")
                    .replaceAll("(?i)([?&][a-z0-9_.-]+=)[^&\\s#]+", "$1[REDACTED]")
                    .replaceAll("\\b[A-Z][A-Z0-9_]{2,}=['\"]?[^\\s'\"]+", "[Environment assignment withheld]"));
        }
        value = redacted.toString();
        int count = value.codePointCount(0, value.length());
        return count > limit ? value.substring(0, value.offsetByCodePoints(0, Math.max(0, limit - 1))) + "…" : value;
    }

    /** Only caller-approved navigation roots belong here; arbitrary log URLs are never accepted. */
    public static String navigation(String root, String relative) {
        if (root == null || relative == null || root.length() > 2048 || relative.length() > 2048) return null;
        try {
            URI base = URI.create(root);
            URI reference = URI.create(relative);
            String path = reference.getPath();
            if (reference.isAbsolute()
                    || reference.getRawAuthority() != null
                    || reference.getRawQuery() != null
                    || reference.getRawFragment() != null
                    || path == null
                    || path.startsWith("/")
                    || path.indexOf('\\') >= 0
                    || path.codePoints().anyMatch(Character::isISOControl)) return null;
            for (String segment : path.split("/", -1)) {
                if (segment.equals("..") || segment.equals(".") || segment.indexOf('%') >= 0) return null;
            }
            URI target = base.resolve(reference);
            String basePath = base.getPath();
            if (!java.util.Set.of("http", "https").contains(base.getScheme())
                    || base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getQuery() != null
                    || base.getFragment() != null
                    || basePath == null
                    || !basePath.endsWith("/")
                    || !java.util.Objects.equals(base.getScheme(), target.getScheme())
                    || !java.util.Objects.equals(base.getAuthority(), target.getAuthority())
                    || !target.getPath().startsWith(basePath)
                    || target.getQuery() != null
                    || target.getFragment() != null
                    || target.toASCIIString().length() > 2048) return null;
            return target.toASCIIString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

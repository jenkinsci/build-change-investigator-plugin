package io.jenkins.plugins.changeinvestigator.evidence;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort redaction of common secret patterns from log text before it is ever
 * included in an evidence bundle or sent to an AI provider.
 *
 * <p><b>This redaction is not, and cannot be, exhaustive or guaranteed.</b> It catches
 * well-known, high-signal patterns (bearer tokens, common cloud provider key formats,
 * private key blocks, obvious "password=" / "token=" assignments). Administrators are
 * responsible for reviewing what data reaches an external AI provider before enabling
 * it - see SECURITY.md.
 */
public final class SecretRedactor {

    private static final String REDACTED = "[REDACTED]";

    private record RedactionRule(Pattern pattern, String replacement) {
    }

    private static final List<RedactionRule> RULES = List.of(
            // Authorization: Bearer <token>
            new RedactionRule(
                    Pattern.compile("(?i)(authorization\\s*:\\s*bearer\\s+)[A-Za-z0-9\\-_.=]+"),
                    "$1" + REDACTED),
            // key=value / key: value assignments for common secret-ish variable names
            new RedactionRule(
                    Pattern.compile(
                            "(?i)((?:api[_-]?key|apikey|api[_-]?token|access[_-]?token|secret[_-]?key|"
                                    + "client[_-]?secret|password|passwd|pwd|auth[_-]?token|private[_-]?key)"
                                    + "\\s*[:=]\\s*)(['\"]?)[^\\s'\"]{3,}\\2"),
                    "$1$2" + REDACTED + "$2"),
            // Generic bare "token"/"secret" assignment without a qualifying prefix word
            new RedactionRule(
                    Pattern.compile("(?i)\\b(token|secret)\\s*[:=]\\s*(['\"]?)[A-Za-z0-9\\-_./+=]{8,}\\2"),
                    "$1=" + REDACTED),
            // AWS access key id
            new RedactionRule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), REDACTED + "-AWS-ACCESS-KEY"),
            // AWS-style secret access key assignment (40 base64-ish chars)
            new RedactionRule(
                    Pattern.compile("(?i)(aws_secret_access_key\\s*[:=]\\s*)['\"]?[A-Za-z0-9/+=]{40}['\"]?"),
                    "$1" + REDACTED),
            // JWT (three base64url segments separated by dots)
            new RedactionRule(
                    Pattern.compile("\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
                    REDACTED + "-JWT"),
            // GitHub tokens (ghp_, gho_, ghu_, ghs_, ghr_)
            new RedactionRule(Pattern.compile("\\bgh[oprsu]_[A-Za-z0-9]{20,}\\b"), REDACTED + "-GITHUB-TOKEN"),
            // Slack tokens
            new RedactionRule(Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"), REDACTED + "-SLACK-TOKEN"),
            // A lone PEM header/footer line (the multi-line body is handled by redactMultiline)
            new RedactionRule(
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), REDACTED + "-PRIVATE-KEY-BLOCK"));

    private static final Pattern PRIVATE_KEY_BLOCK =
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private SecretRedactor() {
    }

    /** Applies all redaction rules to a single line and returns the (possibly modified) result. */
    public static String redact(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String result = line;
        for (RedactionRule rule : RULES) {
            Matcher m = rule.pattern().matcher(result);
            result = m.replaceAll(rule.replacement());
        }
        return result;
    }

    /**
     * Applies redactions that span multiple lines (currently: PEM private key blocks) to a
     * full block of already-joined text. Callers should still call {@link #redact(String)}
     * per line for the single-line patterns.
     */
    public static String redactMultiline(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return PRIVATE_KEY_BLOCK.matcher(text).replaceAll(REDACTED + "-PRIVATE-KEY-BLOCK");
    }
}

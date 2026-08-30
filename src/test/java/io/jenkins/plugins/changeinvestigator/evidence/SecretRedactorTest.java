package io.jenkins.plugins.changeinvestigator.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    @Test
    void redactsBearerToken() {
        String redacted = SecretRedactor.redact("Authorization: Bearer sk-abcDEF123456.xyz");
        assertTrue(redacted.contains("[REDACTED]"), redacted);
        assertFalse(redacted.contains("sk-abcDEF123456"), redacted);
    }

    @Test
    void redactsPasswordAssignment() {
        String redacted = SecretRedactor.redact("password=SuperSecret123!");
        assertFalse(redacted.contains("SuperSecret123"), redacted);
    }

    @Test
    void redactsApiKeyAssignmentWithQuotes() {
        String redacted = SecretRedactor.redact("api_key: \"abcdef0123456789\"");
        assertFalse(redacted.contains("abcdef0123456789"), redacted);
    }

    @Test
    void redactsAwsAccessKeyId() {
        String redacted = SecretRedactor.redact("aws access key is AKIAABCDEFGHIJKLMNOP in use");
        assertFalse(redacted.contains("AKIAABCDEFGHIJKLMNOP"), redacted);
        assertTrue(redacted.contains("AWS-ACCESS-KEY"), redacted);
    }

    @Test
    void redactsGitHubToken() {
        String redacted = SecretRedactor.redact("token used: ghp_1234567890abcdefghij1234567890abcd");
        assertFalse(redacted.contains("ghp_1234567890"), redacted);
    }

    @Test
    void redactsJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dQw4w9WgXcQ_abcdefghijklmno";
        String redacted = SecretRedactor.redact("token=" + jwt);
        assertFalse(redacted.contains(jwt), redacted);
    }

    @Test
    void redactsPrivateKeyBlockAcrossLines() {
        String text = "before\n-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAK...\n-----END RSA PRIVATE KEY-----\nafter";
        String redacted = SecretRedactor.redactMultiline(text);
        assertFalse(redacted.contains("MIIBOgIBAAJBAK"), redacted);
        assertTrue(redacted.contains("before"), redacted);
        assertTrue(redacted.contains("after"), redacted);
    }

    @Test
    void leavesOrdinaryLogLinesUntouched() {
        String line = "[INFO] Building jar: target/app-1.0.jar";
        assertEquals(line, SecretRedactor.redact(line));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals(null, SecretRedactor.redact(null));
        assertEquals("", SecretRedactor.redact(""));
    }
}

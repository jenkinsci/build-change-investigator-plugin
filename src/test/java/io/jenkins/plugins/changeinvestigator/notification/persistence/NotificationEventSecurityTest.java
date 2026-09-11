package io.jenkins.plugins.changeinvestigator.notification.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationEventSecurityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode fixtures() throws Exception {
        try (var stream = getClass().getResourceAsStream("../events-v1.json")) {
            return (ObjectNode) mapper.readTree(stream);
        }
    }

    @Test
    void allFourContractFixturesRoundTripWithoutMutation() throws Exception {
        var fixtures = fixtures();
        for (String name : List.of("specific", "unresolved", "recovered", "confirmed")) {
            var source = (ObjectNode) fixtures.get(name);
            assertEquals(source, new NotificationEvent(source.toString()).snapshot());
            assertDoesNotThrow(() -> NotificationEvent.freeze(source));
        }
    }

    @Test
    void rejectsTrailingDuplicateUnknownAndOversizeJson() throws Exception {
        String valid = fixtures().get("specific").toString();
        for (String suffix : List.of(" {}", " []", " true", " null", " garbage"))
            assertThrows(IllegalArgumentException.class, () -> new NotificationEvent(valid + suffix));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationEvent(valid.replaceFirst("\\{", "{\"eventVersion\":1,")));
        var unknown = (ObjectNode) fixtures().get("specific");
        unknown.put("unexpected", "value");
        assertThrows(IllegalArgumentException.class, () -> new NotificationEvent(unknown.toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationEvent(" ".repeat(NotificationEvent.MAX_BYTES + 1)));
    }

    @Test
    void rejectsArbitraryObjectsBinaryDeepAndLargeProjectionBeforeSerialization() throws Exception {
        var event = (ObjectNode) fixtures().get("specific");
        event.putPOJO("jobLabel", new Object());
        assertThrows(IllegalArgumentException.class, () -> NotificationEvent.freeze(event));
        event.put("jobLabel", new byte[] {1, 2});
        assertThrows(IllegalArgumentException.class, () -> NotificationEvent.freeze(event));
        event.put("jobLabel", "x".repeat(8193));
        assertThrows(IllegalArgumentException.class, () -> NotificationEvent.freeze(event));
        var deep = mapper.createObjectNode();
        var cursor = deep;
        for (int i = 0; i < 26; i++) cursor = cursor.putObject("child");
        assertThrows(IllegalArgumentException.class, () -> NotificationEvent.freeze(deep));
        var many = mapper.createObjectNode();
        var entries = many.putArray("entries");
        for (int i = 0; i < 4001; i++) entries.add(i);
        assertThrows(IllegalArgumentException.class, () -> NotificationEvent.freeze(many));
    }

    @Test
    void knownSyntheticSecretPatternsAreRedactedBeforePersistence() throws Exception {
        List<String> secrets = List.of(
                "Authorization: Bearer syntheticCredential",
                "password=syntheticCredential",
                "token=syntheticCredential",
                "AKIA" + "A".repeat(16),
                "aws_secret_access_key=" + "A".repeat(40),
                "ey" + "A".repeat(12) + "." + "B".repeat(12) + "." + "C".repeat(12),
                "ghp_" + "A".repeat(24),
                "xoxb-" + "A".repeat(15),
                "-----BEGIN PRIVATE KEY-----\nsyntheticCredential\n-----END PRIVATE KEY-----",
                "https://user:syntheticCredential@example.invalid/a",
                "https://example.invalid/?key=syntheticCredential",
                "DEMO_ENV=syntheticCredential");
        for (String secret : secrets) {
            var event = (ObjectNode) fixtures().get("specific");
            event.put("jobLabel", secret);
            assertThrows(IllegalArgumentException.class, () -> new NotificationEvent(event.toString()));
            var frozen = NotificationEvent.freeze(event);
            assertNotEquals(secret, frozen.snapshot().path("jobLabel").asText());
            assertFalse(frozen.json().contains("syntheticCredential"));
        }
        assertEquals("safe", SafeContent.text("\u001b[31msafe\u001b[0m\u202e\u0000", 100));
    }
}

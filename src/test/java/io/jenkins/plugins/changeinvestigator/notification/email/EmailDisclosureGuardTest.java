package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.NotificationObservation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmailDisclosureGuardTest {
    @TempDir
    Path directory;

    private final UUID job = UUID.randomUUID();
    private final List<NotificationEngine.DestinationPolicy> destinations = List.of(
            new NotificationEngine.DestinationPolicy(UUID.randomUUID(), 1, true, false, "EMAIL"),
            new NotificationEngine.DestinationPolicy(UUID.randomUUID(), 1, true, false, "SLACK"));
    private NotificationEngine engine;

    @BeforeEach
    void setup() throws Exception {
        engine = new NotificationEngine(directory, job);
        engine.arm(0);
    }

    private static Predicate<String> guard(String password) {
        return value -> EmailMessage.containsSecret(value, password);
    }

    private static NotificationObservation diagnostic(NotificationObservation input, String value) {
        ObjectNode display = input.display();
        ((ObjectNode) display.get("failureSummary")).put("diagnostic", value);
        return new NotificationObservation(
                input.observationId(),
                input.runId(),
                input.order(),
                input.result(),
                input.actualEvidence(),
                input.completeHistory(),
                input.context(),
                input.signature(),
                input.affectedCheck(),
                input.facts(),
                input.coverage(),
                input.recoveryChanges(),
                display);
    }

    @Test
    void echoedPasswordIsWithheldBeforeAnyCaseOrMixedTransportIntentIsSaved() throws Exception {
        String password = "unpatterned<secret>&\"'";
        var input = diagnostic(EmailFixtures.input(job, 1, "commit-a", false), "Failure: " + password);
        IOException rejected = assertThrows(
                IOException.class, () -> engine.ingestConfigured(input, destinations, 1000, guard(password)));
        assertEquals("Notification content withheld by disclosure policy", rejected.getMessage());
        assertNull(rejected.getCause());
        assertTrue(engine.records().isEmpty());
        assertTrue(new NotificationEngine(directory, job).records().isEmpty());
    }

    @Test
    void nondisplayMaterialIdentityIsAlsoCheckedBeforePersistence() throws Exception {
        String password = "unpatternedSmtpSecret";
        var input = EmailFixtures.input(job, 1, password, false);
        assertFalse(input.display().toString().contains(password));
        assertThrows(IOException.class, () -> engine.ingestConfigured(input, destinations, 1000, guard(password)));
        assertTrue(engine.records().isEmpty());
    }

    @Test
    void cleanObservationStillCreatesBothApprovedTransportIntents() throws Exception {
        var saved = engine.ingestConfigured(
                EmailFixtures.input(job, 1, "commit-a", false), destinations, 1000, guard("unpatternedSmtpSecret"));
        assertNotNull(saved);
        assertEquals(2, saved.destinations().size());
        assertEquals(
                2,
                saved.destinations().stream().mapToInt(d -> d.intents().size()).sum());
        assertEquals(
                List.of("EMAIL", "SLACK"),
                saved.destinations().stream().map(d -> d.transport()).sorted().toList());
        assertEquals(List.of(saved), new NotificationEngine(directory, job).records());
    }

    @Test
    void rejectedUpdateLeavesExistingAggregateAndIntentsUnchanged() throws Exception {
        var saved = engine.ingestConfigured(EmailFixtures.input(job, 1, "commit-a", false), destinations, 1000);
        String password = "unpatternedSmtpSecret";
        assertThrows(
                IOException.class,
                () -> engine.ingestConfigured(
                        EmailFixtures.input(job, 2, password, false), destinations, 2000, guard(password)));
        assertEquals(List.of(saved), new NotificationEngine(directory, job).records());
    }

    @Test
    void currentGuardChecksCopiedPriorFailureAfterJsonEnvelopeDecoding() throws Exception {
        String password = "unpatterned<secret>&\"'";
        var prior = diagnostic(EmailFixtures.input(job, 1, "commit-a", false), "Prior failure: " + password);
        // Represents historical evidence captured before this credential became known to the integration.
        var saved = engine.ingestConfigured(prior, destinations, 1000);
        var recovery = EmailFixtures.input(job, 2, "commit-a", true);
        assertFalse(recovery.display().toString().contains("unpatterned"));
        assertThrows(IOException.class, () -> engine.ingestConfigured(recovery, destinations, 2000, guard(password)));
        assertEquals(List.of(saved), new NotificationEngine(directory, job).records());
    }

    @Test
    void predicateFailureCannotExposeCredentialOrPersistPartialCase() throws Exception {
        var input = EmailFixtures.input(job, 1, "commit-a", false);
        IOException failure = assertThrows(
                IOException.class,
                () -> engine.ingestConfigured(input, destinations, 1000, value -> {
                    throw new IllegalStateException("unpatternedSmtpSecret");
                }));
        assertEquals("Notification content withheld by disclosure policy", failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(engine.records().isEmpty());
    }
}

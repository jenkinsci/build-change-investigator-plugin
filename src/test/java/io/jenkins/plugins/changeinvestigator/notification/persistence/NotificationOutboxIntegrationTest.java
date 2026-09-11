package io.jenkins.plugins.changeinvestigator.notification.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationOutboxIntegrationTest {
    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptedButReceiptLostPersistsUnknownAndNeverAutomaticallyResends() throws Exception {
        UUID job = UUID.randomUUID(), caseId = UUID.randomUUID();
        var store = new NotificationStore(root, job);
        ObjectNode event;
        try (var stream = getClass().getResourceAsStream("../events-v1.json")) {
            event = new NotificationEvent(
                            mapper.readTree(stream).get("specific").toString())
                    .snapshot();
        }
        var queued = OutboxIntent.queued(
                caseId, UUID.fromString(event.path("eventId").asText()), UUID.randomUUID(), 1, 1, 100);
        var initial = store.update(caseId, 0, ignored -> {
            var aggregate = mapper.createObjectNode();
            aggregate.set("event", event);
            aggregate.putObject("policy").put("initialAccepted", false);
            aggregate.set("intent", mapper.valueToTree(queued));
            return aggregate;
        });
        var leased = queued.lease(100, 1000);
        var pending = store.update(caseId, initial.revision(), aggregate -> {
            aggregate.set("intent", mapper.valueToTree(leased));
            return aggregate;
        });
        var fake = new FakeDestination();
        fake.accept(store, caseId, leased);
        assertEquals(1, fake.accepted);
        // Simulate process loss after remote acceptance, before receipt persistence.
        var restarted = new NotificationStore(root, job);
        var loaded = restarted.load(caseId).orElseThrow();
        var recovered = mapper.treeToValue(loaded.aggregate().get("intent"), OutboxIntent.class)
                .recoverExpiredLease(1101);
        restarted.update(caseId, pending.revision(), aggregate -> {
            aggregate.set("intent", mapper.valueToTree(recovered));
            return aggregate;
        });
        assertEquals(OutboxIntent.State.UNKNOWN_OUTCOME, recovered.state());
        assertThrows(IllegalStateException.class, () -> recovered.lease(1200, 1000));
        assertFalse(restarted
                .load(caseId)
                .orElseThrow()
                .aggregate()
                .path("policy")
                .path("initialAccepted")
                .asBoolean());
        assertEquals(1, fake.accepted);
    }

    @Test
    void acceptedReceiptAndPolicyCommitTogether() throws Exception {
        UUID job = UUID.randomUUID(), caseId = UUID.randomUUID();
        var store = new NotificationStore(root, job);
        var lease = OutboxIntent.queued(caseId, UUID.randomUUID(), UUID.randomUUID(), 1, 1, 100)
                .lease(100, 1000);
        var snapshot = store.update(caseId, 0, ignored -> {
            var aggregate = mapper.createObjectNode();
            aggregate.set("intent", mapper.valueToTree(lease));
            aggregate.putObject("policy").put("initialAccepted", false);
            return aggregate;
        });
        new FakeDestination().accept(store, caseId, lease);
        store.update(caseId, snapshot.revision(), aggregate -> {
            aggregate.set("intent", mapper.valueToTree(lease.accepted(lease.leaseToken(), "fake-receipt")));
            ((ObjectNode) aggregate.get("policy")).put("initialAccepted", true);
            return aggregate;
        });
        var durable =
                new NotificationStore(root, job).load(caseId).orElseThrow().aggregate();
        assertEquals("SENT", durable.path("intent").path("state").asText());
        assertTrue(durable.path("policy").path("initialAccepted").asBoolean());
    }

    private static final class FakeDestination {
        int accepted;

        void accept(NotificationStore store, UUID caseId, OutboxIntent intent) throws Exception {
            var durable = store.load(caseId).orElseThrow().aggregate().path("intent");
            assertEquals("LEASED", durable.path("state").asText());
            assertEquals(
                    intent.leaseToken().toString(), durable.path("leaseToken").asText());
            accepted++;
        }
    }
}

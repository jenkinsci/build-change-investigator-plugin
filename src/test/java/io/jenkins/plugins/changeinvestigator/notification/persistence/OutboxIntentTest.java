package io.jenkins.plugins.changeinvestigator.notification.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxIntentTest {
    @TempDir
    Path root;

    @Test
    void stableIdentityChangesOnlyForSemanticDestinationOrRenderer() {
        UUID c = UUID.randomUUID(), e = UUID.randomUUID(), d = UUID.randomUUID();
        var a = OutboxIntent.queued(c, e, d, 1, 1, 1);
        assertEquals(a.deliveryId(), OutboxIntent.queued(c, e, d, 1, 1, 200).deliveryId());
        assertNotEquals(a.deliveryId(), OutboxIntent.queued(c, e, d, 2, 1, 1).deliveryId());
        assertEquals(
                OutboxIntent.State.CANCELLED,
                a.cancelForDestinationGeneration(2).state());
    }

    @Test
    void fakeAcceptanceAndDefiniteRejectionCannotChangeImmutableIdentity() {
        var queued = OutboxIntent.queued(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 100);
        var leased = queued.lease(100, 1000);
        assertThrows(IllegalStateException.class, () -> leased.accepted(UUID.randomUUID(), "receipt"));
        var retry = leased.rejected(leased.leaseToken(), true, 200);
        assertEquals(OutboxIntent.State.RETRY_WAIT, retry.state());
        var second = retry.lease(200, 1000);
        var sent = second.accepted(second.leaseToken(), "fake-receipt");
        assertEquals(queued.deliveryId(), sent.deliveryId());
        assertEquals(OutboxIntent.State.SENT, sent.state());
        assertThrows(IllegalStateException.class, () -> sent.lease(300, 1000));
    }

    @Test
    void admissionReservesTerminalCapacityAndSurvivesRestart() throws Exception {
        UUID controller = UUID.randomUUID(), destination = UUID.randomUUID();
        var budget = new AdmissionBudget(root, controller);
        UUID first = UUID.randomUUID();
        assertTrue(budget.reserve(first, destination, false, 0, 100));
        assertTrue(new AdmissionBudget(root, controller).reserve(first, destination, false, 0, 100));
        assertFalse(budget.reserve(UUID.randomUUID(), destination, false, AdmissionBudget.MAX_STORE_BYTES * 4 / 5, 1));
        assertTrue(budget.reserve(UUID.randomUUID(), destination, true, AdmissionBudget.MAX_STORE_BYTES * 4 / 5, 1));
        assertFalse(budget.reserve(UUID.randomUUID(), destination, true, AdmissionBudget.MAX_STORE_BYTES, 1));
        budget.release(first);
        assertTrue(budget.reserve(first, destination, false, 0, 100));
    }
}

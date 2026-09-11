package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmailSubmissionBudgetTest {
    @TempDir
    Path directory;

    private final UUID controller = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();

    @Test
    void thirtyNewSubmissionsPerMinutePersistAcrossReload() throws Exception {
        var budget = new EmailSubmissionBudget(directory, controller);
        for (int n = 0; n < 30; n++)
            assertTrue(budget.reserve(destination, UUID.randomUUID(), 1000 + n * 1000L)
                    .allowed());
        budget = new EmailSubmissionBudget(directory, controller);
        var deferred = budget.reserve(destination, UUID.randomUUID(), 31000);
        assertFalse(deferred.allowed());
        assertEquals(61000, deferred.retryAt());
        assertTrue(budget.reserve(destination, UUID.randomUUID(), deferred.retryAt())
                .allowed());
    }

    @Test
    void dailyLimitExpiresAtRollingDeadlineWithoutBlockingOtherDestination() throws Exception {
        var budget = new EmailSubmissionBudget(directory, controller);
        for (int n = 0; n < 1000; n++)
            assertTrue(budget.reserve(destination, UUID.randomUUID(), 1000 + n * 3000L)
                    .allowed());
        budget = new EmailSubmissionBudget(directory, controller);
        var deferred = budget.reserve(destination, UUID.randomUUID(), 3001000);
        assertFalse(deferred.allowed());
        assertEquals(86401000, deferred.retryAt());
        assertTrue(budget.reserve(UUID.randomUUID(), UUID.randomUUID(), 3001000).allowed());
        assertTrue(budget.reserve(destination, UUID.randomUUID(), deferred.retryAt())
                .allowed());
    }

    @Test
    void retryIdentityKeepsReservationAndStillObservesSpacing() throws Exception {
        var budget = new EmailSubmissionBudget(directory, controller);
        UUID delivery = UUID.randomUUID();
        assertTrue(budget.reserve(destination, delivery, 1000).allowed());
        assertFalse(budget.reserve(destination, delivery, 1001).allowed());
        for (int n = 1; n < 40; n++)
            assertTrue(budget.reserve(destination, delivery, 1000 + n * 1000L).allowed());
        assertTrue(budget.reserve(destination, UUID.randomUUID(), 41000).allowed());
    }
}

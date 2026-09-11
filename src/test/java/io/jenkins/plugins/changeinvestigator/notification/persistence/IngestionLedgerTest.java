package io.jenkins.plugins.changeinvestigator.notification.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestionLedgerTest {
    @TempDir
    Path root;

    @Test
    void missedCaseSaveRemainsPendingUntilDurableConsumptionAcrossRestart() throws Exception {
        UUID job = UUID.randomUUID(), caseId = UUID.randomUUID();
        var ledger = new IngestionLedger(root, job);
        ledger.arm(10);
        assertEquals(IngestionLedger.Admission.STALE, ledger.register("old:10", 10));
        assertEquals(IngestionLedger.Admission.PENDING, ledger.register("run:11:v1", 11));
        var restarted = new IngestionLedger(root, job);
        assertEquals(1, restarted.pending().size());
        restarted.markConsumed("run:11:v1", caseId, 1);
        assertEquals(IngestionLedger.Admission.CONSUMED, ledger.register("run:11:v1", 11));
        assertTrue(ledger.pending().isEmpty());
    }

    @Test
    void compactedConsumedWatermarkPreventsReplayBeyondTwoHundred() throws Exception {
        UUID job = UUID.randomUUID(), caseId = UUID.randomUUID();
        var ledger = new IngestionLedger(root, job);
        ledger.arm(0);
        for (int n = 1; n <= 202; n++) {
            ledger.register("run:" + n, n);
            ledger.markConsumed("run:" + n, caseId, n);
        }
        assertEquals(IngestionLedger.Admission.STALE, new IngestionLedger(root, job).register("run:1", 1));
        assertEquals(IngestionLedger.Admission.STALE, ledger.register("run:1:v2", 1));
    }

    @Test
    void pendingOverflowNeverDropsTheUnconsumedOldestObservation() throws Exception {
        var ledger = new IngestionLedger(root, UUID.randomUUID());
        assertThrows(IOException.class, ledger::pending);
        ledger.arm(0);
        for (int n = 1; n <= 200; n++) ledger.register("run:" + n, n);
        assertThrows(IOException.class, () -> ledger.register("run:201", 201));
        assertEquals(200, ledger.pending().size());
        assertTrue(ledger.pending().has("run:1"));
    }
}

package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpisodeEngineTest {
    private static final String HEADER =
            "[INFO] --- maven-compiler-plugin:3.13.0:compile (default-compile) @ service ---";
    private static final FailureSignal SIGNAL = FailureSignal.extract(List.of(
            HEADER, "[ERROR] src/main/java/Trade.java:[853,24] cannot find symbol", "symbol: variable missing"));
    private static final ComparableCheck.Check CHECK = ComparableCheck.failed(
            List.of(HEADER, "[ERROR] src/main/java/Trade.java:[853,24] cannot find symbol"), SIGNAL, "builder-v1");

    @Test
    void exactSequenceSurvivesReloadsAndKeepsDistinctThreads(@TempDir Path directory) throws Exception {
        var store = new EpisodeStore(directory.resolve("state.json"));
        var state = store.read();
        state.lastBuild = 224;
        assertEquals("INITIAL", fail(state, 225, "candidate-a"));
        String original = state.active.id;
        EpisodeEngine.sent(state.active, EpisodeEngine.claim(state.active, 0), "225.1");
        assertEquals("NONE", fail(state, 226, "candidate-a"));
        assertEquals("NONE", fail(state, 227, "candidate-a"));
        assertEquals("MATERIAL", fail(state, 228, "candidate-b"));
        EpisodeEngine.sent(state.active, EpisodeEngine.claim(state.active, 0), "228.1");
        store.save(state);
        state = store.read();
        assertFalse(EpisodeEngine.restarted(state));
        assertTrue(EpisodeEngine.success(state, 229, proof(), "{}", 0));
        store.save(state); // Closure and recovery intent precede the external send.
        state = store.read();
        assertTrue(state.active.closed);
        var recovery = EpisodeEngine.claim(state.active, 0);
        assertEquals("RECOVERY", recovery.kind);
        assertEquals("225.1", state.active.rootTs);
        EpisodeEngine.sent(state.active, recovery, "229.1");
        store.save(state);
        state = store.read();
        assertFalse(EpisodeEngine.restarted(state));
        assertNull(EpisodeEngine.claim(state.active, 0));
        for (int number = 230; number <= 233; number++) {
            assertFalse(EpisodeEngine.success(state, number, proof(), "{}", 0));
            assertTrue(state.active.closed);
        }
        store.save(state);
        state = store.read();
        assertEquals("INITIAL", fail(state, 234, "candidate-b"));
        assertNotEquals(original, state.active.id);
        assertNull(state.active.rootTs);
        EpisodeEngine.sent(state.active, EpisodeEngine.claim(state.active, 0), "234.1");
        store.save(state);
        state = store.read();
        assertFalse(EpisodeEngine.restarted(state));
        assertNull(EpisodeEngine.claim(state.active, 0));
        assertEquals("NONE", fail(state, 235, "candidate-b"));
        assertEquals("234.1", state.active.rootTs);
        assertEquals("225.1", state.history.get(0).rootTs);
    }

    @Test
    void verifiedBoundaryIsIndependentOfUnresolvedOldDelivery(@TempDir Path directory) throws Exception {
        for (String status : List.of("QUEUED", "LEASED", "UNKNOWN_OUTCOME", "FAILED", "SENT")) {
            var state = new EpisodeEngine.State();
            fail(state, 225, "a");
            String old = state.active.id;
            state.active.deliveries.get(0).status = status;
            state.lastBuild = 233;
            assertFalse(EpisodeEngine.startsEpisode(state, 234, state.active.signature, 233, 0));
            assertFalse(EpisodeEngine.startsEpisode(state, 234, state.active.signature, 224, 225));
            assertFalse(EpisodeEngine.startsEpisode(state, 234, state.active.signature, 233, 235));
            assertEquals(
                    "INITIAL",
                    EpisodeEngine.failure(
                            state, 234, state.active.signature, "a", CHECK, "C12345678", "{}", 0, 233, 234));
            assertNotEquals(old, state.active.id);
            assertFalse(state.history.get(0).closed);
            assertEquals(status, state.history.get(0).deliveries.get(0).status);
            var store = new EpisodeStore(directory.resolve(status + ".json"));
            store.save(state);
            state = store.read();
            EpisodeEngine.restarted(state);
            assertNotNull(EpisodeEngine.claim(state.active, 0));
            assertNull(state.active.rootTs);
        }
    }

    @Test
    void recoveryDeliveryFailureNeverReopensOrBlindlyResends(@TempDir Path directory) throws Exception {
        for (String outcome : List.of("UNKNOWN_OUTCOME", "FAILED")) {
            var state = new EpisodeEngine.State();
            fail(state, 225, "a");
            EpisodeEngine.sent(state.active, EpisodeEngine.claim(state.active, 0), "225.1");
            assertTrue(EpisodeEngine.success(state, 229, proof(), "{}", 0));
            var recovery = EpisodeEngine.claim(state.active, 0);
            recovery.status = outcome;
            var store = new EpisodeStore(directory.resolve(outcome + ".json"));
            store.save(state);
            state = store.read();
            assertTrue(state.active.closed);
            assertFalse(EpisodeEngine.restarted(state));
            assertNull(EpisodeEngine.claim(state.active, 0));
            assertFalse(EpisodeEngine.success(state, 230, proof(), "{}", 0));
            assertEquals("INITIAL", fail(state, 234, "a"));
            assertNull(state.active.rootTs);
        }
    }

    @Test
    void stableIdentityIgnoresLineShiftsButRetainsDistinctFailure() {
        FailureSignal shifted = FailureSignal.extract(List.of(
                HEADER, "[ERROR] src/main/java/Trade.java:[900,2] cannot find symbol", "symbol: variable missing"));
        FailureSignal different = FailureSignal.extract(List.of(
                HEADER, "[ERROR] src/main/java/Trade.java:[900,2] cannot find symbol", "symbol: variable other"));
        assertEquals(EpisodeEngine.signature(SIGNAL), EpisodeEngine.signature(shifted));
        assertNotEquals(EpisodeEngine.signature(SIGNAL), EpisodeEngine.signature(different));
    }

    @Test
    void lifecycleSuppressesRepeatsClosesOnceAndStartsNewRecurrence() {
        EpisodeEngine.State state = new EpisodeEngine.State();
        assertEquals("INITIAL", fail(state, 2, "candidate-a"));
        String first = state.active.id;
        assertEquals("NONE", fail(state, 3, "candidate-a"));
        assertEquals("MATERIAL", fail(state, 4, "candidate-b"));
        assertFalse(EpisodeEngine.success(state, 5, null, "{}", 0));
        assertFalse(state.active.closed);
        assertTrue(EpisodeEngine.success(state, 6, proof(), "{}", 0));
        assertFalse(EpisodeEngine.success(state, 7, proof(), "{}", 0));
        assertEquals(3, state.active.deliveries.size());
        assertEquals("INITIAL", fail(state, 8, "candidate-b"));
        assertNotEquals(first, state.active.id);
        assertEquals(first, state.history.get(0).id);
    }

    @Test
    void olderOrDuplicateObservationsCannotRegressState() {
        EpisodeEngine.State state = new EpisodeEngine.State();
        fail(state, 20, "candidate");
        assertEquals("NONE", fail(state, 19, "different"));
        assertFalse(EpisodeEngine.success(state, 20, proof(), "{}", 0));
        assertEquals(1, state.active.deliveries.size());
        assertEquals("candidate", state.active.material);
    }

    @Test
    void replyWaitsForReceiptAndUnknownRootNeverCreatesReplacement() {
        EpisodeEngine.State state = new EpisodeEngine.State();
        fail(state, 2, "a");
        fail(state, 3, "b");
        EpisodeEngine.Delivery root = EpisodeEngine.claim(state.active, 0);
        assertNotNull(root);
        assertNull(EpisodeEngine.claim(state.active, 0));
        EpisodeEngine.uncertain(root);
        assertNull(EpisodeEngine.claim(state.active, 0));
        assertEquals("QUEUED", state.active.deliveries.get(1).status);
    }

    @Test
    void receiptAndInFlightRestartSurviveAtomicStore(@TempDir Path directory) throws Exception {
        EpisodeStore store = new EpisodeStore(directory.resolve("state.json"));
        EpisodeEngine.State state = store.read();
        fail(state, 2, "a");
        EpisodeEngine.Delivery root = EpisodeEngine.claim(state.active, 0);
        store.save(state);
        assertTrue(EpisodeEngine.restarted(store.read()));
        EpisodeEngine.sent(state.active, root, "123.000001");
        fail(state, 3, "b");
        assertNotNull(EpisodeEngine.claim(state.active, 0));
        store.save(state);
        EpisodeEngine.State restored = store.read();
        assertTrue(EpisodeEngine.restarted(restored));
        store.save(restored);
        assertEquals("123.000001", store.read().active.rootTs);
        assertEquals("SENT", restored.active.deliveries.get(0).status);
        assertEquals("UNKNOWN_OUTCOME", restored.active.deliveries.get(1).status);
        assertFalse(EpisodeEngine.restarted(restored));
        assertNull(EpisodeEngine.claim(restored.active, 0));
    }

    @Test
    void retriesAreBoundedAndWaitForDueTime() {
        EpisodeEngine.State state = new EpisodeEngine.State();
        fail(state, 2, "a");
        for (int attempt = 0; attempt < 5; attempt++) {
            EpisodeEngine.Delivery delivery = EpisodeEngine.claim(state.active, attempt * 100L);
            assertNotNull(delivery);
            EpisodeEngine.retry(delivery, (attempt + 1) * 100L);
            assertNull(EpisodeEngine.claim(state.active, attempt * 100L));
        }
        assertEquals("FAILED", state.active.deliveries.get(0).status);
    }

    @Test
    void deliveredSupersededFailuresDoNotExhaustHistory() {
        EpisodeEngine.State state = new EpisodeEngine.State();
        for (int build = 1; build <= 20; build++) {
            assertEquals(
                    "INITIAL",
                    EpisodeEngine.failure(state, build, "failure-" + build, "candidate", CHECK, "C123", "{}", 0));
            EpisodeEngine.Delivery root = EpisodeEngine.claim(state.active, 0);
            assertNotNull(root);
            EpisodeEngine.sent(state.active, root, build + ".000001");
            assertFalse(state.active.closed);
            assertTrue(state.history.size() <= 8);
            assertTrue(state.history.stream().noneMatch(episode -> episode.closed));
        }
        assertEquals(20, state.active.firstBuild);
    }

    @Test
    void historyRetentionNeverDiscardsUnknownDeliveries() {
        EpisodeEngine.State state = new EpisodeEngine.State();
        for (int build = 1; build <= 9; build++) {
            EpisodeEngine.failure(state, build, "failure-" + build, "candidate", CHECK, "C123", "{}", 0);
            EpisodeEngine.uncertain(EpisodeEngine.claim(state.active, 0));
        }
        var retained = state.history.stream().map(episode -> episode.id).toList();
        assertThrows(
                IllegalStateException.class,
                () -> EpisodeEngine.failure(state, 10, "another-failure", "candidate", CHECK, "C123", "{}", 0));
        assertEquals(retained, state.history.stream().map(episode -> episode.id).toList());
        assertTrue(
                state.history.stream().allMatch(episode -> "UNKNOWN_OUTCOME".equals(episode.deliveries.get(0).status)));
    }

    private static String fail(EpisodeEngine.State state, int build, String material) {
        return EpisodeEngine.failure(state, build, EpisodeEngine.signature(SIGNAL), material, CHECK, "C123", "{}", 0);
    }

    private static ComparableCheck.Proof proof() {
        return ComparableCheck.passed(
                List.of(HEADER, "[INFO] Compiling 3 source files with javac", "[INFO] BUILD SUCCESS"),
                CHECK,
                "builder-v1",
                true);
    }
}

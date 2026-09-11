package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuppressionPolicyTest {
    private static final long START = 1_000_000;

    private static SuppressionPolicy.State initial() {
        var state = SuppressionPolicy.offer(
                        SuppressionPolicy.State.empty(), "initial", SuppressionPolicy.Kind.INITIAL, "a", START)
                .state();
        return SuppressionPolicy.accepted(state, "initial", START + 15_000);
    }

    @Test
    void initialWaitsFifteenSecondsAndNoAiWait() {
        var state = SuppressionPolicy.offer(
                        SuppressionPolicy.State.empty(), "initial", SuppressionPolicy.Kind.INITIAL, "a", START)
                .state();
        assertFalse(SuppressionPolicy.evaluate(state, START + 14_999).eligible());
        assertTrue(SuppressionPolicy.evaluate(state, START + 15_000).eligible());
        assertEquals(
                "AI_UPDATES_DISABLED",
                SuppressionPolicy.offer(state, "ai", SuppressionPolicy.Kind.AI_AVAILABLE, "b", START)
                        .reason());
    }

    @Test
    void repeatedInitialAndUnchangedFactsNeverSendAgain() {
        var state = initial();
        for (int i = 0; i < 10; i++) {
            var decision = SuppressionPolicy.offer(
                    state, "repeat-" + i, SuppressionPolicy.Kind.MATERIAL, "a", START + 20_000 + i);
            assertFalse(decision.eligible());
            assertNull(decision.state().pending());
        }
        assertEquals(
                "LIFECYCLE_ALREADY_ACCEPTED",
                SuppressionPolicy.offer(state, "new-id", SuppressionPolicy.Kind.INITIAL, "a", START)
                        .reason());
    }

    @Test
    void debounceCoalescesButHasFiveMinuteHardMaximum() {
        var state = initial();
        for (int seconds = 0; seconds <= 280; seconds += 40) {
            state = SuppressionPolicy.offer(
                            state,
                            "event-" + seconds,
                            SuppressionPolicy.Kind.MATERIAL,
                            "facts-" + seconds,
                            START + 20_000 + seconds * 1000)
                    .state();
        }
        assertFalse(SuppressionPolicy.evaluate(state, START + 319_999).eligible());
        assertTrue(SuppressionPolicy.evaluate(state, START + 320_000).eligible());
        assertEquals("event-280", state.pending().eventId());
    }

    @Test
    void duplicatePendingDoesNotExtendDebounce() {
        var state = SuppressionPolicy.offer(initial(), "event", SuppressionPolicy.Kind.MATERIAL, "b", START + 20_000)
                .state();
        var duplicate = SuppressionPolicy.offer(state, "event", SuppressionPolicy.Kind.MATERIAL, "b", START + 79_000)
                .state();
        assertEquals(state, duplicate);
        assertTrue(SuppressionPolicy.evaluate(duplicate, START + 80_000).eligible());
    }

    @Test
    void materialMergesIntoUnsentInitialWithoutLosingInitialDeadline() {
        var state = SuppressionPolicy.offer(
                        SuppressionPolicy.State.empty(), "new", SuppressionPolicy.Kind.INITIAL, "a", START)
                .state();
        state = SuppressionPolicy.offer(state, "update", SuppressionPolicy.Kind.MATERIAL, "b", START + 10_000)
                .state();
        assertEquals(SuppressionPolicy.Kind.INITIAL, state.pending().kind());
        assertEquals(START + 15_000, SuppressionPolicy.evaluate(state, START).eligibleAt());
    }

    @Test
    void candidateReversalBeforeDeliveryCancelsPendingDelta() {
        var state = SuppressionPolicy.offer(initial(), "b", SuppressionPolicy.Kind.MATERIAL, "b", START + 20_000)
                .state();
        var back = SuppressionPolicy.offer(state, "a", SuppressionPolicy.Kind.MATERIAL, "a", START + 30_000);
        assertNull(back.state().pending());
    }

    @Test
    void candidateReversalAfterDeliveryIsEligibleLater() {
        var state = SuppressionPolicy.offer(initial(), "b", SuppressionPolicy.Kind.MATERIAL, "b", START + 20_000)
                .state();
        state = SuppressionPolicy.accepted(state, "b", START + 80_000);
        state = SuppressionPolicy.offer(state, "a-again", SuppressionPolicy.Kind.MATERIAL, "a", START + 90_000)
                .state();
        assertEquals(
                START + 980_000,
                SuppressionPolicy.evaluate(state, START + 90_000).eligibleAt());
    }

    @Test
    void fifthMaterialWaitsForRollingDay() {
        var state = initial();
        long firstAccepted = 0;
        for (int i = 0; i < 4; i++) {
            long now = START + 100_000 + i * 1_000_000L;
            state = SuppressionPolicy.offer(state, "m" + i, SuppressionPolicy.Kind.MATERIAL, "f" + i, now)
                    .state();
            state = SuppressionPolicy.accepted(state, "m" + i, now + 60_000);
            if (i == 0) firstAccepted = now + 60_000;
        }
        state = SuppressionPolicy.offer(state, "fifth", SuppressionPolicy.Kind.MATERIAL, "fifth", START + 4_100_000)
                .state();
        assertFalse(SuppressionPolicy.evaluate(state, START + 4_200_000).eligible());
        assertEquals(
                firstAccepted + SuppressionPolicy.DAY,
                SuppressionPolicy.evaluate(state, START + 4_200_000).eligibleAt());
        assertTrue(SuppressionPolicy.evaluate(state, firstAccepted + SuppressionPolicy.DAY)
                .eligible());
    }

    @Test
    void lifetimeCapRetainsRecoveryAndConfirmationSlots() {
        var state = initial();
        for (int i = 0; i < 12; i++) {
            long now = START + (i + 1) * SuppressionPolicy.DAY;
            state = SuppressionPolicy.offer(state, "m" + i, SuppressionPolicy.Kind.MATERIAL, "f" + i, now)
                    .state();
            state = SuppressionPolicy.accepted(state, "m" + i, now + 60_000);
        }
        long now = START + 14 * SuppressionPolicy.DAY;
        state = SuppressionPolicy.offer(state, "blocked", SuppressionPolicy.Kind.MATERIAL, "blocked", now)
                .state();
        assertEquals(
                "LIFETIME_MATERIAL_CAP",
                SuppressionPolicy.evaluate(state, now + 60_000).reason());
        state = SuppressionPolicy.offer(state, "recovered", SuppressionPolicy.Kind.RECOVERY, "recovered", now)
                .state();
        state = SuppressionPolicy.accepted(state, "recovered", now + 5_000);
        state = SuppressionPolicy.offer(
                        state, "confirmed", SuppressionPolicy.Kind.CONFIRMATION, "confirmed", now + 10_000)
                .state();
        state = SuppressionPolicy.accepted(state, "confirmed", now + 15_000);
        assertEquals(12, state.materialLifetime());
        assertTrue(state.initialAccepted() && state.recoveryAccepted() && state.confirmationAccepted());
    }

    @Test
    void recoverySupersedesPendingFailureAndUnsentInitial() {
        var state = SuppressionPolicy.offer(
                        SuppressionPolicy.State.empty(), "new", SuppressionPolicy.Kind.INITIAL, "a", START)
                .state();
        state = SuppressionPolicy.offer(state, "recover", SuppressionPolicy.Kind.RECOVERY, "r", START + 1_000)
                .state();
        assertEquals(SuppressionPolicy.Kind.RECOVERY, state.pending().kind());
        var stale = SuppressionPolicy.offer(state, "stale", SuppressionPolicy.Kind.MATERIAL, "b", START + 2_000);
        assertEquals("SUPERSEDED_BY_LIFECYCLE", stale.reason());
        state = SuppressionPolicy.accepted(state, "recover", START + 6_000);
        assertTrue(state.initialAccepted());
        assertTrue(state.recoveryAccepted());
    }

    @Test
    void confirmationCanCoalesceRecovery() {
        var state = SuppressionPolicy.offer(initial(), "recover", SuppressionPolicy.Kind.RECOVERY, "r", START + 20_000)
                .state();
        state = SuppressionPolicy.offer(state, "confirm", SuppressionPolicy.Kind.CONFIRMATION, "c", START + 21_000)
                .state();
        state = SuppressionPolicy.accepted(state, "confirm", START + 26_000);
        assertTrue(state.recoveryAccepted() && state.confirmationAccepted());
    }

    @Test
    void muteIsOrthogonalAndRetained() {
        var empty = new SuppressionPolicy.State(false, false, false, 0, List.of(), -1, -1, START + 100_000, "", null);
        var decision = SuppressionPolicy.offer(empty, "new", SuppressionPolicy.Kind.INITIAL, "a", START);
        var state = decision.state();
        assertEquals("MUTED", decision.reason());
        assertNull(state.pending());
        assertFalse(SuppressionPolicy.evaluate(state, START + 110_000).eligible());
        assertFalse(state.initialAccepted());
    }

    @Test
    void snapshotRoundTripPreservesPendingCountersAndDeadlines() throws Exception {
        var state = SuppressionPolicy.offer(initial(), "event", SuppressionPolicy.Kind.MATERIAL, "b", START + 20_000)
                .state();
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsBytes(state), SuppressionPolicy.State.class);
        assertEquals(state, restored);
        assertEquals(
                SuppressionPolicy.evaluate(state, START + 80_000),
                SuppressionPolicy.evaluate(restored, START + 80_000));
    }

    @Test
    void wrongOrEarlyAcceptanceIsRejected() {
        var state = SuppressionPolicy.offer(initial(), "event", SuppressionPolicy.Kind.MATERIAL, "b", START + 20_000)
                .state();
        assertThrows(IllegalStateException.class, () -> SuppressionPolicy.accepted(state, "event", START + 20_000));
        assertThrows(IllegalStateException.class, () -> SuppressionPolicy.accepted(state, "other", START + 90_000));
    }

    @Test
    void clockRollbackCannotShortenQuietDeadline() {
        var state = SuppressionPolicy.offer(initial(), "event", SuppressionPolicy.Kind.MATERIAL, "b", START + 90_000)
                .state();
        state = SuppressionPolicy.offer(state, "updated", SuppressionPolicy.Kind.MATERIAL, "c", START + 80_000)
                .state();
        assertEquals(
                START + 150_000,
                SuppressionPolicy.evaluate(state, START + 90_000).eligibleAt());
    }

    @Test
    void initialStormAdmissionIsThreePerHourTenPerDay() {
        var state = InitialAdmissionPolicy.State.empty();
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            if (InitialAdmissionPolicy.eligible(state, START)) {
                state = InitialAdmissionPolicy.accepted(state, START);
                accepted++;
            }
        }
        assertEquals(3, accepted);
        for (int hour = 1; hour <= 3; hour++) {
            for (int i = 0; i < 3; i++) {
                long now = START + hour * InitialAdmissionPolicy.HOUR;
                if (InitialAdmissionPolicy.eligible(state, now)) state = InitialAdmissionPolicy.accepted(state, now);
            }
        }
        assertEquals(10, state.acceptedAt().size());
        assertFalse(InitialAdmissionPolicy.eligible(state, START + 4 * InitialAdmissionPolicy.HOUR));
        assertTrue(InitialAdmissionPolicy.eligible(state, START + SuppressionPolicy.DAY));
    }
}

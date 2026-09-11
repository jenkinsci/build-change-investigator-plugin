package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** Per-job/destination initial admission limits, including uncorrelated uncertain episodes. */
public final class InitialAdmissionPolicy {
    public static final long HOUR = 3_600_000;
    public static final int HOURLY_LIMIT = 3;
    public static final int DAILY_LIMIT = 10;

    private InitialAdmissionPolicy() {}

    public record State(List<Long> acceptedAt) implements Serializable {
        public State {
            acceptedAt = List.copyOf(Objects.requireNonNull(acceptedAt));
            if (acceptedAt.size() > DAILY_LIMIT || acceptedAt.stream().anyMatch(t -> t < 0)) {
                throw new IllegalArgumentException("Invalid initial admission state");
            }
        }

        public static State empty() {
            return new State(List.of());
        }
    }

    public static boolean eligible(State state, long now) {
        Objects.requireNonNull(state);
        if (now < 0) {
            throw new IllegalArgumentException("Invalid clock");
        }
        long day = state.acceptedAt().stream()
                .filter(t -> t > now - SuppressionPolicy.DAY)
                .count();
        long hour = state.acceptedAt().stream().filter(t -> t > now - HOUR).count();
        return day < DAILY_LIMIT && hour < HOURLY_LIMIT;
    }

    public static State accepted(State state, long now) {
        if (!eligible(state, now)) {
            throw new IllegalStateException("Initial admission limited");
        }
        java.util.ArrayList<Long> retained = new java.util.ArrayList<>(state.acceptedAt().stream()
                .filter(t -> t > now - SuppressionPolicy.DAY)
                .toList());
        retained.add(now);
        return new State(retained);
    }
}

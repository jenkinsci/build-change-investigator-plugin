package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** Pure per-case/destination policy. Epoch milliseconds are supplied by an injected caller clock. */
public final class SuppressionPolicy {
    public static final long INITIAL_DELAY = 15_000;
    public static final long MATERIAL_QUIET = 60_000;
    public static final long MATERIAL_MAX_WAIT = 300_000;
    public static final long MATERIAL_SPACING = 900_000;
    public static final long DAY = 86_400_000;
    public static final long CLOSURE_DELAY = 5_000;
    public static final int DAILY_MATERIAL_LIMIT = 4;
    public static final int LIFETIME_MATERIAL_LIMIT = 12;

    private SuppressionPolicy() {}

    public enum Kind {
        INITIAL,
        MATERIAL,
        AI_AVAILABLE,
        RECOVERY,
        CONFIRMATION,
        CORRECTION
    }

    public record Pending(String eventId, Kind kind, String fingerprint, long firstAt, long lastAt)
            implements Serializable {
        public Pending {
            requireIdentifier(eventId);
            Objects.requireNonNull(kind);
            requireIdentifier(fingerprint);
            if (firstAt < 0 || lastAt < firstAt) {
                throw new IllegalArgumentException("Invalid pending deadline");
            }
        }
    }

    public record State(
            boolean initialAccepted,
            boolean recoveryAccepted,
            boolean confirmationAccepted,
            int materialLifetime,
            List<Long> materialAcceptedAt,
            long lastMaterialAt,
            long lastCorrectionAt,
            long mutedUntil,
            String lastAcceptedFingerprint,
            Pending pending)
            implements Serializable {
        public State {
            materialAcceptedAt = List.copyOf(Objects.requireNonNull(materialAcceptedAt));
            lastAcceptedFingerprint = Objects.requireNonNullElse(lastAcceptedFingerprint, "");
            if (materialLifetime < 0
                    || materialLifetime > LIFETIME_MATERIAL_LIMIT
                    || materialAcceptedAt.size() > LIFETIME_MATERIAL_LIMIT
                    || materialAcceptedAt.size() > materialLifetime
                    || (recoveryAccepted && !initialAccepted)
                    || (confirmationAccepted && !recoveryAccepted)
                    || (materialLifetime == 0 && lastMaterialAt != -1)
                    || (materialLifetime > 0 && (!initialAccepted || lastMaterialAt < 0))
                    || materialAcceptedAt.stream().anyMatch(t -> t < 0)
                    || lastMaterialAt < -1
                    || lastCorrectionAt < -1
                    || mutedUntil < 0
                    || lastAcceptedFingerprint.length() > 512) {
                throw new IllegalArgumentException("Invalid suppression state");
            }
        }

        public static State empty() {
            return new State(false, false, false, 0, List.of(), -1, -1, 0, "", null);
        }
    }

    public record Decision(State state, boolean eligible, long eligibleAt, String reason) {}

    public static Decision offer(State state, String eventId, Kind kind, String fingerprint, long now) {
        return offer(state, eventId, kind, fingerprint, now, false);
    }

    public static Decision offer(
            State state, String eventId, Kind kind, String fingerprint, long now, boolean aiUpdates) {
        Objects.requireNonNull(state);
        requireIdentifier(eventId);
        requireIdentifier(fingerprint);
        Objects.requireNonNull(kind);
        requireTime(now);
        if (state.mutedUntil() > now) {
            return new Decision(withPending(state, null), false, -1, "MUTED");
        }
        if (kind == Kind.AI_AVAILABLE && !aiUpdates) {
            return new Decision(state, false, -1, "AI_UPDATES_DISABLED");
        }
        if (state.pending() != null && state.pending().eventId().equals(eventId)) {
            return evaluate(state, now);
        }
        if (kind == Kind.INITIAL && state.initialAccepted()
                || kind == Kind.RECOVERY && state.recoveryAccepted()
                || kind == Kind.CONFIRMATION && state.confirmationAccepted()) {
            return new Decision(state, false, -1, "LIFECYCLE_ALREADY_ACCEPTED");
        }
        if (material(kind) && fingerprint.equals(state.lastAcceptedFingerprint())) {
            State cleared =
                    state.pending() != null && material(state.pending().kind()) ? withPending(state, null) : state;
            return new Decision(cleared, false, -1, "UNCHANGED_FACTS");
        }
        Pending old = state.pending();
        if (old != null && priority(kind) < priority(old.kind())) {
            return new Decision(state, false, -1, "SUPERSEDED_BY_LIFECYCLE");
        }
        long first = now;
        Kind effective = material(kind) && !state.initialAccepted() && old == null ? Kind.INITIAL : kind;
        if (old != null && old.kind() == Kind.INITIAL && material(kind)) {
            first = old.firstAt();
            effective = Kind.INITIAL;
        } else if (old != null && material(old.kind()) && material(kind)) {
            first = old.firstAt();
        } else if (old != null && old.kind() == kind) {
            first = old.firstAt();
        }
        State updated = withPending(
                state,
                new Pending(eventId, effective, fingerprint, first, Math.max(old == null ? first : old.lastAt(), now)));
        return evaluate(updated, now);
    }

    public static Decision evaluate(State state, long now) {
        Objects.requireNonNull(state);
        requireTime(now);
        Pending pending = state.pending();
        if (pending == null) {
            return new Decision(state, false, -1, "NO_PENDING_EVENT");
        }
        if (state.mutedUntil() > now) {
            return new Decision(withPending(state, null), false, -1, "MUTED");
        }
        long at;
        if (material(pending.kind())) {
            if (state.materialLifetime() >= LIFETIME_MATERIAL_LIMIT) {
                return new Decision(state, false, -1, "LIFETIME_MATERIAL_CAP");
            }
            at = Math.min(add(pending.lastAt(), MATERIAL_QUIET), add(pending.firstAt(), MATERIAL_MAX_WAIT));
            if (state.lastMaterialAt() >= 0) {
                at = Math.max(at, add(state.lastMaterialAt(), MATERIAL_SPACING));
            }
            List<Long> recent = state.materialAcceptedAt().stream()
                    .filter(t -> add(t, DAY) > now)
                    .sorted()
                    .toList();
            if (recent.size() >= DAILY_MATERIAL_LIMIT) {
                at = Math.max(at, add(recent.get(recent.size() - DAILY_MATERIAL_LIMIT), DAY));
            }
        } else if (pending.kind() == Kind.CORRECTION) {
            at = state.lastCorrectionAt() < 0
                    ? pending.firstAt()
                    : Math.max(pending.firstAt(), add(state.lastCorrectionAt(), DAY));
        } else {
            at = add(pending.firstAt(), pending.kind() == Kind.INITIAL ? INITIAL_DELAY : CLOSURE_DELAY);
        }
        return new Decision(state, now >= at, at, now >= at ? "ELIGIBLE" : "DEFERRED");
    }

    /** Reserves quota for a durably accepted fake delivery; real transport is intentionally absent. */
    public static State accepted(State state, String eventId, long now) {
        Decision decision = evaluate(state, now);
        if (!decision.eligible() || !state.pending().eventId().equals(eventId)) {
            throw new IllegalStateException("Pending event is not eligible");
        }
        Pending pending = state.pending();
        boolean isMaterial = material(pending.kind());
        List<Long> accepted = state.materialAcceptedAt().stream()
                .filter(t -> add(t, DAY) > now)
                .toList();
        if (isMaterial) {
            java.util.ArrayList<Long> copy = new java.util.ArrayList<>(accepted);
            copy.add(now);
            accepted = List.copyOf(copy);
        }
        return new State(
                state.initialAccepted()
                        || pending.kind() == Kind.INITIAL
                        || pending.kind() == Kind.RECOVERY
                        || pending.kind() == Kind.CONFIRMATION,
                state.recoveryAccepted() || pending.kind() == Kind.RECOVERY || pending.kind() == Kind.CONFIRMATION,
                state.confirmationAccepted() || pending.kind() == Kind.CONFIRMATION,
                state.materialLifetime() + (isMaterial ? 1 : 0),
                accepted,
                isMaterial ? now : state.lastMaterialAt(),
                pending.kind() == Kind.CORRECTION ? now : state.lastCorrectionAt(),
                state.mutedUntil(),
                pending.fingerprint(),
                null);
    }

    private static State withPending(State state, Pending pending) {
        return new State(
                state.initialAccepted(),
                state.recoveryAccepted(),
                state.confirmationAccepted(),
                state.materialLifetime(),
                state.materialAcceptedAt(),
                state.lastMaterialAt(),
                state.lastCorrectionAt(),
                state.mutedUntil(),
                state.lastAcceptedFingerprint(),
                pending);
    }

    private static boolean material(Kind kind) {
        return kind == Kind.MATERIAL || kind == Kind.AI_AVAILABLE;
    }

    private static int priority(Kind kind) {
        return switch (kind) {
            case INITIAL, MATERIAL, AI_AVAILABLE -> 0;
            case RECOVERY -> 1;
            case CONFIRMATION -> 2;
            case CORRECTION -> 3;
        };
    }

    private static long add(long time, long duration) {
        return time > Long.MAX_VALUE - duration ? Long.MAX_VALUE : time + duration;
    }

    private static void requireIdentifier(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid suppression identifier");
        }
    }

    private static void requireTime(long now) {
        if (now < 0) {
            throw new IllegalArgumentException("Invalid clock value");
        }
    }
}

package io.jenkins.plugins.changeinvestigator.notification.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Immutable transport-neutral delivery state. No client, credential or network behavior. */
public record OutboxIntent(
        UUID deliveryId,
        UUID eventId,
        UUID destinationId,
        long destinationGeneration,
        int rendererVersion,
        State state,
        int attempts,
        long createdAt,
        long nextAttemptAt,
        UUID leaseToken,
        long leaseExpiresAt,
        String receipt,
        String safeCode) {
    public OutboxIntent {
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(destinationId);
        Objects.requireNonNull(state);
        if (destinationGeneration < 1
                || rendererVersion < 1
                || attempts < 0
                || attempts > 6
                || createdAt < 0
                || nextAttemptAt < 0
                || leaseExpiresAt < 0) throw new IllegalArgumentException("Invalid outbox bounds");
        if (receipt != null && (!receipt.matches("[A-Za-z0-9_.:-]{1,256}")))
            throw new IllegalArgumentException("Invalid receipt");
        if (receipt != null
                && !receipt.equals(
                        io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(receipt, 256)))
            throw new IllegalArgumentException("Unsafe receipt");
        if (safeCode != null && !safeCode.matches("[A-Z0-9_]{1,80}"))
            throw new IllegalArgumentException("Invalid delivery code");
        if (state == State.LEASED && (leaseToken == null || leaseExpiresAt == 0))
            throw new IllegalArgumentException("Missing delivery lease");
        if (state == State.SENT && (receipt == null || receipt.isBlank()))
            throw new IllegalArgumentException("Missing delivery receipt");
        if (state != State.LEASED && (leaseToken != null || leaseExpiresAt != 0))
            throw new IllegalArgumentException("Unexpected delivery lease");
    }

    public static OutboxIntent queued(
            UUID caseId, UUID eventId, UUID destinationId, long generation, int rendererVersion, long now) {
        String key = caseId + ":" + eventId + ":" + destinationId + ":" + generation + ":" + rendererVersion;
        return new OutboxIntent(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                eventId,
                destinationId,
                generation,
                rendererVersion,
                State.QUEUED,
                0,
                now,
                now,
                null,
                0,
                null,
                null);
    }

    /** Persist the returned lease in the case transaction before any future submission. */
    public OutboxIntent lease(long now, long leaseMillis) {
        if ((state != State.QUEUED && state != State.RETRY_WAIT)
                || now < createdAt
                || now < nextAttemptAt
                || attempts >= 6
                || now - createdAt >= 86_400_000
                || leaseMillis < 1
                || leaseMillis > 300_000) throw new IllegalStateException("Delivery is not eligible");
        return copy(State.LEASED, attempts + 1, now, UUID.randomUUID(), Math.addExact(now, leaseMillis), null, null);
    }

    public OutboxIntent accepted(UUID token, String receiptId) {
        requireLease(token);
        if (receiptId == null || receiptId.isBlank()) throw new IllegalArgumentException("Receipt required");
        return copy(State.SENT, attempts, nextAttemptAt, null, 0, receiptId, "ACCEPTED");
    }

    public OutboxIntent rejected(UUID token, boolean retryable, long retryAt) {
        requireLease(token);
        if (retryAt < nextAttemptAt) throw new IllegalArgumentException("Invalid retry time");
        boolean retry = retryable && attempts < 6 && retryAt - createdAt < 86_400_000;
        return copy(
                retry ? State.RETRY_WAIT : State.FAILED_PERMANENT,
                attempts,
                retryAt,
                null,
                0,
                null,
                retry ? "DEFINITE_TRANSIENT_REJECTION" : "DEFINITE_PERMANENT_REJECTION");
    }

    public OutboxIntent unknown(UUID token) {
        requireLease(token);
        return copy(State.UNKNOWN_OUTCOME, attempts, nextAttemptAt, null, 0, null, "ACCEPTANCE_UNKNOWN");
    }

    public OutboxIntent recoverExpiredLease(long now) {
        return state == State.LEASED && now >= leaseExpiresAt ? unknown(leaseToken) : this;
    }

    public OutboxIntent cancelForDestinationGeneration(long currentGeneration) {
        if (currentGeneration == destinationGeneration || state == State.SENT || state == State.UNKNOWN_OUTCOME)
            return this;
        if (state == State.LEASED) return unknown(leaseToken);
        return copy(State.CANCELLED, attempts, nextAttemptAt, null, 0, null, "DESTINATION_CHANGED");
    }

    private void requireLease(UUID token) {
        if (state != State.LEASED || token == null || !token.equals(leaseToken))
            throw new IllegalStateException("Delivery lease mismatch");
    }

    private OutboxIntent copy(
            State status, int count, long next, UUID token, long expiry, String response, String code) {
        return new OutboxIntent(
                deliveryId,
                eventId,
                destinationId,
                destinationGeneration,
                rendererVersion,
                status,
                count,
                createdAt,
                next,
                token,
                expiry,
                response,
                code);
    }

    public enum State {
        QUEUED,
        LEASED,
        SENT,
        RETRY_WAIT,
        FAILED_PERMANENT,
        UNKNOWN_OUTCOME,
        CANCELLED,
        SUPPRESSED
    }
}

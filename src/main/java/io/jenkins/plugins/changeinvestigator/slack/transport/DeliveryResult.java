package io.jenkins.plugins.changeinvestigator.slack.transport;

/** Delivery certainty, rather than a promise of exactly-once delivery. */
public record DeliveryResult(Outcome outcome, String timestamp, long retryAfterSeconds, String safeMessage) {
    public enum Outcome {
        ACCEPTED,
        RETRY,
        PERMANENT,
        UNKNOWN
    }
}

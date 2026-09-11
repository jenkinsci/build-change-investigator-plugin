package io.jenkins.plugins.changeinvestigator.notification.persistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Frozen rendered bytes and approved routing identity; never contains a credential or client. */
public record DeliverySnapshot(
        UUID deliveryId, String payload, String routing, long reservedAt, java.util.List<String> chunks) {
    public DeliverySnapshot(UUID deliveryId, String payload, String routing, long reservedAt) {
        this(deliveryId, payload, routing, reservedAt, java.util.List.of());
    }

    public DeliverySnapshot {
        chunks = chunks == null ? java.util.List.of() : java.util.List.copyOf(chunks);
        if (chunks.size() > 3 || chunks.stream().anyMatch(c -> c.length() > 32768 || !c.matches("[A-Za-z0-9+/=]*")))
            throw new IllegalArgumentException("Invalid frozen byte chunks");
        if (deliveryId == null
                || payload == null
                || payload.getBytes(StandardCharsets.UTF_8).length > 32768
                || routing == null
                || !routing.matches("[A-Za-z0-9_.:-]{1,256}")
                || reservedAt < 0) {
            throw new IllegalArgumentException("Invalid frozen delivery snapshot");
        }
    }
}

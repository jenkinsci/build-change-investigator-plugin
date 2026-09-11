package io.jenkins.plugins.changeinvestigator.notification.persistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Frozen rendered bytes and approved routing identity; never contains a credential or client. */
public record DeliverySnapshot(UUID deliveryId, String payload, String routing, long reservedAt) {
    public DeliverySnapshot {
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

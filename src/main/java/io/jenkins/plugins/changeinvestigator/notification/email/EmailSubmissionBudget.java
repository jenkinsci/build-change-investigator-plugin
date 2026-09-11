package io.jenkins.plugins.changeinvestigator.notification.email;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.persistence.NotificationStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Durable per-destination submission reservations; retries retain the original reservation. */
public final class EmailSubmissionBudget {
    private final NotificationStore store;

    public EmailSubmissionBudget(Path controllerRoot, UUID controllerId) throws IOException {
        store = new NotificationStore(controllerRoot.resolve("bci-email-budget"), controllerId);
    }

    public record Decision(boolean allowed, long retryAt) {}

    public synchronized Decision reserve(UUID destination, UUID delivery, long now) throws IOException {
        var saved = store.load(destination);
        ObjectNode data = saved.map(NotificationStore.Snapshot::aggregate)
                .orElseGet(() -> new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        if (data.isEmpty()) {
            data.putObject("reservations");
            data.put("nextAt", 0L);
        }
        if (data.size() != 2
                || !data.path("reservations").isObject()
                || data.path("reservations").size() > 1000
                || !data.path("nextAt").isIntegralNumber()
                || data.path("nextAt").asLong() < 0
                || now < 0) throw new IOException("Invalid Email submission budget");
        ObjectNode reservations = (ObjectNode) data.get("reservations");
        var expired = new java.util.ArrayList<String>();
        var fields = reservations.fields();
        long oldestDay = Long.MAX_VALUE, oldestMinute = Long.MAX_VALUE;
        int recent = 0;
        while (fields.hasNext()) {
            var entry = fields.next();
            try {
                UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid Email reservation");
            }
            if (!entry.getValue().isIntegralNumber() || entry.getValue().asLong() < 0)
                throw new IOException("Invalid Email reservation clock");
            long at = entry.getValue().asLong();
            if (now >= at && now - at >= 86_400_000) expired.add(entry.getKey());
            else {
                oldestDay = Math.min(oldestDay, at);
                if (now < at || now - at < 60_000) {
                    recent++;
                    oldestMinute = Math.min(oldestMinute, at);
                }
            }
        }
        reservations.remove(expired);
        long next = data.path("nextAt").asLong();
        boolean existing = reservations.has(delivery.toString());
        if (!existing && recent >= 30) next = Math.max(next, oldestMinute + 60_000);
        if (!existing && reservations.size() >= 1000) next = Math.max(next, oldestDay + 86_400_000);
        if (now < next) return new Decision(false, next);
        if (!existing) reservations.put(delivery.toString(), now);
        data.put("nextAt", now + 1000);
        store.update(
                destination, saved.map(NotificationStore.Snapshot::revision).orElse(0L), ignored -> data);
        return new Decision(true, now);
    }
}

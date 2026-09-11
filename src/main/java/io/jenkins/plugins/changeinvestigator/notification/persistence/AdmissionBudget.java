package io.jenkins.plugins.changeinvestigator.notification.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Durable controller-wide admission reservations. Orphan reservations fail closed until reconciled. */
public final class AdmissionBudget {
    public static final int MAX_CONTROLLER = 10000;
    public static final int MAX_DESTINATION = 1000;
    public static final long MAX_STORE_BYTES = 256L * 1024 * 1024;
    private final NotificationStore store;
    private final UUID id;
    private final Object lock;

    public AdmissionBudget(Path controllerRoot, UUID controllerId) throws IOException {
        Path root = controllerRoot.resolve("bci-notification-admission");
        store = new NotificationStore(root, controllerId, path -> {}, 2 * 1024 * 1024);
        id = controllerId;
        lock = store.transactionLock();
    }

    /** Reserve before case intent creation. Never release an uncertain external outcome automatically. */
    public boolean reserve(
            UUID deliveryId, UUID destinationId, boolean terminal, long currentStoreBytes, int eventBytes)
            throws IOException {
        if (currentStoreBytes < 0 || eventBytes < 0 || eventBytes > 32768)
            throw new IllegalArgumentException("Invalid storage accounting");
        synchronized (lock) {
            var old = store.load(id);
            ObjectNode data = old.isEmpty()
                    ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    : old.get().aggregate();
            ObjectNode entries;
            if (data.isEmpty()) {
                entries = data.putObject("reservations");
                data.putObject("jobBytes");
            } else if (data.path("reservations").isObject()) entries = (ObjectNode) data.get("reservations");
            else throw new IOException("Invalid admission index");
            String key = deliveryId.toString();
            if (entries.has(key)) {
                if (!destinationId
                        .toString()
                        .equals(entries.path(key).path("destination").asText()))
                    throw new IOException("Delivery reservation mismatch");
                return true;
            }
            int destinationCount = 0;
            long reservedBytes = 0;
            for (JsonNode entry : entries) {
                if (!entry.isObject()
                        || !entry.path("destination").isTextual()
                        || !entry.path("bytes").isInt()
                        || entry.path("bytes").intValue() < 0
                        || entry.path("bytes").intValue() > 32768) throw new IOException("Invalid admission entry");
                reservedBytes += entry.path("bytes").intValue();
                if (entry.path("destination").textValue().equals(destinationId.toString())) destinationCount++;
            }
            long measuredBytes = usage(data);
            int controllerLimit = terminal ? MAX_CONTROLLER : MAX_CONTROLLER * 4 / 5;
            int destinationLimit = terminal ? MAX_DESTINATION : MAX_DESTINATION * 4 / 5;
            long diskLimit = terminal ? MAX_STORE_BYTES : MAX_STORE_BYTES * 4 / 5;
            if (entries.size() >= controllerLimit
                    || destinationCount >= destinationLimit
                    || Math.max(currentStoreBytes, measuredBytes) > diskLimit - eventBytes - reservedBytes)
                return false;
            entries.putObject(key).put("destination", destinationId.toString()).put("bytes", eventBytes);
            store.update(id, old.map(NotificationStore.Snapshot::revision).orElse(0L), ignored -> data);
            return true;
        }
    }

    /** Local store usage is measured off request paths; outstanding reservations remain additional conservative headroom. */
    public void reportUsage(UUID jobId, long bytes) throws IOException {
        if (bytes < 0 || bytes > MAX_STORE_BYTES) throw new IOException("Notification storage budget exceeded");
        synchronized (lock) {
            var old = store.load(id);
            ObjectNode data = old.isEmpty()
                    ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    : old.get().aggregate();
            if (data.isEmpty()) {
                data.putObject("reservations");
                data.putObject("jobBytes");
            }
            usage(data);
            ObjectNode jobs = (ObjectNode) data.get("jobBytes");
            if (!jobs.has(jobId.toString()) && jobs.size() >= 10000)
                throw new IOException("Notification storage index full");
            jobs.put(jobId.toString(), bytes);
            if (usage(data) > MAX_STORE_BYTES - 2 * 1024 * 1024)
                throw new IOException("Notification controller storage budget exceeded");
            store.update(id, old.map(NotificationStore.Snapshot::revision).orElse(0L), ignored -> data);
        }
    }

    private static long usage(ObjectNode data) throws IOException {
        if (!data.path("jobBytes").isObject()) throw new IOException("Invalid storage accounting");
        long total = 0;
        for (JsonNode value : data.get("jobBytes")) {
            if (!value.isIntegralNumber()
                    || !value.canConvertToLong()
                    || value.longValue() < 0
                    || value.longValue() > MAX_STORE_BYTES) throw new IOException("Invalid storage accounting");
            total += value.longValue();
        }
        return total;
    }

    /** Call only after durable terminal state or a proven never-submitted orphan reconciliation. */
    public void release(UUID deliveryId) throws IOException {
        synchronized (lock) {
            var old = store.load(id);
            if (old.isEmpty()) return;
            ObjectNode data = old.get().aggregate();
            if (!data.path("reservations").isObject()) throw new IOException("Invalid admission index");
            ObjectNode entries = (ObjectNode) data.get("reservations");
            if (entries.remove(deliveryId.toString()) != null)
                store.update(id, old.get().revision(), ignored -> data);
        }
    }
}

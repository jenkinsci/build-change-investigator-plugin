package io.jenkins.plugins.changeinvestigator.notification.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Durable bounded handoff ledger. Case idempotency is committed before marking a handoff consumed. */
public final class IngestionLedger {
    public static final int MAX_ENTRIES = 200;
    private final NotificationStore store;
    private final UUID ledgerId;
    private final Object lock;

    public IngestionLedger(Path jobRoot, UUID jobId) throws IOException {
        Path root = jobRoot.resolve("bci-notifications").resolve("ingestion");
        store = new NotificationStore(root, jobId);
        ledgerId = jobId;
        lock = store.transactionLock();
    }

    /** A new ledger arms after this source-order boundary; loading never arms or backfills. */
    public void arm(long enableAfterOrder) throws IOException {
        if (enableAfterOrder < 0) throw new IllegalArgumentException("Negative ingestion boundary");
        synchronized (lock) {
            if (store.load(ledgerId).isPresent()) return;
            store.update(ledgerId, 0, value -> {
                value.put("consumedThrough", enableAfterOrder);
                value.putObject("entries");
                return value;
            });
        }
    }

    public Admission register(String observationId, long sourceOrder) throws IOException {
        validateId(observationId);
        if (sourceOrder < 1) throw new IllegalArgumentException("Invalid source order");
        synchronized (lock) {
            NotificationStore.Snapshot snapshot = required();
            ObjectNode value = snapshot.aggregate();
            ObjectNode entries = entries(value);
            JsonNode existing = entries.get(observationId);
            if (existing != null) {
                if (existing.path("sourceOrder").longValue() != sourceOrder)
                    throw new IOException("Observation identity mismatch");
                return existing.path("consumed").asBoolean() ? Admission.CONSUMED : Admission.PENDING;
            }
            if (sourceOrder <= value.path("consumedThrough").longValue()) return Admission.STALE;
            if (entries.size() >= MAX_ENTRIES) compact(value);
            if (entries.size() >= MAX_ENTRIES) throw new IOException("Notification ingestion limit reached");
            ObjectNode entry = entries.putObject(observationId);
            entry.put("sourceOrder", sourceOrder);
            entry.put("consumed", false);
            store.update(ledgerId, snapshot.revision(), ignored -> value);
            return Admission.PENDING;
        }
    }

    public void markConsumed(String observationId, UUID caseId, long caseRevision) throws IOException {
        validateId(observationId);
        if (caseRevision < 1) throw new IllegalArgumentException("Invalid consumed revision");
        synchronized (lock) {
            NotificationStore.Snapshot snapshot = required();
            ObjectNode value = snapshot.aggregate();
            ObjectNode entries = entries(value);
            JsonNode entry = entries.get(observationId);
            if (entry == null) throw new IOException("Unregistered notification observation");
            if (entry.path("consumed").asBoolean()) return;
            ObjectNode updated = (ObjectNode) entry;
            updated.put("consumed", true);
            updated.put("caseId", caseId.toString());
            updated.put("caseRevision", caseRevision);
            store.update(ledgerId, snapshot.revision(), ignored -> value);
        }
    }

    public ObjectNode pending() throws IOException {
        synchronized (lock) {
            ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            entries(required().aggregate()).fields().forEachRemaining(entry -> {
                if (!entry.getValue().path("consumed").asBoolean())
                    result.set(entry.getKey(), entry.getValue().deepCopy());
            });
            return result;
        }
    }

    public java.util.Optional<ObjectNode> observation(String observationId) throws IOException {
        validateId(observationId);
        synchronized (lock) {
            JsonNode value = entries(required().aggregate()).get(observationId);
            return value == null ? java.util.Optional.empty() : java.util.Optional.of(((ObjectNode) value).deepCopy());
        }
    }

    public long consumedThrough() throws IOException {
        synchronized (lock) {
            return required().aggregate().path("consumedThrough").longValue();
        }
    }

    private NotificationStore.Snapshot required() throws IOException {
        return store.load(ledgerId).orElseThrow(() -> new IOException("Notification ledger not armed"));
    }

    private static ObjectNode entries(ObjectNode value) throws IOException {
        if (value.size() != 2
                || !value.path("consumedThrough").isIntegralNumber()
                || !value.path("consumedThrough").canConvertToLong()
                || value.path("consumedThrough").longValue() < 0
                || !value.path("entries").isObject()) throw new IOException("Invalid notification ledger");
        ObjectNode entries = (ObjectNode) value.get("entries");
        if (entries.size() > MAX_ENTRIES) throw new IOException("Invalid notification ledger size");
        var fields = entries.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getKey().matches("[A-Za-z0-9:_-]{1,160}")) throw new IOException("Invalid observation identity");
            JsonNode entry = field.getValue();
            if (!entry.isObject()
                    || !entry.path("sourceOrder").isIntegralNumber()
                    || !entry.path("sourceOrder").canConvertToLong()
                    || entry.path("sourceOrder").longValue() < 1
                    || !entry.path("consumed").isBoolean()) throw new IOException("Invalid notification ledger entry");
            if (entry.path("consumed").asBoolean()) {
                if (entry.size() != 4
                        || !entry.path("caseRevision").isIntegralNumber()
                        || !entry.path("caseRevision").canConvertToLong()
                        || entry.path("caseRevision").longValue() < 1)
                    throw new IOException("Invalid consumption record");
                try {
                    UUID.fromString(entry.path("caseId").asText());
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid consumed case identity");
                }
            } else if (entry.size() != 2) throw new IOException("Invalid pending record");
        }
        return entries;
    }

    private static void compact(ObjectNode value) throws IOException {
        ObjectNode entries = entries(value);
        long earliest = Long.MAX_VALUE;
        for (JsonNode entry : entries)
            earliest = Math.min(earliest, entry.path("sourceOrder").longValue());
        final long boundary = earliest;
        for (JsonNode entry : entries) {
            if (entry.path("sourceOrder").longValue() <= boundary
                    && !entry.path("consumed").asBoolean()) return;
        }
        var names = new java.util.ArrayList<String>();
        entries.fields().forEachRemaining(entry -> {
            if (entry.getValue().path("sourceOrder").longValue() <= boundary) names.add(entry.getKey());
        });
        entries.remove(names);
        value.put("consumedThrough", Math.max(value.path("consumedThrough").longValue(), boundary));
    }

    private static void validateId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9:_-]{1,160}"))
            throw new IllegalArgumentException("Invalid observation identity");
    }

    public enum Admission {
        PENDING,
        CONSUMED,
        STALE
    }
}

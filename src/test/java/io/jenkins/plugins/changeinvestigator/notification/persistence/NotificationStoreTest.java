package io.jenkins.plugins.changeinvestigator.notification.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationStoreTest {
    @TempDir
    Path root;

    @Test
    void atomicFailureKeepsPreviousAggregateAndNoPartialEvent() throws Exception {
        UUID job = UUID.randomUUID(), id = UUID.randomUUID();
        NotificationStore store = new NotificationStore(root, job);
        store.update(id, 0, value -> value.put("events", 1).put("intents", 1));
        NotificationStore failing = new NotificationStore(root, job, path -> {
            throw new IOException("simulated full disk");
        });
        assertThrows(
                IOException.class,
                () -> failing.update(id, 1, value -> value.put("events", 2).put("intents", 2)));
        var restored = new NotificationStore(root, job).load(id).orElseThrow();
        assertEquals(1, restored.revision());
        assertEquals(1, restored.aggregate().path("events").asInt());
        assertEquals(1, restored.aggregate().path("intents").asInt());
    }

    @Test
    void revisionGuardAndDefensiveCopies() throws Exception {
        UUID id = UUID.randomUUID();
        var store = new NotificationStore(root, UUID.randomUUID());
        var snapshot = store.update(id, 0, value -> value.put("count", 1));
        snapshot.aggregate().put("count", 99);
        assertEquals(1, store.load(id).orElseThrow().aggregate().path("count").asInt());
        assertThrows(NotificationStore.RevisionConflictException.class, () -> store.update(id, 0, value -> value));
    }

    @Test
    void corruptUnknownAndDuplicateJsonAreQuarantinedWithoutRecreation() throws Exception {
        UUID job = UUID.randomUUID();
        for (String text :
                new String[] {"{broken", "{\"schemaVersion\":2}", "{\"schemaVersion\":1,\"schemaVersion\":1}"}) {
            UUID id = UUID.randomUUID();
            var store = new NotificationStore(root, job);
            store.update(id, 0, value -> value);
            Path path = root.resolve("bci-notifications/cases/" + id + ".json");
            Files.writeString(path, text);
            assertThrows(IOException.class, () -> store.load(id));
            assertTrue(Files.exists(path.resolveSibling(path.getFileName() + ".quarantined")));
            assertEquals(text, Files.readString(path));
            assertThrows(IOException.class, () -> store.update(id, 0, value -> value));
        }
    }

    @Test
    void rejectsOversizePojoSymlinkAndWrongJob() throws Exception {
        UUID job = UUID.randomUUID(), id = UUID.randomUUID();
        var store = new NotificationStore(root, job);
        assertThrows(IOException.class, () -> store.update(id, 0, value -> value.put("text", "x".repeat(32769))));
        assertThrows(
                IOException.class,
                () -> store.update(id, 0, value -> {
                    value.putPOJO("client", new Object());
                    return value;
                }));
        store.update(id, 0, value -> value);
        assertThrows(IOException.class, () -> new NotificationStore(root, UUID.randomUUID()).load(id));
        Path linked = root.resolve("linked");
        Files.createSymbolicLink(linked, root);
        assertThrows(IOException.class, () -> new NotificationStore(linked, job));
    }

    @Test
    void hundredConcurrentTransactionsHaveNoLostEvents() throws Exception {
        UUID job = UUID.randomUUID(), id = UUID.randomUUID();
        var store = new NotificationStore(root, job);
        store.update(id, 0, value -> value.put("count", 0));
        var pool = Executors.newFixedThreadPool(8);
        try {
            var work = new ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++)
                work.add(pool.submit(() -> {
                    try {
                        for (; ; ) {
                            var snapshot = store.load(id).orElseThrow();
                            try {
                                store.update(
                                        id,
                                        snapshot.revision(),
                                        value -> value.put(
                                                "count", value.path("count").asInt() + 1));
                                return;
                            } catch (NotificationStore.RevisionConflictException retry) {
                                /* Retry a stale local revision. */
                            }
                        }
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }));
            for (Future<?> future : work) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(100, store.load(id).orElseThrow().aggregate().path("count").asInt());
    }

    @Test
    void oneDurableLeaseWinsAndRestartNeverAutomaticallyResendsUnknown() throws Exception {
        UUID job = UUID.randomUUID(), id = UUID.randomUUID();
        ObjectMapper codec = new ObjectMapper();
        OutboxIntent intent = OutboxIntent.queued(id, UUID.randomUUID(), UUID.randomUUID(), 1, 1, 100);
        var store = new NotificationStore(root, job);
        store.update(id, 0, value -> {
            value.set("intent", codec.valueToTree(intent));
            return value;
        });
        var before = store.load(id).orElseThrow();
        store.update(id, before.revision(), value -> {
            OutboxIntent current = codec.convertValue(value.get("intent"), OutboxIntent.class);
            value.set("intent", codec.valueToTree(current.lease(100, 1000)));
            return value;
        });
        assertThrows(
                NotificationStore.RevisionConflictException.class,
                () -> store.update(id, before.revision(), value -> value));
        var restarted = new NotificationStore(root, job).load(id).orElseThrow();
        OutboxIntent leased = codec.treeToValue(restarted.aggregate().get("intent"), OutboxIntent.class);
        OutboxIntent unknown = leased.recoverExpiredLease(1100);
        assertEquals(OutboxIntent.State.UNKNOWN_OUTCOME, unknown.state());
        assertThrows(IllegalStateException.class, () -> unknown.lease(1200, 1000));
    }
}

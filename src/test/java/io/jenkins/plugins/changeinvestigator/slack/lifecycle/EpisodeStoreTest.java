package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpisodeStoreTest {
    @Test
    void configurationDigestsKeepLegacyJsonNamesAndDeliveryState(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("state.json");
        EpisodeStore store = new EpisodeStore(file);
        EpisodeEngine.State state = new EpisodeEngine.State();
        EpisodeEngine.failure(state, 1, "signature", "material", null, "C12345678", "{}", 0);
        String digest = EpisodeEngine.digest("true|bot-id|#alerts|true|1|1|0|0");
        state.configurationDigest = digest;
        state.active.configurationDigest = digest;
        var delivery = state.active.deliveries.get(0);
        delivery.status = "UNKNOWN_OUTCOME";
        delivery.aiRequested = true;
        store.save(state);
        String json = Files.readString(file);
        assertTrue(json.contains("\"configKey\":\"" + digest + "\""));
        assertFalse(json.contains("configurationDigest"));
        EpisodeEngine.State restored = new EpisodeStore(file).read();
        assertEquals(digest, restored.configurationDigest);
        assertEquals(digest, restored.active.configurationDigest);
        assertEquals(delivery.id, restored.active.deliveries.get(0).id);
        assertEquals("UNKNOWN_OUTCOME", restored.active.deliveries.get(0).status);
        assertTrue(restored.active.deliveries.get(0).aiRequested);
    }

    @Test
    void duplicateKeysTrailingObjectsAndUnknownVersionsFailClosed(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("state.json");
        EpisodeStore store = new EpisodeStore(file);
        for (String invalid : new String[] {"{\"version\":1,\"version\":1}", "{} {}", "{\"version\":2}"}) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, store::read);
        }
    }

    @Test
    void oversizedOrInvalidStateCannotBeSaved(@TempDir Path directory) throws Exception {
        EpisodeStore store = new EpisodeStore(directory.resolve("state.json"));
        EpisodeEngine.State state = new EpisodeEngine.State();
        EpisodeEngine.failure(state, 1, "signature", "material", null, "C12345678", "{}", 0);
        state.active.context = "x".repeat(16001);
        assertThrows(IOException.class, () -> store.save(state));
        state.active.context = "{}";
        state.active.deliveries.get(0).status = "MAYBE_SENT";
        assertThrows(IOException.class, () -> store.save(state));
    }

    @Test
    void nonRegularStatePathIsRejected(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("state.json");
        Files.createDirectory(file);
        EpisodeStore store = new EpisodeStore(file);
        assertThrows(IOException.class, store::read);
        assertThrows(IOException.class, () -> store.save(new EpisodeEngine.State()));
    }

    @Test
    void stoppedRuntimeCannotRecreateDisposedJobState(@TempDir Path directory) throws Exception {
        Path job = Files.createDirectory(directory.resolve("job"));
        Path file = job.resolve("state.json");
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        EpisodeStore store = new EpisodeStore(file, stopped::get);
        store.save(new EpisodeEngine.State());
        stopped.set(true);
        Files.delete(file);
        Files.delete(job);
        assertThrows(IOException.class, () -> store.save(new EpisodeEngine.State()));
        assertThrows(IOException.class, store::read);
        assertFalse(Files.exists(job));
    }
}

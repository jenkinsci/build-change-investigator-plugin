package io.jenkins.plugins.changeinvestigator.notification.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Bounded data-only atomic storage. Callers validate their fixed aggregate schema. */
public final class NotificationStore {
    public static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(24)
                            .maxStringLength(32768)
                            .maxNumberLength(64)
                            .build())
                    .build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path root;
    private final UUID jobId;
    private final WriteBarrier barrier;
    private final int maxBytes;

    public NotificationStore(Path jobRoot, UUID jobId) throws IOException {
        this(jobRoot, jobId, path -> {}, MAX_RECORD_BYTES);
    }

    NotificationStore(Path jobRoot, UUID jobId, WriteBarrier barrier) throws IOException {
        this(jobRoot, jobId, barrier, MAX_RECORD_BYTES);
    }

    NotificationStore(Path jobRoot, UUID jobId, WriteBarrier barrier, int maxBytes) throws IOException {
        this.maxBytes = maxBytes;
        this.root = jobRoot.toAbsolutePath().normalize().resolve("bci-notifications");
        this.jobId = java.util.Objects.requireNonNull(jobId);
        this.barrier = barrier;
        ensureDirectory(root);
    }

    public Optional<Snapshot> load(UUID caseId) throws IOException {
        synchronized (lock(root)) {
            return read(caseId);
        }
    }

    /** Revision zero creates; any other revision is a compare-and-set against durable state. */
    public Snapshot update(UUID caseId, long expectedRevision, UnaryOperator<ObjectNode> transaction)
            throws IOException {
        synchronized (lock(root)) {
            Snapshot previous = read(caseId).orElse(null);
            long revision = previous == null ? 0 : previous.revision();
            if (revision != expectedRevision) throw new RevisionConflictException();
            if (revision == Long.MAX_VALUE) throw new IOException("Notification revision exhausted");
            ObjectNode value = transaction.apply(previous == null ? JSON.createObjectNode() : previous.aggregate());
            if (value == null) throw new IOException("Notification aggregate required");
            validateTree(value, 0, new int[] {0});
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("schemaVersion", 1);
            envelope.put("jobId", jobId.toString());
            envelope.put("caseId", caseId.toString());
            envelope.put("revision", revision + 1);
            envelope.set("aggregate", value.deepCopy());
            byte[] bytes = JSON.writeValueAsBytes(envelope);
            if (bytes.length > maxBytes) throw new IOException("Notification record limit exceeded");
            atomicWrite(file(caseId), bytes, barrier);
            return new Snapshot(revision + 1, value);
        }
    }

    /** Bounded discovery; larger histories require explicit archival/reconciliation rather than partial grouping. */
    public java.util.List<UUID> caseIds() throws IOException {
        synchronized (lock(root)) {
            Path directory = root.resolve("cases");
            ensureDirectory(directory);
            var result = new java.util.ArrayList<UUID>();
            try (var paths = Files.newDirectoryStream(directory, "*.json")) {
                for (Path path : paths) {
                    checkPath(path);
                    Path filename = path.getFileName();
                    if (filename == null) throw new IOException("Notification case filename required");
                    String name = filename.toString();
                    try {
                        result.add(UUID.fromString(name.substring(0, name.length() - 5)));
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException("Invalid notification case filename");
                    }
                    if (result.size() > 100) throw new IOException("Notification case discovery limit exceeded");
                }
            }
            result.sort(java.util.Comparator.comparing(UUID::toString));
            return java.util.List.copyOf(result);
        }
    }

    /** Quarantine a structurally readable envelope that fails its caller's typed schema validation. */
    public void quarantine(UUID id) throws IOException {
        synchronized (lock(root)) {
            Path path = file(id);
            atomicWrite(
                    path.resolveSibling(path.getFileName() + ".quarantined"),
                    "QUARANTINED\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    ignored -> {});
        }
    }

    private Optional<Snapshot> read(UUID id) throws IOException {
        Path path = file(id);
        checkPath(path);
        Path marker = path.resolveSibling(path.getFileName() + ".quarantined");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Notification record quarantined");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes)
                throw new IOException("Invalid notification record");
            JsonNode value;
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw new IOException("Notification record limit exceeded");
                value = JSON.readTree(bytes);
            }
            Set<String> fields = new HashSet<>();
            if (value != null) value.fieldNames().forEachRemaining(fields::add);
            if (value == null
                    || !value.isObject()
                    || !fields.equals(Set.of("schemaVersion", "jobId", "caseId", "revision", "aggregate"))
                    || !value.path("schemaVersion").isInt()
                    || value.path("schemaVersion").intValue() != 1
                    || !jobId.toString().equals(value.path("jobId").asText())
                    || !id.toString().equals(value.path("caseId").asText())
                    || !value.path("revision").isIntegralNumber()
                    || !value.path("revision").canConvertToLong()
                    || value.path("revision").longValue() < 1
                    || !value.path("aggregate").isObject()) throw new IOException("Unsupported notification record");
            validateTree(value.get("aggregate"), 0, new int[] {0});
            return Optional.of(new Snapshot(value.get("revision").longValue(), (ObjectNode) value.get("aggregate")));
        } catch (IOException | RuntimeException failure) {
            // Keep the original bytes for restricted inspection; a marker prevents treating corruption as absence.
            atomicWrite(marker, "QUARANTINED\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), pathToWrite -> {});
            throw new IOException("Notification record quarantined");
        }
    }

    private Path file(UUID id) throws IOException {
        Path directory = root.resolve("cases");
        ensureDirectory(directory);
        return directory.resolve(id.toString() + ".json");
    }

    static Object lock(Path path) {
        return LOCKS[Math.floorMod(path.toAbsolutePath().normalize().toString().hashCode(), LOCKS.length)];
    }

    Object transactionLock() {
        return lock(root);
    }

    static void ensureDirectory(Path path) throws IOException {
        checkPath(path);
        Files.createDirectories(path);
        checkPath(path);
    }

    static void checkPath(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic notification path rejected");
            current = current.getParent();
        }
    }

    static void atomicWrite(Path path, byte[] bytes, WriteBarrier barrier) throws IOException {
        checkPath(path);
        Path parent = path.getParent();
        if (parent == null) throw new IOException("Notification parent directory required");
        ensureDirectory(parent);
        Path temporary = Files.createTempFile(parent, ".bci-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            barrier.beforeReplace(path);
            checkPath(path);
            // No non-atomic fallback: leave the previous complete record intact on unsupported filesystems.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateTree(JsonNode node, int depth, int[] count) throws IOException {
        if (depth > 24 || ++count[0] > 60000 || node.isPojo() || node.isBinary())
            throw new IOException("Notification data bounds exceeded");
        if (node.isTextual() && node.textValue().length() > 32768)
            throw new IOException("Notification field bounds exceeded");
        if (node.isContainerNode()) {
            if (node.size() > 10000) throw new IOException("Notification collection bounds exceeded");
            for (JsonNode child : node) validateTree(child, depth + 1, count);
        }
    }

    public record Snapshot(long revision, ObjectNode aggregate) {
        public Snapshot {
            aggregate = aggregate.deepCopy();
        }

        @Override
        public ObjectNode aggregate() {
            return aggregate.deepCopy();
        }
    }

    public static final class RevisionConflictException extends IOException {
        public RevisionConflictException() {
            super("Notification revision conflict");
        }
    }

    @FunctionalInterface
    interface WriteBarrier {
        void beforeReplace(Path destination) throws IOException;
    }
}

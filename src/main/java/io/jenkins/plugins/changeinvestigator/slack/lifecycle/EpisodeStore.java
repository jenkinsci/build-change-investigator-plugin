package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Job;
import hudson.util.AtomicFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** A bounded per-job outbox. Runtime serializes mutations; failed persistence prevents submission. */
public final class EpisodeStore {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Path path;
    private final java.util.function.BooleanSupplier cancelled;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(20)
                            .maxStringLength(40000)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public EpisodeStore(Job<?, ?> job) {
        this(job.getRootDir().toPath().resolve("bci-slack-episodes.json"));
    }

    EpisodeStore(Job<?, ?> job, java.util.function.BooleanSupplier cancelled) {
        this(job.getRootDir().toPath().resolve("bci-slack-episodes.json"), cancelled);
    }

    public EpisodeStore(Path path) {
        this(path, () -> false);
    }

    EpisodeStore(Path path, java.util.function.BooleanSupplier cancelled) {
        this.path = path;
        this.cancelled = cancelled;
    }

    void ensureActive() throws IOException {
        if (cancelled.getAsBoolean())
            throw new java.io.InterruptedIOException("Slack notification runtime is stopping");
    }

    public EpisodeEngine.State read() throws IOException {
        ensureActive();
        safePath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new EpisodeEngine.State();
        if (Files.size(path) > MAX_BYTES) throw new IOException("Slack episode state exceeds its safe size limit");
        byte[] bytes;
        try (var input = Files.newInputStream(path, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Slack episode state exceeds its safe size limit");
        EpisodeEngine.State state = mapper.readValue(bytes, EpisodeEngine.State.class);
        validateState(state);
        return state;
    }

    public void save(EpisodeEngine.State state) throws IOException {
        ensureActive();
        safePath();
        validateState(state);
        byte[] data = mapper.writeValueAsBytes(state);
        if (data.length > MAX_BYTES) throw new IOException("Slack episode state exceeds its safe size limit");
        try (AtomicFileWriter writer = new AtomicFileWriter(path, StandardCharsets.UTF_8)) {
            try {
                writer.write(new String(data, StandardCharsets.UTF_8));
                ensureActive();
                writer.commit();
            } finally {
                writer.abort();
            }
        }
    }

    private void safePath() throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("Slack episode state requires a parent directory");
        if (Files.isSymbolicLink(path)
                || Files.isSymbolicLink(parent)
                || Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe Slack episode state path");
    }

    private static void validateState(EpisodeEngine.State state) throws IOException {
        if (state == null
                || state.version != 1
                || state.lastBuild < 0
                || state.history == null
                || state.history.size() > 8) throw new IOException("Unsupported Slack episode state");
        if (state.optInAfter < -1
                || state.nextPreflight < 0
                || !bounded(state.configKey, 128)
                || !bounded(state.safeStatus, 200)) throw new IOException("Invalid Slack settings fence");
        Set<String> ids = new HashSet<>();
        for (EpisodeEngine.Episode episode : state.history) validate(episode, ids);
        if (state.active != null) validate(state.active, ids);
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static boolean bounded(String value, int limit) {
        return value == null || value.length() <= limit;
    }

    private static void validate(EpisodeEngine.Episode episode, Set<String> ids) throws IOException {
        if (episode == null
                || episode.id == null
                || episode.signature == null
                || episode.material == null
                || episode.deliveries == null
                || episode.deliveries.size() > 32) throw new IOException("Invalid Slack episode state");
        if (!uuid(episode.id)
                || !ids.add(episode.id)
                || episode.signature.length() > 128
                || episode.material.length() > 128
                || !bounded(episode.context, 16000)
                || !bounded(episode.channel, 100)
                || !bounded(episode.routeTeam, 100)
                || !bounded(episode.credentialId, 256)
                || !bounded(episode.configKey, 128)
                || episode.firstBuild < 1
                || episode.lastFailure < episode.firstBuild
                || episode.rootTs != null && !episode.rootTs.matches("[0-9]{1,20}\\.[0-9]{1,20}"))
            throw new IOException("Invalid Slack episode metadata");
        if (episode.check != null
                && (!bounded(episode.check.kind, 32)
                        || !bounded(episode.check.context, 128)
                        || !bounded(episode.check.module, 200)
                        || !bounded(episode.check.goal, 100)
                        || !bounded(episode.check.execution, 200)
                        || !bounded(episode.check.source, 2000)
                        || !bounded(episode.check.version, 100)))
            throw new IOException("Invalid Slack comparable check");
        for (EpisodeEngine.Delivery delivery : episode.deliveries) {
            if (delivery == null
                    || delivery.id == null
                    || delivery.kind == null
                    || delivery.status == null
                    || delivery.payload == null
                    || delivery.payload.length() > 40000) throw new IOException("Invalid Slack delivery state");
            if (!uuid(delivery.id)
                    || !ids.add(delivery.id)
                    || !Set.of("INITIAL", "MATERIAL", "RECOVERY").contains(delivery.kind)
                    || !Set.of("QUEUED", "LEASED", "SENT", "FAILED", "UNKNOWN_OUTCOME")
                            .contains(delivery.status)
                    || delivery.build < 1
                    || delivery.attempts < 0
                    || delivery.attempts > 5
                    || delivery.nextAttempt < 0
                    || delivery.aiDeadline < 0
                    || delivery.mappingRevision < 0
                    || !bounded(delivery.safeStatus, 200)) throw new IOException("Invalid Slack delivery metadata");
        }
    }
}

package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Deterministic transitions. Callers must save each transition before any network operation. */
public final class EpisodeEngine {
    private EpisodeEngine() {}

    public static final class State {
        public int version = 1;
        public int lastBuild;
        public int optInAfter = -1;
        public String configKey;
        public String safeStatus = "";
        public long nextPreflight;
        public Episode active;
        public List<Episode> history = new ArrayList<>();
    }

    public static final class Episode {
        public String id;
        public String signature;
        public String material;
        public int firstBuild;
        public int lastFailure;
        public int knownFirstBad;
        public boolean closed;
        public String channel;
        public String rootTs;
        public String context;
        public String routeTeam;
        public String credentialId;
        public String configKey;
        public ComparableCheck.Check check;
        public List<Delivery> deliveries = new ArrayList<>();
    }

    public static final class Delivery {
        public String id;
        public String kind;
        public int build;
        public String payload;
        public String status = "QUEUED";
        public int attempts;
        public long nextAttempt;
        public long aiDeadline;
        public boolean aiRequested;
        public long mappingRevision;
        public String safeStatus = "Waiting to send";
    }

    /** Build numbers and diagnostic line offsets deliberately do not participate. */
    public static String signature(FailureSignal signal) {
        String location = signal.getFile().replace('\\', '/');
        String specific = signal.isSpecific() ? "" : signal.getText().replaceAll("\\d+", "#");
        return digest(String.join(
                "|",
                signal.getCategory(),
                location,
                signal.getSymbol(),
                signal.getException(),
                signal.getMethod(),
                signal.getTest(),
                signal.getStage(),
                signal.getModule(),
                specific));
    }

    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns INITIAL, MATERIAL, or NONE. Payload must already be bounded, safe Block Kit. */
    public static String failure(
            State state,
            int build,
            String signature,
            String material,
            ComparableCheck.Check check,
            String channel,
            String payload,
            long now) {
        if (build <= state.lastBuild) return "NONE";
        state.lastBuild = build;
        Episode episode = state.active;
        if (episode == null || episode.closed || !episode.signature.equals(signature)) {
            if (episode != null) {
                if (state.history.size() >= 8) {
                    int removable = -1;
                    for (int i = 0; i < state.history.size(); i++) {
                        // Superseded episodes need no further delivery once every message has a receipt.
                        // Eviction does not claim recovery; unresolved deliveries must remain retained.
                        if (state.history.get(i).deliveries.stream().allMatch(d -> d.status.equals("SENT"))) {
                            removable = i;
                            break;
                        }
                    }
                    if (removable < 0) throw new IllegalStateException("Pending episode limit reached");
                    state.history.remove(removable);
                }
                state.history.add(episode);
            }
            episode = new Episode();
            episode.id = UUID.randomUUID().toString();
            episode.signature = signature;
            episode.material = material;
            episode.firstBuild = build;
            episode.lastFailure = build;
            episode.check = check;
            episode.channel = channel;
            state.active = episode;
            enqueue(episode, "INITIAL", build, payload, now);
            return "INITIAL";
        }
        episode.lastFailure = build;
        if (!episode.material.equals(material)) {
            episode.material = material;
            enqueue(episode, "MATERIAL", build, payload, now);
            return "MATERIAL";
        }
        return "NONE";
    }

    /** A successful build alone cannot close an episode. */
    public static boolean success(State state, int build, ComparableCheck.Proof proof, String payload, long now) {
        if (build <= state.lastBuild) return false;
        state.lastBuild = build;
        Episode episode = state.active;
        if (episode == null || episode.closed || proof == null || !proof.matches(episode.check)) return false;
        enqueue(episode, "RECOVERY", build, payload, now);
        episode.closed = true;
        return true;
    }

    private static void enqueue(Episode episode, String kind, int build, String payload, long now) {
        if (payload == null || payload.length() > 40000) throw new IllegalArgumentException("Invalid message size");
        if (episode.deliveries.size() >= 32) {
            // Keep unresolved deliveries and the root receipt; discard only an old acknowledged reply.
            int removable = -1;
            for (int i = 1; i < episode.deliveries.size(); i++) {
                if (episode.deliveries.get(i).status.equals("SENT")) {
                    removable = i;
                    break;
                }
            }
            if (removable < 0) throw new IllegalStateException("Pending message limit reached");
            episode.deliveries.remove(removable);
        }
        Delivery delivery = new Delivery();
        delivery.id = UUID.randomUUID().toString();
        delivery.kind = kind;
        delivery.build = build;
        delivery.payload = payload;
        delivery.nextAttempt = now;
        episode.deliveries.add(delivery);
    }

    /** Persist the returned lease before posting. A missing root never creates a replacement root. */
    public static Delivery claim(Episode episode, long now) {
        for (Delivery delivery : episode.deliveries) {
            if (delivery.status.equals("UNKNOWN_OUTCOME")) return null;
            if (delivery.status.equals("LEASED")) return null;
            if (delivery.status.equals("QUEUED")) {
                if (delivery.nextAttempt > now) return null;
                if (!delivery.kind.equals("INITIAL") && (episode.rootTs == null || episode.rootTs.isEmpty()))
                    return null;
                delivery.status = "LEASED";
                delivery.attempts++;
                return delivery;
            }
            if (delivery.kind.equals("INITIAL") && !delivery.status.equals("SENT")) return null;
        }
        return null;
    }

    public static void sent(Episode episode, Delivery delivery, String timestamp) {
        if (!delivery.status.equals("LEASED")) throw new IllegalStateException("No message lease");
        if (timestamp == null || !timestamp.matches("[0-9]{1,20}\\.[0-9]{1,20}")) {
            uncertain(delivery);
            return;
        }
        delivery.status = "SENT";
        delivery.safeStatus = "Sent";
        if (delivery.kind.equals("INITIAL")) episode.rootTs = timestamp;
    }

    public static void retry(Delivery delivery, long when) {
        if (delivery.attempts >= 5) {
            delivery.status = "FAILED";
            delivery.safeStatus = "Delivery retry limit reached";
        } else {
            delivery.status = "QUEUED";
            delivery.nextAttempt = when;
            delivery.safeStatus = "Waiting to retry";
        }
    }

    public static void uncertain(Delivery delivery) {
        delivery.status = "UNKNOWN_OUTCOME";
        delivery.safeStatus = "Delivery could not be confirmed; not resent";
    }

    /** Call only when no worker owns this job; a retained lease then has an uncertain outcome. */
    public static boolean restarted(State state) {
        boolean changed = false;
        List<Episode> episodes = new ArrayList<>(state.history);
        if (state.active != null) episodes.add(state.active);
        for (Episode episode : episodes)
            for (Delivery delivery : episode.deliveries) {
                if (delivery.status.equals("LEASED")) {
                    uncertain(delivery);
                    changed = true;
                }
            }
        return changed;
    }
}

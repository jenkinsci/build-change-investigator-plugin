package io.jenkins.plugins.changeinvestigator.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.identity.CorrelationClassifier;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.InvestigationKeyV1;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.LifecycleReducer;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.LifecycleStatus;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.SuppressionPolicy;
import io.jenkins.plugins.changeinvestigator.notification.persistence.AdmissionBudget;
import io.jenkins.plugins.changeinvestigator.notification.persistence.IngestionLedger;
import io.jenkins.plugins.changeinvestigator.notification.persistence.NotificationStore;
import io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Serial local ingestion. State, redacted semantic snapshots and intents share one atomic aggregate. */
public final class NotificationEngine {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private final UUID jobId;
    private final NotificationStore store;
    private final IngestionLedger ledger;
    private final Object lock;
    private final AdmissionBudget admissionBudget;
    private final Path jobRoot;

    public NotificationEngine(Path jobRoot, UUID jobId) throws IOException {
        this(jobRoot, jobId, jobRoot.resolve("notification-admission-scope"), jobId);
    }

    public NotificationEngine(Path jobRoot, UUID jobId, Path controllerRoot, UUID controllerId) throws IOException {
        this.jobId = jobId;
        this.jobRoot = jobRoot.toAbsolutePath().normalize();
        admissionBudget = new AdmissionBudget(controllerRoot, controllerId);
        store = new NotificationStore(jobRoot, jobId);
        ledger = new IngestionLedger(jobRoot, jobId);
        lock = LOCKS[Math.floorMod(jobId.hashCode(), LOCKS.length)];
    }

    /** Explicit activation boundary. Merely loading the plugin or viewing a build never calls this. */
    public void arm(long afterOrder) throws IOException {
        ledger.arm(afterOrder);
    }

    public List<NotificationInvestigationRecord> records() throws IOException {
        List<NotificationInvestigationRecord> result = new ArrayList<>();
        for (UUID id : store.caseIds()) {
            var saved = store.load(id);
            if (saved.isEmpty()) continue;
            try {
                var record = JSON.treeToValue(saved.get().aggregate(), NotificationInvestigationRecord.class);
                if (!record.jobId().equals(jobId) || !record.investigationId().equals(id))
                    throw new IllegalArgumentException("Notification storage scope mismatch");
                result.add(record);
            } catch (IOException | IllegalArgumentException e) {
                store.quarantine(id);
                throw new IOException("Invalid notification case", e);
            }
        }
        return List.copyOf(result);
    }

    /** No delivery destinations exist in Phase 1. Tests may supply opaque approved fake destinations. */
    public NotificationInvestigationRecord ingest(NotificationObservation input, List<UUID> destinations, long now)
            throws IOException {
        return ingestConfigured(
                input,
                destinations.stream()
                        .map(id -> new DestinationPolicy(id, 1, true, false))
                        .toList(),
                now);
    }

    public record DestinationPolicy(UUID id, long generation, boolean recovery, boolean aiUpdates) {
        public DestinationPolicy {
            if (id == null || generation < 1) throw new IllegalArgumentException("Invalid destination policy");
        }
    }

    public NotificationInvestigationRecord ingestConfigured(
            NotificationObservation input, List<DestinationPolicy> destinations, long now) throws IOException {
        if (destinations.size() > 10
                || now < 0
                || !jobId.toString().equals(input.context().jobId()))
            throw new IllegalArgumentException("Invalid ingestion scope");
        synchronized (lock) {
            IngestionLedger.Admission admission = ledger.register(input.observationId(), input.order());
            List<NotificationInvestigationRecord> cases = records();
            for (var existing : cases)
                if (existing.processedObservations().contains(input.observationId())) {
                    if (admission == IngestionLedger.Admission.PENDING)
                        ledger.markConsumed(
                                input.observationId(),
                                existing.investigationId(),
                                existing.lifecycle().revision());
                    return existing;
                }
            if (admission != IngestionLedger.Admission.PENDING) return null;
            var latest = cases.stream()
                    .max(java.util.Comparator.comparingLong(c -> c.lifecycle().currentOrder()))
                    .orElse(null);
            if (latest != null && input.order() < latest.lifecycle().currentOrder()) {
                // Late historical completion cannot rewind an already accepted observation sequence.
                ledger.markConsumed(
                        input.observationId(),
                        latest.investigationId(),
                        latest.lifecycle().revision());
                return latest;
            }
            var prior = cases.stream()
                    .filter(c -> ended(c) || c == latest)
                    .map(c -> new CorrelationClassifier.PriorCase(
                            c.investigationId().toString(), c.jobId().toString(), c.context(), c.signature(), ended(c)))
                    .toList();
            boolean different = latest != null && !latest.signature().sameIdentity(input.signature());
            var classification = CorrelationClassifier.classify(
                    jobId.toString(),
                    input.context(),
                    input.signature(),
                    prior,
                    new CorrelationClassifier.Continuity(input.completeHistory(), different, false));
            NotificationInvestigationRecord previous = null;
            if ("FAILURE".equals(input.result())) {
                if (classification.caseId() != null)
                    for (var candidate : cases)
                        if (candidate.investigationId().toString().equals(classification.caseId()))
                            previous = candidate;
                if (previous == null)
                    for (var candidate : cases)
                        if (candidate.key().episodeAnchorRunId().equals(input.runId()) && !ended(candidate))
                            previous = candidate;
            } else if (latest != null
                    && latest.context().digest().equals(input.context().digest())
                    && !ended(latest)) {
                previous = latest;
            }
            input = preserveEpisodeBoundary(input, previous);
            boolean comparable = previous == null
                    || previous.context().trusted()
                            && input.context().trusted()
                            && input.context().ancestryKnown()
                            && previous.context()
                                    .digest()
                                    .equals(input.context().digest());
            var observation = new LifecycleReducer.Observation(
                    input.observationId(),
                    input.runId(),
                    input.order(),
                    input.result(),
                    input.actualEvidence(),
                    comparable,
                    input.completeHistory() && comparable,
                    input.context().digest(),
                    input.affectedCheck(),
                    input.facts(),
                    input.coverage(),
                    input.recoveryChanges());
            var transition = LifecycleReducer.reduce(previous == null ? null : previous.lifecycle(), observation);
            if (transition.snapshot() == null) {
                ledger.markConsumed(input.observationId(), jobId, 1);
                return null;
            }
            if (transition.newCaseRequired()) {
                previous = null;
                transition = LifecycleReducer.reduce(null, observation);
            }
            InvestigationKeyV1 key = previous == null
                    ? InvestigationKeyV1.create(
                            jobId.toString(), input.context().digest(), input.signature(), input.runId())
                    : previous.key();
            UUID caseId = previous == null
                    ? UUID.nameUUIDFromBytes(key.digest().getBytes(StandardCharsets.UTF_8))
                    : previous.investigationId();
            if (previous != null
                    && transition.eventType() == null
                    && !ended(previous)
                    && destinations.stream().anyMatch(DestinationPolicy::aiUpdates)
                    && "AI_COMPLETE"
                            .equals(input.display().path("ai").path("state").asText())
                    && input.display().path("ai").path("summary").isTextual()
                    && previous.events().stream()
                            .noneMatch(
                                    e -> e.snapshot().path("ai").path("summary").isTextual())) {
                transition = new LifecycleReducer.Transition(
                        transition.snapshot(), "AI_AVAILABLE", List.of(), false, "AI_AVAILABLE");
            }
            var events = new ArrayList<NotificationEvent>(previous == null ? List.of() : previous.events());
            var destinationStates = new ArrayList<NotificationInvestigationRecord.DestinationState>(
                    previous == null ? List.of() : previous.destinations());
            long sequence = previous == null ? 0 : previous.semanticSequence();
            var policyAudit = new ArrayList<String>();
            var releasedReservations = new ArrayList<UUID>();
            if (transition.eventType() != null) {
                sequence++;
                NotificationEvent event =
                        event(input, previous, caseId, key, transition, classification, now, sequence);
                events.add(event);
                UUID eventId = UUID.fromString(event.snapshot().path("eventId").asText());
                for (DestinationPolicy approved :
                        destinations.stream().distinct().toList()) {
                    UUID destination = approved.id();
                    if ("RECOVERY_OBSERVED".equals(transition.eventType()) && !approved.recovery()) continue;
                    if ("AI_AVAILABLE".equals(transition.eventType()) && !approved.aiUpdates()) continue;
                    var old = destinationStates.stream()
                            .filter(d -> d.destinationId().equals(destination))
                            .findFirst()
                            .orElse(null);
                    if (old != null && old.generation() != approved.generation()) continue;
                    var policy = old == null ? SuppressionPolicy.State.empty() : old.policy();
                    SuppressionPolicy.Kind kind = transition.eventType().equals("INVESTIGATION_OPENED")
                            ? SuppressionPolicy.Kind.INITIAL
                            : transition.eventType().equals("RECOVERY_OBSERVED")
                                    ? SuppressionPolicy.Kind.RECOVERY
                                    : transition.eventType().equals("AI_AVAILABLE")
                                            ? SuppressionPolicy.Kind.AI_AVAILABLE
                                            : SuppressionPolicy.Kind.MATERIAL;
                    boolean admissionLimited = input.signature().quality() == FailureSignatureV1.Quality.UNRESOLVED
                            && (old == null
                                    || (!policy.initialAccepted()
                                            && old.intents().isEmpty()))
                            && !initialAdmissionEligible(cases, destination, now);
                    var offered = admissionLimited
                            ? new SuppressionPolicy.Decision(policy, false, -1, "INITIAL_ADMISSION_LIMIT")
                            : SuppressionPolicy.offer(
                                    policy,
                                    eventId.toString(),
                                    kind,
                                    input.facts().fingerprint()
                                            + ("AI_AVAILABLE".equals(transition.eventType()) ? ":ai-available" : ""),
                                    now,
                                    approved.aiUpdates());
                    policyAudit.add(offered.reason());
                    List<OutboxIntent> intents = new ArrayList<>(old == null ? List.of() : old.intents());
                    String pendingId = offered.state().pending() == null
                            ? null
                            : offered.state().pending().eventId();
                    // Supersession is recorded before compacting terminal, never-attempted intent history.
                    for (int i = 0; i < intents.size(); i++) {
                        OutboxIntent intent = intents.get(i);
                        if (intent.state() == OutboxIntent.State.QUEUED
                                && intent.attempts() == 0
                                && !intent.eventId().toString().equals(pendingId)) {
                            intents.set(
                                    i,
                                    new OutboxIntent(
                                            intent.deliveryId(),
                                            intent.eventId(),
                                            intent.destinationId(),
                                            intent.destinationGeneration(),
                                            intent.rendererVersion(),
                                            OutboxIntent.State.CANCELLED,
                                            0,
                                            intent.createdAt(),
                                            intent.nextAttemptAt(),
                                            null,
                                            0,
                                            null,
                                            "SUPERSEDED_UNATTEMPTED"));
                            policyAudit.add("SUPERSEDED_UNATTEMPTED");
                            releasedReservations.add(intent.deliveryId());
                        }
                    }
                    if (!admissionLimited && eventId.toString().equals(pendingId)) {
                        // The per-destination pending kind retains INITIAL even when this semantic event is an update.
                        OutboxIntent queued =
                                OutboxIntent.queued(caseId, eventId, destination, approved.generation(), 1, now);
                        long next = offered.eligibleAt() < 0 ? Long.MAX_VALUE : Math.max(now, offered.eligibleAt());
                        admissionBudget.reportUsage(jobId, measuredStoreBytes());
                        if (admissionBudget.reserve(
                                queued.deliveryId(),
                                destination,
                                kind == SuppressionPolicy.Kind.RECOVERY,
                                0,
                                JSON.writeValueAsBytes(event.snapshot()).length)) {
                            intents.add(new OutboxIntent(
                                    queued.deliveryId(),
                                    queued.eventId(),
                                    queued.destinationId(),
                                    queued.destinationGeneration(),
                                    queued.rendererVersion(),
                                    queued.state(),
                                    queued.attempts(),
                                    queued.createdAt(),
                                    next,
                                    null,
                                    0,
                                    null,
                                    null));
                        } else policyAudit.add("DURABLE_ADMISSION_LIMIT");
                    }
                    // Cancellations remain in the bounded audit. Attempted receipts and uncertain outcomes stay
                    // immutable.
                    while (intents.size() > 16) {
                        int removable = -1;
                        for (int i = 1; i < intents.size(); i++) {
                            if (intents.get(i).state() == OutboxIntent.State.CANCELLED
                                    && intents.get(i).attempts() == 0) {
                                removable = i;
                                break;
                            }
                        }
                        if (removable < 0)
                            throw new IOException("Notification attempted-intent retention limit reached");
                        intents.remove(removable);
                        policyAudit.add("CANCELLED_INTENT_COMPACTED");
                    }
                    if (old != null) destinationStates.remove(old);
                    destinationStates.add(new NotificationInvestigationRecord.DestinationState(
                            destination,
                            approved.generation(),
                            offered.state(),
                            intents,
                            old == null ? List.of() : old.submissions()));
                }
                retainEventSnapshots(events, destinationStates);
            }
            var processed = new ArrayList<String>(previous == null ? List.of() : previous.processedObservations());
            processed.add(input.observationId());
            if (processed.size() > 200) processed.remove(0);
            var audit = new ArrayList<NotificationInvestigationRecord.Audit>(
                    previous == null ? List.of() : previous.audit());
            for (String code : policyAudit)
                audit.add(new NotificationInvestigationRecord.Audit(
                        code, now, transition.snapshot().revision()));
            audit.add(new NotificationInvestigationRecord.Audit(
                    transition.reason(), now, transition.snapshot().revision()));
            while (audit.size() > 200) audit.remove(0);
            var record = new NotificationInvestigationRecord(
                    1,
                    caseId,
                    jobId,
                    previous == null ? input.context() : previous.context(),
                    previous == null ? input.signature() : previous.signature(),
                    key,
                    transition.snapshot(),
                    sequence,
                    input.display().path("ai").path("state").asText("AI_NOT_CONFIGURED"),
                    processed,
                    events,
                    destinationStates,
                    audit);
            long expected =
                    store.load(caseId).map(NotificationStore.Snapshot::revision).orElse(0L);
            // Reserve conservative replacement space before mutation. A failed write leaves a safe overcount.
            admissionBudget.reportUsage(jobId, measuredStoreBytes() + JSON.writeValueAsBytes(record).length + 1024L);
            store.update(caseId, expected, ignored -> JSON.valueToTree(record));
            for (UUID released : releasedReservations) admissionBudget.release(released);
            admissionBudget.reportUsage(jobId, measuredStoreBytes());
            ledger.markConsumed(
                    input.observationId(), caseId, transition.snapshot().revision());
            return record;
        }
    }

    public NotificationInvestigationRecord updateDestination(
            UUID caseId,
            UUID destinationId,
            java.util.function.UnaryOperator<NotificationInvestigationRecord.DestinationState> change,
            String safeCode,
            long now)
            throws IOException {
        synchronized (lock) {
            var saved = store.load(caseId).orElseThrow(() -> new IOException("Notification case unavailable"));
            var record = JSON.treeToValue(saved.aggregate(), NotificationInvestigationRecord.class);
            if (!record.jobId().equals(jobId) || !record.investigationId().equals(caseId))
                throw new IOException("Notification scope mismatch");
            var states = new ArrayList<NotificationInvestigationRecord.DestinationState>(record.destinations());
            int index = -1;
            for (int i = 0; i < states.size(); i++)
                if (states.get(i).destinationId().equals(destinationId)) index = i;
            if (index < 0) throw new IOException("Notification destination unavailable");
            var updated = change.apply(states.get(index));
            if (!updated.destinationId().equals(destinationId))
                throw new IOException("Notification destination changed");
            states.set(index, updated);
            var audit = new ArrayList<NotificationInvestigationRecord.Audit>(record.audit());
            audit.add(new NotificationInvestigationRecord.Audit(
                    safeCode, now, record.lifecycle().revision()));
            while (audit.size() > 200) audit.remove(0);
            var result = new NotificationInvestigationRecord(
                    1,
                    caseId,
                    jobId,
                    record.context(),
                    record.signature(),
                    record.key(),
                    record.lifecycle(),
                    record.semanticSequence(),
                    record.aiState(),
                    record.processedObservations(),
                    record.events(),
                    states,
                    audit);
            store.update(caseId, saved.revision(), ignored -> JSON.valueToTree(result));
            for (var state : result.destinations())
                for (var intent : state.intents()) {
                    if (intent.state() == OutboxIntent.State.SENT
                            || intent.state() == OutboxIntent.State.CANCELLED
                            || intent.state() == OutboxIntent.State.FAILED_PERMANENT
                            || intent.state() == OutboxIntent.State.SUPPRESSED)
                        admissionBudget.release(intent.deliveryId());
                }
            return result;
        }
    }

    private static NotificationObservation preserveEpisodeBoundary(
            NotificationObservation input, NotificationInvestigationRecord previous) {
        if (previous == null || ended(previous) || previous.events().isEmpty()) return input;
        long anchor =
                previous.events().get(0).snapshot().path("build").path("number").asLong(-1);
        ObjectNode display = input.display();
        long candidateGood =
                display.path("boundary").path("lastKnownGood").path("number").asLong(-1);
        if (anchor < 0 || candidateGood < anchor) return input;
        // A success inside this still-open episode has not passed recovery and cannot become its baseline.
        var retainedBoundary =
                previous.events().get(previous.events().size() - 1).snapshot().get("boundary");
        display.set("boundary", retainedBoundary.deepCopy());
        var facts = input.facts();
        var retainedFacts = new io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts(
                previous.lifecycle().facts().verifiedFirstBadRunId(),
                facts.topCandidates(),
                facts.relevantCommits(),
                facts.responderIdentity(),
                facts.recoveryCandidateIdentity(),
                facts.correctionIdentity());
        return new NotificationObservation(
                input.observationId(),
                input.runId(),
                input.order(),
                input.result(),
                input.actualEvidence(),
                input.completeHistory(),
                input.context(),
                input.signature(),
                input.affectedCheck(),
                retainedFacts,
                input.coverage(),
                input.recoveryChanges(),
                display);
    }

    private long measuredStoreBytes() throws IOException {
        Path directory = jobRoot.resolve("bci-notifications");
        if (!java.nio.file.Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0;
        long total = 0;
        int count = 0;
        try (var paths = java.nio.file.Files.walk(directory, 12)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++count > 2000 || java.nio.file.Files.isSymbolicLink(path))
                    throw new IOException("Notification storage inventory limit");
                if (java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    total += java.nio.file.Files.size(path);
                    if (total > AdmissionBudget.MAX_STORE_BYTES)
                        throw new IOException("Notification storage budget exceeded");
                }
            }
        }
        return total;
    }

    private NotificationEvent event(
            NotificationObservation input,
            NotificationInvestigationRecord previous,
            UUID caseId,
            InvestigationKeyV1 key,
            LifecycleReducer.Transition transition,
            CorrelationClassifier.Classification classification,
            long now,
            long sequence) {
        ObjectNode event = input.display();
        if (previous != null
                && !"FAILURE".equals(input.result())
                && !previous.events().isEmpty()) {
            ObjectNode prior =
                    previous.events().get(previous.events().size() - 1).snapshot();
            for (String field : List.of(
                    "failureSummary",
                    "boundary",
                    "topCandidates",
                    "allChangeCount",
                    "changesComplete",
                    "suggestedChecks")) event.set(field, prior.get(field));
        }
        event.put("eventVersion", 1)
                .put(
                        "eventId",
                        UUID.nameUUIDFromBytes((caseId + ":" + sequence).getBytes(StandardCharsets.UTF_8))
                                .toString())
                .put("investigationId", caseId.toString())
                .put("caseRevision", transition.snapshot().revision())
                .put("eventType", transition.eventType())
                .put("createdAt", Instant.ofEpochMilli(now).toString())
                .put("jobId", jobId.toString())
                .put("contextDigest", input.context().digest())
                .put("lifecycleState", transition.snapshot().status().name())
                .put("correlationStatus", classification.status().name());
        if (event.path("ai").path("summary").isTextual()
                && "AI_COMPLETE".equals(event.path("ai").path("state").asText())) {
            ((ObjectNode) event.get("ai"))
                    .put("evidenceRevision", transition.snapshot().revision());
        }
        event.putObject("investigationKey")
                .put("keyVersion", 1)
                .put("digest", key.digest())
                .put("episodeAnchorRunId", key.episodeAnchorRunId());
        event.set("failureSignature", JSON.valueToTree(previous == null ? input.signature() : previous.signature()));
        if ((previous == null ? input.signature() : previous.signature()).quality()
                == io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1.Quality.UNRESOLVED)
            event.put("correlationStatus", "UNKNOWN");
        var reasons = event.putArray("materialReasons");
        transition.materialReasons().forEach(reason -> reasons.add(reason.name()));
        var related = event.putArray("relatedInvestigations");
        classification.historicalCaseIds().stream()
                .limit(5)
                .forEach(id -> related.addObject().put("investigationId", id).put("relationship", "RECURRENCE"));
        event.putNull("confirmation");
        ObjectNode recovery = event.putObject("recovery");
        recovery.put("assessment", transition.snapshot().recoveryAssessment().name());
        recovery.put("coverage", transition.eventType().equals("RECOVERY_OBSERVED") ? "VERIFIED" : "UNKNOWN");
        recovery.set(
                "build", transition.eventType().equals("RECOVERY_OBSERVED") ? event.get("build") : JSON.nullNode());
        var candidates = recovery.putArray("candidateIds");
        recovery.put("basis", "");
        if (transition.snapshot().recoveryAssessment().name().equals("LIKELY_RECOVERY_CHANGE")) {
            input.recoveryChanges().stream()
                    .filter(c ->
                            c.presentInSuccessfulHistory() && !c.relationship().isBlank())
                    .limit(1)
                    .forEach(c -> {
                        candidates.add(
                                io.jenkins.plugins.changeinvestigator.notification.identity.IdentityCanonicalizer
                                        .digest(c.commit()));
                        recovery.put("basis", c.relationship());
                    });
        }
        return NotificationEvent.freeze(event);
    }

    private static boolean initialAdmissionEligible(
            List<NotificationInvestigationRecord> cases, UUID destination, long now) {
        List<Long> starts = new ArrayList<>();
        for (var record : cases) {
            if (record.signature().quality() != FailureSignatureV1.Quality.UNRESOLVED) continue;
            for (var state : record.destinations()) {
                if (!state.destinationId().equals(destination)) continue;
                state.intents().stream()
                        .filter(i -> i.state() != OutboxIntent.State.SUPPRESSED)
                        .mapToLong(OutboxIntent::createdAt)
                        .min()
                        .ifPresent(starts::add);
            }
        }
        long hour = starts.stream().filter(t -> t > now - 3_600_000).count();
        long day = starts.stream().filter(t -> t > now - SuppressionPolicy.DAY).count();
        return hour < io.jenkins.plugins.changeinvestigator.notification.lifecycle.InitialAdmissionPolicy.HOURLY_LIMIT
                && day
                        < io.jenkins.plugins.changeinvestigator.notification.lifecycle.InitialAdmissionPolicy
                                .DAILY_LIMIT;
    }

    private static void retainEventSnapshots(
            List<NotificationEvent> events, List<NotificationInvestigationRecord.DestinationState> destinations)
            throws IOException {
        if (events.isEmpty()) return;
        java.util.Set<String> keep = new java.util.HashSet<>();
        keep.add(events.get(0).snapshot().path("eventId").asText());
        keep.add(events.get(events.size() - 1).snapshot().path("eventId").asText());
        for (var destination : destinations)
            for (var intent : destination.intents()) {
                if (intent.attempts() > 0 || intent.state() != OutboxIntent.State.CANCELLED)
                    keep.add(intent.eventId().toString());
            }
        events.removeIf(e -> !keep.contains(e.snapshot().path("eventId").asText()));
        if (events.size() > 32) throw new IOException("Notification referenced-event retention limit reached");
    }

    private static boolean ended(NotificationInvestigationRecord record) {
        return record.lifecycle().status() == LifecycleStatus.RECOVERED
                || record.lifecycle().status() == LifecycleStatus.CONFIRMED_RESOLUTION
                || record.lifecycle().status() == LifecycleStatus.CLOSED;
    }
}

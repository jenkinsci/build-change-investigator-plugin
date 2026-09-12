package io.jenkins.plugins.changeinvestigator.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import io.jenkins.plugins.changeinvestigator.notification.feedback.*;
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
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                            .maxNestingDepth(24)
                            .maxStringLength(32768)
                            .maxNumberLength(64)
                            .build())
                    .build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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

    /** Bounded read-only access: no directory creation, quarantine or admission bookkeeping. */
    public static java.util.Optional<NotificationInvestigationRecord> peek(
            Path jobRoot, UUID expectedJobId, UUID caseId) throws IOException {
        Path base = jobRoot.toAbsolutePath().normalize();
        Path directory = base.resolve("bci-notifications").resolve("cases");
        Path file = directory.resolve(caseId + ".json");
        for (Path current = file; current != null; current = current.getParent())
            if (java.nio.file.Files.isSymbolicLink(current)) throw new IOException("Notification path unavailable");
        if (!java.nio.file.Files.exists(file)) return java.util.Optional.empty();
        if (!java.nio.file.Files.isRegularFile(file)
                || java.nio.file.Files.size(file) > NotificationStore.MAX_RECORD_BYTES)
            throw new IOException("Notification record unavailable");
        byte[] bytes;
        try (var input = java.nio.file.Files.newInputStream(file)) {
            bytes = input.readNBytes(NotificationStore.MAX_RECORD_BYTES + 1);
        }
        if (bytes.length > NotificationStore.MAX_RECORD_BYTES) throw new IOException("Notification record unavailable");
        try {
            var envelope = JSON.readTree(bytes);
            if (envelope == null
                    || !envelope.isObject()
                    || !envelope.path("aggregate").isObject()
                    || !envelope.path("revision").isIntegralNumber()
                    || envelope.path("revision").asLong() < 1
                    || envelope.path("schemaVersion").asInt() != 1
                    || !expectedJobId.toString().equals(envelope.path("jobId").asText())
                    || !caseId.toString().equals(envelope.path("caseId").asText()))
                throw new IOException("Notification scope mismatch");
            var value = JSON.treeToValue(envelope.path("aggregate"), NotificationInvestigationRecord.class);
            if (!value.jobId().equals(expectedJobId) || !value.investigationId().equals(caseId))
                throw new IOException("Notification scope mismatch");
            return java.util.Optional.of(value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Notification record unavailable");
        }
    }

    /** Authorization and nonce replay are checked under the same lock as evidence and delivery claims. */
    public FeedbackResult feedback(
            UUID caseId,
            FeedbackRequest request,
            FeedbackActor actor,
            java.util.function.BooleanSupplier authorized,
            java.util.function.Predicate<String> withheld,
            long now)
            throws IOException {
        java.util.Objects.requireNonNull(request);
        java.util.Objects.requireNonNull(actor);
        java.util.Objects.requireNonNull(authorized);
        java.util.Objects.requireNonNull(withheld);
        if (now < 0) throw new IllegalArgumentException("Invalid feedback time");
        synchronized (lock) {
            if (!authorized.getAsBoolean())
                throw new org.springframework.security.access.AccessDeniedException("Feedback permission required");
            var saved = store.load(caseId).orElseThrow(() -> new IOException("Notification case unavailable"));
            var record = JSON.treeToValue(saved.aggregate(), NotificationInvestigationRecord.class);
            if (!record.jobId().equals(jobId) || !record.investigationId().equals(caseId))
                throw new IllegalArgumentException("Feedback scope mismatch");
            String digest = io.jenkins.plugins.changeinvestigator.notification.identity.IdentityCanonicalizer.digest(
                    JSON.writeValueAsString(request));
            for (var action : record.feedback().records())
                if (action.request().actionId().equals(request.actionId())) {
                    if (!action.actor().id().equals(actor.id())
                            || !action.requestDigest().equals(digest))
                        throw new IOException("Feedback action replay conflict");
                    return new FeedbackResult(action.afterRevision(), action.recordId(), true, "APPLIED");
                }
            if (record.caseRevision() != request.expectedRevision())
                throw new IOException("Feedback revision conflict");
            requireEvidence(record, request);
            if (record.feedback().records().size() >= FeedbackState.MAX_RECORDS)
                throw new IOException("Feedback history limit reached");
            var destinations = request.destinations().isEmpty()
                    ? record.destinations().stream()
                            .map(NotificationInvestigationRecord.DestinationState::destinationId)
                            .toList()
                    : request.destinations();
            if (!record.destinations().stream()
                    .map(NotificationInvestigationRecord.DestinationState::destinationId)
                    .toList()
                    .containsAll(destinations)) throw new IllegalArgumentException("Feedback destination unavailable");
            checkDisclosure(JSON.valueToTree(request), withheld);
            checkDisclosure(JSON.valueToTree(actor), withheld);
            FeedbackRequest safe = new FeedbackRequest(
                    request.action(),
                    request.actionId(),
                    request.expectedRevision(),
                    request.candidateId(),
                    request.evidenceRevision(),
                    request.recoveryBuild(),
                    SafeContent.text(request.correctiveAction(), 400),
                    SafeContent.text(request.validationBasis(), 400),
                    request.fixCommit(),
                    SafeContent.text(request.note(), 500),
                    request.confirmationId(),
                    destinations,
                    request.muteUntil());
            actor = new FeedbackActor(actor.id(), SafeContent.text(actor.label(), 100));
            long revision = Math.addExact(record.caseRevision(), 1);
            UUID recordId = UUID.randomUUID();
            ObjectNode latest = record.events().get(record.events().size() - 1).snapshot();
            var lifecycle = record.lifecycle();
            ConfirmationRecord confirmation = null;
            var current = (safe.action() == FeedbackRequest.Action.CORRECT_CONFIRMATION
                            || safe.action() == FeedbackRequest.Action.REVOKE_CONFIRMATION)
                    ? java.util.stream.Stream.of(
                                    record.feedback().currentCause(),
                                    record.feedback().currentResolution())
                            .flatMap(java.util.Optional::stream)
                            .filter(c -> c.recordId().equals(safe.confirmationId()))
                            .findFirst()
                            .orElse(null)
                    : (safe.action() == FeedbackRequest.Action.CONFIRM_CAUSE
                                    ? record.feedback().currentCause()
                                    : record.feedback().currentResolution())
                            .orElse(null);
            String eventType = null;
            String reviewAction = null;
            switch (safe.action()) {
                case ACKNOWLEDGE -> {}
                case NOT_RELATED -> {
                    requireCandidate(latest, safe.candidateId());
                    requireEvidence(record, safe);
                    if (!record.feedback().notRelated(safe.candidateId(), safe.evidenceRevision())
                            && actionableCandidate(latest, safe.candidateId())) {
                        eventType = "INVESTIGATION_UPDATED";
                        reviewAction = "CANDIDATE_NOT_RELATED";
                    }
                }
                case CONFIRM_CAUSE, CONFIRM_RESOLUTION, CORRECT_CONFIRMATION, REVOKE_CONFIRMATION -> {
                    boolean successor = safe.action() == FeedbackRequest.Action.CORRECT_CONFIRMATION
                            || safe.action() == FeedbackRequest.Action.REVOKE_CONFIRMATION;
                    if (successor && (current == null || !current.recordId().equals(safe.confirmationId())))
                        throw new IOException("Current confirmation required");
                    if (!successor && current != null)
                        throw new IOException("Correct or revoke the current confirmation first");
                    boolean resolution = safe.action() == FeedbackRequest.Action.CONFIRM_RESOLUTION
                            || successor && current.kind() == ConfirmationRecord.Kind.RESOLUTION;
                    if (!resolution
                            && (!safe.recoveryBuild().isBlank()
                                    || !safe.fixCommit().isBlank()))
                        throw new IllegalArgumentException("Cause confirmation cannot carry fix references");
                    boolean revoked = safe.action() == FeedbackRequest.Action.REVOKE_CONFIRMATION;
                    if (safe.validationBasis().isBlank())
                        throw new IllegalArgumentException("Validation basis required");
                    if (!revoked) {
                        requireEvidence(record, safe);
                        if (!safe.candidateId().isBlank()) requireCandidate(latest, safe.candidateId());
                        if (safe.correctiveAction().isBlank())
                            throw new IllegalArgumentException("Corrective action or defined cause required");
                        if (resolution) requireRecovery(record, latest, safe);
                        if (!safe.fixCommit().isBlank()
                                && (!resolution || !verifiedFixCommit(latest, safe.fixCommit())))
                            throw new IllegalArgumentException("Fix commit is not part of retained evidence");
                    }
                    confirmation = new ConfirmationRecord(
                            recordId,
                            revision,
                            caseId,
                            now,
                            actor.id(),
                            actor.label(),
                            "AUTHENTICATED_JENKINS_ACTION",
                            revoked ? current.causeCandidateId() : safe.candidateId(),
                            revoked ? current.fixCommit() : safe.fixCommit(),
                            revoked ? current.fixBuild() : safe.recoveryBuild(),
                            safe.note(),
                            revoked ? current.correctiveAction() : safe.correctiveAction(),
                            safe.validationBasis(),
                            successor ? current.recordId() : null,
                            resolution ? ConfirmationRecord.Kind.RESOLUTION : ConfirmationRecord.Kind.CAUSE,
                            revoked);
                    if (resolution)
                        lifecycle = humanLifecycle(
                                lifecycle, revoked ? LifecycleStatus.RECOVERED : LifecycleStatus.CONFIRMED_RESOLUTION);
                    eventType =
                            successor ? "CORRECTION" : resolution ? "RESOLUTION_CONFIRMED" : "INVESTIGATION_UPDATED";
                    reviewAction =
                            revoked ? "CONFIRMATION_REVOKED" : successor ? "CONFIRMATION_CORRECTED" : "CAUSE_CONFIRMED";
                }
                case REOPEN -> {
                    if (lifecycle.status() != LifecycleStatus.CLOSED
                            || lifecycle.recoveryAssessment()
                                    != io.jenkins.plugins.changeinvestigator.notification.lifecycle.RecoveryAssessment
                                            .NONE
                            || record.audit().stream().noneMatch(a -> a.code().equals("ADMINISTRATIVELY_CLOSED"))
                            || safe.validationBasis().isBlank())
                        throw new IOException("Investigation cannot be reopened");
                    lifecycle = humanLifecycle(lifecycle, LifecycleStatus.ACTIVE);
                    eventType = "INVESTIGATION_UPDATED";
                    reviewAction = "INVESTIGATION_REOPENED";
                }
                case MUTE -> {
                    if (safe.note().isBlank() && safe.validationBasis().isBlank())
                        throw new IllegalArgumentException("Mute reason required");
                }
                case UNMUTE -> {}
            }
            var history = new ArrayList<FeedbackRecord>(record.feedback().records());
            history.add(new FeedbackRecord(
                    recordId, safe, actor, digest, now, record.caseRevision(), revision, confirmation));
            var feedback = new FeedbackState(history);
            var events = new ArrayList<NotificationEvent>(record.events());
            long sequence = record.semanticSequence();
            NotificationEvent projected = null;
            if (eventType != null) {
                sequence++;
                projected = feedbackEvent(
                        latest,
                        caseId,
                        revision,
                        record.evidenceRevision(),
                        sequence,
                        now,
                        eventType,
                        reviewAction,
                        lifecycle,
                        feedback.currentResolution().orElse(null),
                        recordId,
                        actor,
                        safe,
                        confirmation == null ? null : confirmation.kind().name());
                events.add(projected);
            }
            var states = new ArrayList<NotificationInvestigationRecord.DestinationState>();
            var released = new ArrayList<UUID>();
            var reserved = new ArrayList<UUID>();
            boolean committed = false;
            boolean deliveryUnavailable = false;
            try {
                for (var destination : record.destinations()) {
                    if (!destinations.contains(destination.destinationId())) {
                        states.add(destination);
                        continue;
                    }
                    var policy = destination.policy();
                    var intents = new ArrayList<OutboxIntent>(destination.intents());
                    boolean mutedAction = safe.action() == FeedbackRequest.Action.MUTE
                            || safe.action() == FeedbackRequest.Action.UNMUTE;
                    if (mutedAction) {
                        long until = safe.action() == FeedbackRequest.Action.UNMUTE
                                ? 0
                                : safe.muteUntil() == -1
                                        ? Long.MAX_VALUE
                                        : safe.muteUntil() == 0
                                                ? Math.addExact(now, SuppressionPolicy.DAY)
                                                : safe.muteUntil();
                        if (safe.action() == FeedbackRequest.Action.MUTE && until <= now)
                            throw new IllegalArgumentException("Future mute expiry required");
                        policy = mutePolicy(policy, until, null);
                        cancelPending(intents, released, "HUMAN_MUTE_CHANGED");
                    } else if (projected != null) {
                        boolean correction = "CORRECTION".equals(eventType);
                        boolean published = correction && assertionSent(record, destination, safe.confirmationId());
                        if (correction) {
                            cancelAssertion(record, intents, released, safe.confirmationId());
                            if (policy.pending() != null) {
                                String pending = policy.pending().eventId();
                                if (intents.stream()
                                        .anyMatch(i -> i.eventId().toString().equals(pending)
                                                && i.state() == OutboxIntent.State.CANCELLED))
                                    policy = mutePolicy(policy, policy.mutedUntil(), null);
                            }
                        }
                        if (!correction || published) {
                            var kind = correction
                                    ? SuppressionPolicy.Kind.CORRECTION
                                    : "RESOLUTION_CONFIRMED".equals(eventType)
                                            ? SuppressionPolicy.Kind.CONFIRMATION
                                            : SuppressionPolicy.Kind.MATERIAL;
                            String eventId =
                                    projected.snapshot().path("eventId").asText();
                            var offered = correction
                                    ? SuppressionPolicy.offerCriticalCorrection(
                                            policy, eventId, "human:" + recordId, now)
                                    : SuppressionPolicy.offer(policy, eventId, kind, "human:" + recordId, now);
                            policy = offered.state();
                            if (policy.pending() != null
                                    && policy.pending().eventId().equals(eventId)) {
                                cancelUnattempted(intents, released, "SUPERSEDED_BY_HUMAN_REVIEW");
                                var queued = OutboxIntent.queued(
                                        caseId,
                                        UUID.fromString(eventId),
                                        destination.destinationId(),
                                        destination.generation(),
                                        1,
                                        now);
                                long due =
                                        offered.eligibleAt() < 0 ? Long.MAX_VALUE : Math.max(now, offered.eligibleAt());
                                if (correction) {
                                    while (intents.size() >= 16) {
                                        int removable = -1;
                                        for (int i = 1; i < intents.size(); i++)
                                            if (intents.get(i).state() == OutboxIntent.State.CANCELLED
                                                    && intents.get(i).attempts() == 0) {
                                                removable = i;
                                                break;
                                            }
                                        if (removable < 0) break;
                                        intents.remove(removable);
                                    }
                                }
                                boolean admitted = (!correction || intents.size() < 16)
                                        && admissionBudget.reserve(
                                                queued.deliveryId(),
                                                destination.destinationId(),
                                                kind == SuppressionPolicy.Kind.CONFIRMATION
                                                        || kind == SuppressionPolicy.Kind.CORRECTION,
                                                0,
                                                JSON.writeValueAsBytes(projected.snapshot()).length);
                                if (admitted) {
                                    reserved.add(queued.deliveryId());
                                    intents.add(new OutboxIntent(
                                            queued.deliveryId(),
                                            queued.eventId(),
                                            queued.destinationId(),
                                            queued.destinationGeneration(),
                                            1,
                                            queued.state(),
                                            0,
                                            now,
                                            due,
                                            null,
                                            0,
                                            null,
                                            correction ? "CRITICAL_CORRECTION" : null));
                                } else if (correction) {
                                    deliveryUnavailable = true;
                                    policy = mutePolicy(policy, policy.mutedUntil(), null);
                                } else throw new IOException("Feedback delivery admission limit reached");
                            }
                        }
                    }
                    while (intents.size() > 16) {
                        int removable = -1;
                        for (int i = 1; i < intents.size(); i++)
                            if (intents.get(i).state() == OutboxIntent.State.CANCELLED
                                    && intents.get(i).attempts() == 0) {
                                removable = i;
                                break;
                            }
                        if (removable < 0) throw new IOException("Feedback intent retention limit reached");
                        intents.remove(removable);
                    }
                    var retainedIds =
                            intents.stream().map(OutboxIntent::deliveryId).toList();
                    states.add(new NotificationInvestigationRecord.DestinationState(
                            destination.destinationId(),
                            destination.generation(),
                            policy,
                            intents,
                            destination.submissions().stream()
                                    .filter(s -> retainedIds.contains(s.deliveryId()))
                                    .toList(),
                            destination.transport()));
                }
                if (events.size() > 32) throw new IOException("Feedback evidence retention limit reached");
                var audit = new ArrayList<NotificationInvestigationRecord.Audit>(record.audit());
                audit.add(new NotificationInvestigationRecord.Audit(
                        "HUMAN_" + safe.action().name(), now, revision));
                if (deliveryUnavailable)
                    audit.add(new NotificationInvestigationRecord.Audit(
                            "HUMAN_CORRECTION_DELIVERY_UNAVAILABLE", now, revision));
                while (audit.size() > 200) audit.remove(0);
                var changed = new NotificationInvestigationRecord(
                        1,
                        caseId,
                        jobId,
                        record.context(),
                        record.signature(),
                        record.key(),
                        lifecycle,
                        sequence,
                        record.aiState(),
                        record.processedObservations(),
                        events,
                        states,
                        audit,
                        feedback);
                checkDisclosure(JSON.valueToTree(changed), withheld);
                for (var event : events) checkDisclosure(event.snapshot(), withheld);
                store.update(caseId, saved.revision(), ignored -> JSON.valueToTree(changed));
                committed = true;
                for (UUID id : released) admissionBudget.release(id);
                return new FeedbackResult(revision, recordId, false, "APPLIED");
            } finally {
                if (!committed) for (UUID id : reserved) admissionBudget.release(id);
            }
        }
    }

    private static void requireEvidence(NotificationInvestigationRecord record, FeedbackRequest request)
            throws IOException {
        if (request.evidenceRevision() != record.evidenceRevision())
            throw new IOException("Feedback evidence revision conflict");
    }

    private static void requireCandidate(ObjectNode latest, String candidateId) {
        boolean found = false;
        for (var candidate : latest.path("topCandidates"))
            found |= candidate.path("candidateId").asText().equals(candidateId);
        if (candidateId.isBlank() || !found)
            throw new IllegalArgumentException("Candidate is not in this investigation evidence");
    }

    private static boolean actionableCandidate(ObjectNode latest, String candidateId) {
        for (var candidate : latest.path("topCandidates"))
            if (candidate.path("candidateId").asText().equals(candidateId))
                return List.of("STRONG", "MODERATE")
                        .contains(candidate.path("strength").asText());
        return false;
    }

    private static boolean verifiedFixCommit(ObjectNode latest, String commit) {
        if (commit.length() > 128
                || !"VERIFIED".equals(latest.path("recovery").path("coverage").asText())) return false;
        String digest =
                io.jenkins.plugins.changeinvestigator.notification.identity.IdentityCanonicalizer.digest(commit);
        for (var candidate : latest.path("recovery").path("candidateIds"))
            if (candidate.asText().equals(digest)) return true;
        return false;
    }

    private static void requireRecovery(
            NotificationInvestigationRecord record, ObjectNode latest, FeedbackRequest request) throws IOException {
        if ((record.lifecycle().status() != LifecycleStatus.RECOVERED
                        && record.lifecycle().status() != LifecycleStatus.CONFIRMED_RESOLUTION)
                || !"VERIFIED".equals(latest.path("recovery").path("coverage").asText())
                || !"SUCCESS"
                        .equals(latest.path("recovery")
                                .path("build")
                                .path("result")
                                .asText())
                || !request.recoveryBuild()
                        .equals(latest.path("recovery")
                                .path("build")
                                .path("runId")
                                .asText())) throw new IOException("Verified recovery build required");
    }

    private static LifecycleReducer.Snapshot humanLifecycle(LifecycleReducer.Snapshot old, LifecycleStatus status) {
        return new LifecycleReducer.Snapshot(
                status,
                old.revision(),
                old.occurrenceCount(),
                old.lastFailureOrder(),
                old.currentOrder(),
                old.lastObservationId(),
                old.facts(),
                old.recoveryAssessment());
    }

    private static SuppressionPolicy.State mutePolicy(
            SuppressionPolicy.State old, long until, SuppressionPolicy.Pending pending) {
        return new SuppressionPolicy.State(
                old.initialAccepted(),
                old.recoveryAccepted(),
                old.confirmationAccepted(),
                old.materialLifetime(),
                old.materialAcceptedAt(),
                old.lastMaterialAt(),
                old.lastCorrectionAt(),
                until,
                old.lastAcceptedFingerprint(),
                pending);
    }

    private static void cancelPending(List<OutboxIntent> intents, List<UUID> released, String code) {
        for (int i = 0; i < intents.size(); i++) {
            var intent = intents.get(i);
            if (intent.state() == OutboxIntent.State.QUEUED || intent.state() == OutboxIntent.State.RETRY_WAIT) {
                intents.set(
                        i,
                        new OutboxIntent(
                                intent.deliveryId(),
                                intent.eventId(),
                                intent.destinationId(),
                                intent.destinationGeneration(),
                                intent.rendererVersion(),
                                OutboxIntent.State.CANCELLED,
                                intent.attempts(),
                                intent.createdAt(),
                                intent.nextAttemptAt(),
                                null,
                                0,
                                intent.receipt(),
                                code));
                released.add(intent.deliveryId());
            }
        }
    }

    private static void cancelUnattempted(List<OutboxIntent> intents, List<UUID> released, String code) {
        for (int i = 0; i < intents.size(); i++) {
            var intent = intents.get(i);
            if (intent.state() == OutboxIntent.State.QUEUED && intent.attempts() == 0) {
                var one = new ArrayList<OutboxIntent>(List.of(intent));
                cancelPending(one, released, code);
                intents.set(i, one.get(0));
            }
        }
    }

    private static void cancelAssertion(
            NotificationInvestigationRecord record, List<OutboxIntent> intents, List<UUID> released, UUID assertion) {
        if (assertion == null) return;
        for (int i = 0; i < intents.size(); i++) {
            var intent = intents.get(i);
            var event = record.events().stream()
                    .filter(e -> e.snapshot()
                            .path("eventId")
                            .asText()
                            .equals(intent.eventId().toString()))
                    .findFirst()
                    .orElse(null);
            if (event != null && containsAssertion(event.snapshot(), assertion.toString())) {
                var one = new ArrayList<OutboxIntent>(List.of(intent));
                cancelPending(one, released, "ASSERTION_SUPERSEDED");
                intents.set(i, one.get(0));
            }
        }
    }

    private static boolean containsAssertion(ObjectNode event, String id) {
        return id.equals(event.path("confirmation").path("recordId").asText())
                || id.equals(event.path("humanReview").path("recordId").asText());
    }

    private static boolean supersededAssertion(NotificationInvestigationRecord record, ObjectNode event) {
        return record.feedback().records().stream()
                .anyMatch(r -> (r.request().action() == FeedbackRequest.Action.CORRECT_CONFIRMATION
                                || r.request().action() == FeedbackRequest.Action.REVOKE_CONFIRMATION)
                        && r.request().confirmationId() != null
                        && containsAssertion(event, r.request().confirmationId().toString()));
    }

    private static boolean assertionSent(
            NotificationInvestigationRecord record,
            NotificationInvestigationRecord.DestinationState destination,
            UUID assertion) {
        if (assertion == null) return false;
        for (var event : record.events()) {
            var snapshot = event.snapshot();
            boolean communicated = snapshot.path("humanReview").isObject()
                    ? assertion
                            .toString()
                            .equals(snapshot.path("humanReview")
                                    .path("recordId")
                                    .asText())
                    : "RESOLUTION_CONFIRMED".equals(snapshot.path("eventType").asText())
                            && assertion
                                    .toString()
                                    .equals(snapshot.path("confirmation")
                                            .path("recordId")
                                            .asText());
            if (!communicated) continue;
            String eventId = snapshot.path("eventId").asText();
            if (destination.intents().stream()
                    .anyMatch(i -> i.eventId().toString().equals(eventId) && i.state() == OutboxIntent.State.SENT))
                return true;
        }
        return false;
    }

    private static boolean sentSupersededAssertion(
            NotificationInvestigationRecord record,
            NotificationInvestigationRecord.DestinationState destination,
            ObjectNode event) {
        try {
            return assertionSent(
                    record,
                    destination,
                    UUID.fromString(
                            event.path("humanReview").path("supersedesRecordId").asText()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
    /** Final claim fence. A submission admitted here may already be in flight when a later mute is saved. */
    public boolean submissionAllowed(UUID caseId, UUID destinationId, UUID deliveryId, UUID leaseToken, long now)
            throws IOException {
        synchronized (lock) {
            var record = peek(jobRoot, jobId, caseId).orElse(null);
            if (record == null) return false;
            for (var destination : record.destinations())
                if (destination.destinationId().equals(destinationId)) {
                    for (var intent : destination.intents())
                        if (intent.deliveryId().equals(deliveryId)
                                && intent.state() == OutboxIntent.State.LEASED
                                && java.util.Objects.equals(intent.leaseToken(), leaseToken)
                                && intent.leaseExpiresAt() > now) {
                            var event = record.events().stream()
                                    .filter(e -> e.snapshot()
                                            .path("eventId")
                                            .asText()
                                            .equals(intent.eventId().toString()))
                                    .findFirst()
                                    .orElse(null);
                            if (event == null || supersededAssertion(record, event.snapshot())) return false;
                            boolean critical = "CORRECTION"
                                            .equals(event.snapshot()
                                                    .path("eventType")
                                                    .asText())
                                    && sentSupersededAssertion(record, destination, event.snapshot());
                            if (critical) return true;
                            if (destination.policy().mutedUntil() > now) return false;
                            return record.feedback().records().stream()
                                    .noneMatch(r -> (r.request().action() == FeedbackRequest.Action.MUTE
                                                    || r.request().action() == FeedbackRequest.Action.UNMUTE)
                                            && r.afterRevision()
                                                    > event.snapshot()
                                                            .path("caseRevision")
                                                            .asLong()
                                            && r.request().destinations().contains(destinationId));
                        }
                }
            return false;
        }
    }

    private static NotificationEvent feedbackEvent(
            ObjectNode previous,
            UUID caseId,
            long revision,
            long evidenceRevision,
            long sequence,
            long now,
            String type,
            String reviewAction,
            LifecycleReducer.Snapshot lifecycle,
            ConfirmationRecord confirmation,
            UUID recordId,
            FeedbackActor actor,
            FeedbackRequest request,
            String assertionKind) {
        ObjectNode event = previous.deepCopy();
        event.put(
                        "eventId",
                        UUID.nameUUIDFromBytes((caseId + ":" + sequence).getBytes(StandardCharsets.UTF_8))
                                .toString())
                .put("caseRevision", revision)
                .put("evidenceRevision", evidenceRevision)
                .put("eventType", type)
                .put("createdAt", Instant.ofEpochMilli(now).toString())
                .put("lifecycleState", lifecycle.status().name());
        event.putArray("materialReasons");
        ObjectNode review = event.putObject("humanReview");
        review.put("action", reviewAction)
                .put("recordId", recordId.toString())
                .put("actorLabel", actor.label())
                .put("correctiveAction", request.correctiveAction())
                .put("validationBasis", request.validationBasis());
        if (assertionKind != null) review.put("assertionKind", assertionKind);
        if (!request.candidateId().isBlank()) review.put("candidateId", request.candidateId());
        if (request.confirmationId() == null) review.putNull("supersedesRecordId");
        else review.put("supersedesRecordId", request.confirmationId().toString());
        if (lifecycle.status() != LifecycleStatus.CONFIRMED_RESOLUTION || confirmation != null)
            event.putNull("confirmation");
        if (confirmation != null
                && confirmation.kind() == ConfirmationRecord.Kind.RESOLUTION
                && !confirmation.revoked()) {
            ObjectNode value = event.putObject("confirmation");
            value.put("recordId", confirmation.recordId().toString())
                    .put("revision", confirmation.revision())
                    .put(
                            "confirmedAt",
                            Instant.ofEpochMilli(confirmation.confirmedAt()).toString())
                    .put("actorLabel", confirmation.actorLabel())
                    .put("source", confirmation.source())
                    .put("note", "")
                    .put("correctiveAction", confirmation.correctiveAction())
                    .put("validationBasis", confirmation.validationBasis());
            if (confirmation.causeCandidateId().isBlank()) value.putNull("causeCandidateId");
            else value.put("causeCandidateId", confirmation.causeCandidateId());
            if (confirmation.fixCommit().isBlank()) value.putNull("fixCommit");
            else value.put("fixCommit", confirmation.fixCommit());
            value.set("fixBuild", event.path("recovery").path("build"));
            ((ObjectNode) event.get("recovery")).put("assessment", "CONFIRMED_FIX");
        } else if (lifecycle.status() == LifecycleStatus.RECOVERED) {
            ((ObjectNode) event.get("recovery"))
                    .put("assessment", lifecycle.recoveryAssessment().name());
        }
        return NotificationEvent.freeze(event);
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

    public record DestinationPolicy(UUID id, long generation, boolean recovery, boolean aiUpdates, String transport) {
        public DestinationPolicy(UUID id, long generation, boolean recovery, boolean aiUpdates) {
            this(id, generation, recovery, aiUpdates, "SLACK");
        }

        public DestinationPolicy {
            if (id == null || generation < 1) throw new IllegalArgumentException("Invalid destination policy");
            if (!List.of("SLACK", "EMAIL").contains(transport))
                throw new IllegalArgumentException("Unsupported destination transport");
        }
    }

    public NotificationInvestigationRecord ingestConfigured(
            NotificationObservation input, List<DestinationPolicy> destinations, long now) throws IOException {
        return ingestConfigured(input, destinations, now, value -> false);
    }

    /** A transient disclosure guard never becomes part of the persisted record or event identity. */
    public NotificationInvestigationRecord ingestConfigured(
            NotificationObservation input,
            List<DestinationPolicy> destinations,
            long now,
            java.util.function.Predicate<String> withheld)
            throws IOException {
        java.util.Objects.requireNonNull(withheld);
        checkDisclosure(JSON.valueToTree(input), withheld);
        if (destinations.size() > 20
                || destinations.stream()
                                .filter(d -> "SLACK".equals(d.transport()))
                                .count()
                        > 10
                || destinations.stream()
                                .filter(d -> "EMAIL".equals(d.transport()))
                                .count()
                        > 10
                || destinations.stream().map(DestinationPolicy::id).distinct().count() != destinations.size()
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
                    if (old != null
                            && (old.generation() != approved.generation()
                                    || !old.transport().equals(approved.transport()))) continue;
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
                            old == null ? List.of() : old.submissions(),
                            approved.transport()));
                }
                if (previous != null && !previous.feedback().records().isEmpty()) {
                    if (events.size() > 32) throw new IOException("Feedback evidence retention limit reached");
                } else retainEventSnapshots(events, destinationStates);
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
                    audit,
                    previous == null ? FeedbackState.empty() : previous.feedback());
            checkDisclosure(JSON.valueToTree(record), withheld);
            // Event and metadata envelopes contain serialized JSON; inspect their decoded text values too.
            for (var savedEvent : record.events()) checkDisclosure(savedEvent.snapshot(), withheld);
            for (var savedDestination : record.destinations())
                for (var submission : savedDestination.submissions()) {
                    String metadata = submission.payload().stripLeading();
                    if (metadata.startsWith("{") || metadata.startsWith("[")) {
                        try {
                            checkDisclosure(JSON.readTree(metadata), withheld);
                        } catch (IOException invalid) {
                            throw new IOException("Notification content withheld by disclosure policy");
                        }
                    }
                }
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

    private static void checkDisclosure(
            com.fasterxml.jackson.databind.JsonNode node, java.util.function.Predicate<String> withheld)
            throws IOException {
        try {
            checkDisclosure(node, withheld, 0, new int[] {0});
        } catch (RuntimeException rejected) {
            throw new IOException("Notification content withheld by disclosure policy");
        }
    }

    private static void checkDisclosure(
            com.fasterxml.jackson.databind.JsonNode node,
            java.util.function.Predicate<String> withheld,
            int depth,
            int[] count)
            throws IOException {
        if (node == null || node.isPojo() || node.isBinary() || depth > 32 || ++count[0] > 50000)
            throw new IOException("Notification disclosure input limit");
        if (node.isTextual() && withheld.test(node.asText()))
            throw new IOException("Notification content withheld by disclosure policy");
        for (var child : node) checkDisclosure(child, withheld, depth + 1, count);
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
            if (!updated.destinationId().equals(destinationId)
                    || !updated.transport().equals(states.get(index).transport())
                    || updated.generation() != states.get(index).generation())
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
                    audit,
                    record.feedback());
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
                .put(
                        "caseRevision",
                        transition.snapshot().revision()
                                + (previous == null
                                        ? 0
                                        : previous.feedback().records().size()))
                .put("evidenceRevision", transition.snapshot().revision())
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

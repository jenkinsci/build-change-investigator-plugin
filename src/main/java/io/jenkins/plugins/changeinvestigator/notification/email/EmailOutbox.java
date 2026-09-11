package io.jenkins.plugins.changeinvestigator.notification.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.NotificationInvestigationRecord;
import io.jenkins.plugins.changeinvestigator.notification.NotificationInvestigationRecord.DestinationState;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.SuppressionPolicy;
import io.jenkins.plugins.changeinvestigator.notification.persistence.DeliverySnapshot;
import io.jenkins.plugins.changeinvestigator.notification.persistence.OutboxIntent;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One local transaction, one unlocked network attempt, one local outcome transaction. */
public final class EmailOutbox {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;
    private final EmailSubmissionBudget budget;
    private final Sender sender;

    public EmailOutbox(Clock clock, EmailSubmissionBudget budget, Sender sender) {
        this.clock = clock;
        this.budget = budget;
        this.sender = sender;
    }

    @FunctionalInterface
    public interface Sender {
        EmailTransport.Outcome send(EmailTransport.Settings settings, String recipient, String frozenMimeBase64);
    }

    public record Target(
            long generation,
            String sender,
            String recipient,
            URI jenkinsRoot,
            EmailTransport.Settings settings,
            boolean recovery,
            boolean aiUpdates) {
        public Target {
            if (generation < 1 || sender == null || recipient == null || jenkinsRoot == null || settings == null)
                throw new IllegalArgumentException("Invalid approved email target");
        }

        @Override
        public String toString() {
            return "Approved email delivery target";
        }
    }

    private record Claim(OutboxIntent intent, DeliverySnapshot snapshot) {}

    public String dispatch(
            NotificationEngine engine,
            UUID caseId,
            UUID destination,
            Supplier<Optional<Target>> authorization,
            BooleanSupplier deliveryArmed)
            throws IOException {
        if (engine.records().stream()
                .filter(r -> r.investigationId().equals(caseId))
                .flatMap(r -> r.destinations().stream())
                .noneMatch(d -> d.destinationId().equals(destination) && "EMAIL".equals(d.transport())))
            return "WRONG_TRANSPORT";
        long now = clock.millis();
        // Expired leases become unknown even while the restored controller remains disarmed.
        engine.updateDestination(
                caseId,
                destination,
                state -> {
                    var intents = state.intents().stream()
                            .map(i -> i.recoverExpiredLease(now))
                            .toList();
                    return with(state, state.policy(), intents, state.submissions());
                },
                "DELIVERY_RECONCILED",
                now);
        if (!deliveryArmed.getAsBoolean()) return "CONTROLLER_PAUSED";
        Optional<Target> approved = authorization.get();
        if (approved.isEmpty()) {
            cancel(engine, caseId, destination, "DESTINATION_REVOKED", now);
            return "DESTINATION_REVOKED";
        }
        Target target = approved.get();
        NotificationInvestigationRecord record = engine.records().stream()
                .filter(r -> r.investigationId().equals(caseId))
                .findFirst()
                .orElseThrow();
        DestinationState state = record.destinations().stream()
                .filter(d -> d.destinationId().equals(destination))
                .findFirst()
                .orElseThrow();
        if (state.generation() != target.generation()) {
            cancel(engine, caseId, destination, "DESTINATION_CHANGED", now);
            return "DESTINATION_CHANGED";
        }
        Optional<OutboxIntent> candidate = state.intents().stream()
                .filter(i -> (i.state() == OutboxIntent.State.QUEUED || i.state() == OutboxIntent.State.RETRY_WAIT)
                        && i.nextAttemptAt() <= now)
                .sorted(Comparator.comparingInt((OutboxIntent i) -> priority(i, record))
                        .thenComparingLong(OutboxIntent::createdAt))
                .findFirst();
        if (candidate.isEmpty()) return status(state);
        OutboxIntent intent = candidate.get();
        if (state.intents().stream()
                .anyMatch(i ->
                        i.state() == OutboxIntent.State.UNKNOWN_OUTCOME || "THREAD_UNAVAILABLE".equals(i.safeCode()))) {
            finishWithoutSend(engine, caseId, destination, intent, "THREAD_UNAVAILABLE", now);
            return "THREAD_UNAVAILABLE";
        }
        if (intent.attempts() >= 6 || now - intent.createdAt() >= 86_400_000) {
            finishWithoutSend(engine, caseId, destination, intent, "RETRY_EXHAUSTED", now);
            return "RETRY_EXHAUSTED";
        }
        NotificationEvent event = record.events().stream()
                .filter(e -> e.snapshot()
                        .path("eventId")
                        .asText()
                        .equals(intent.eventId().toString()))
                .findFirst()
                .orElseThrow();
        String type = event.snapshot().path("eventType").asText();
        if ((!target.recovery() && type.equals("RECOVERY_OBSERVED"))
                || (!target.aiUpdates() && type.equals("AI_AVAILABLE"))) {
            finishWithoutSend(engine, caseId, destination, intent, "POLICY_DISABLED", now);
            return "POLICY_DISABLED";
        }
        if (state.intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.LEASED)) return "PARENT_PENDING";
        DeliverySnapshot frozen = state.submissions().stream()
                .filter(s -> s.deliveryId().equals(intent.deliveryId()))
                .findFirst()
                .orElse(null);
        if (frozen == null) {
            var eligibility = SuppressionPolicy.evaluate(state.policy(), now);
            if (!eligibility.eligible()
                    || state.policy().pending() == null
                    || !state.policy()
                            .pending()
                            .eventId()
                            .equals(intent.eventId().toString())) return "POLICY_DEFERRED";
            DeliverySnapshot root = rootSnapshot(state);
            if (root == null
                    && state.intents().stream()
                            .anyMatch(i -> i.attempts() > 0 && i.state() != OutboxIntent.State.CANCELLED)) {
                if (state.intents().stream()
                        .anyMatch(i ->
                                i.state() == OutboxIntent.State.LEASED || i.state() == OutboxIntent.State.RETRY_WAIT))
                    return "PARENT_PENDING";
                finishWithoutSend(engine, caseId, destination, intent, "THREAD_UNAVAILABLE", now);
                return "THREAD_UNAVAILABLE";
            }
            try {
                EmailRenderer.Content content = new EmailRenderer(target.jenkinsRoot())
                        .render(
                                event,
                                root == null
                                        ? EmailRenderer.Presentation.INITIAL_FIELDNOTE
                                        : EmailRenderer.Presentation.EVENT);
                ObjectNode metadata = JSON.createObjectNode();
                String rootId = root == null
                        ? null
                        : JSON.readTree(root.payload()).path("messageId").asText();
                String subject = root == null
                        ? content.subject()
                        : JSON.readTree(root.payload()).path("subject").asText();
                var references = new ArrayList<String>();
                for (var receipt : state.intents()) {
                    if (receipt.state() != OutboxIntent.State.SENT) continue;
                    for (var snapshot : state.submissions())
                        if (snapshot.deliveryId().equals(receipt.deliveryId()))
                            references.add(JSON.readTree(snapshot.payload())
                                    .path("messageId")
                                    .asText());
                }
                String password = target.settings().password();
                if (EmailMessage.containsSecret(content.html(), password)
                        || EmailMessage.containsSecret(content.plainText(), password)
                        || EmailMessage.containsSecret(subject, password))
                    throw new IllegalArgumentException("Credential in content");
                var prepared = EmailMessage.prepare(
                        intent.deliveryId(),
                        target.sender(),
                        target.recipient(),
                        root == null ? subject : "Re: " + subject,
                        content.plainText(),
                        content.html(),
                        rootId,
                        references,
                        intent.createdAt());
                metadata.put("messageId", prepared.messageId()).put("subject", subject);
                metadata.put("rootId", rootId == null ? prepared.messageId() : rootId);
                var chunks = new ArrayList<String>();
                String mime = prepared.mimeBase64();
                for (int offset = 0; offset < mime.length(); offset += 32768)
                    chunks.add(mime.substring(offset, Math.min(offset + 32768, mime.length())));
                frozen = new DeliverySnapshot(
                        intent.deliveryId(),
                        JSON.writeValueAsString(metadata),
                        (root == null ? "ROOT:" : "REPLY:") + routing(target),
                        now,
                        chunks);
            } catch (IllegalArgumentException invalid) {
                finishWithoutSend(engine, caseId, destination, intent, "RENDER_REJECTED", now);
                return "RENDER_REJECTED";
            }
        }
        if (!frozen.routing().endsWith(":" + routing(target))) {
            cancel(engine, caseId, destination, "DESTINATION_CHANGED", now);
            return "DESTINATION_CHANGED";
        }
        var reservation = budget.reserve(destination, intent.deliveryId(), now);
        if (!reservation.allowed()) {
            defer(engine, caseId, destination, intent, reservation.retryAt(), now);
            return "RATE_DEFERRED";
        }
        DeliverySnapshot bytes = frozen;
        var claimed = new java.util.concurrent.atomic.AtomicReference<Claim>();
        engine.updateDestination(
                caseId,
                destination,
                current -> {
                    if (current.intents().stream()
                            .anyMatch(i -> i.state() == OutboxIntent.State.LEASED
                                    || i.state() == OutboxIntent.State.UNKNOWN_OUTCOME
                                    || "THREAD_UNAVAILABLE".equals(i.safeCode()))) return current;
                    var intents = new ArrayList<>(current.intents());
                    for (int n = 0; n < intents.size(); n++) {
                        var selected = intents.get(n);
                        if (!selected.deliveryId().equals(intent.deliveryId()) || !selected.equals(intent)) continue;
                        var policy = current.policy();
                        boolean first = selected.attempts() == 0;
                        if (first) {
                            var due = SuppressionPolicy.evaluate(policy, now);
                            if (!due.eligible()
                                    || policy.pending() == null
                                    || !policy.pending()
                                            .eventId()
                                            .equals(selected.eventId().toString())) return current;
                            // Reservations/unknown acceptance count toward quota; safe retries never reserve twice.
                            policy = SuppressionPolicy.accepted(
                                    policy, selected.eventId().toString(), now);
                        }
                        var lease = selected.lease(now, 120_000);
                        intents.set(n, lease);
                        var submissions = new ArrayList<>(current.submissions());
                        if (submissions.stream().noneMatch(s -> s.deliveryId().equals(bytes.deliveryId())))
                            submissions.add(bytes);
                        claimed.set(new Claim(lease, bytes));
                        return with(current, policy, intents, submissions);
                    }
                    return current;
                },
                "DELIVERY_LEASED",
                now);
        if (claimed.get() == null) return "CLAIM_LOST";
        Claim claim = claimed.get();
        EmailTransport.Outcome outcome;
        try {
            Optional<Target> fresh = authorization.get();
            if (!deliveryArmed.getAsBoolean()
                    || fresh.isEmpty()
                    || fresh.get().generation() != target.generation()
                    || !routing(fresh.get()).equals(routing(target))
                    || (type.equals("RECOVERY_OBSERVED") && !fresh.get().recovery())
                    || (type.equals("AI_AVAILABLE") && !fresh.get().aiUpdates()))
                return settleCancelled(engine, caseId, destination, claim, clock.millis());
            String mime = String.join("", claim.snapshot().chunks());
            String password = fresh.get().settings().password();
            String raw =
                    new String(java.util.Base64.getDecoder().decode(mime), java.nio.charset.StandardCharsets.UTF_8);
            if (password != null && !password.isEmpty() && raw.contains(password))
                return settleCancelled(engine, caseId, destination, claim, clock.millis());
            outcome = sender.send(fresh.get().settings(), target.recipient(), mime);
        } catch (RuntimeException | LinkageError e) {
            outcome = new EmailTransport.Outcome(EmailTransport.Status.UNKNOWN_OUTCOME, "ACCEPTANCE_UNKNOWN");
        }
        if (outcome == null)
            outcome = new EmailTransport.Outcome(EmailTransport.Status.UNKNOWN_OUTCOME, "ACCEPTANCE_UNKNOWN");
        EmailTransport.Outcome result = outcome;
        long completed = clock.millis();
        engine.updateDestination(
                caseId,
                destination,
                current -> {
                    var intents = new ArrayList<>(current.intents());
                    var submissions = new ArrayList<>(current.submissions());
                    for (int n = 0; n < intents.size(); n++) {
                        OutboxIntent live = intents.get(n);
                        if (!live.deliveryId().equals(claim.intent().deliveryId())
                                || live.state() != OutboxIntent.State.LEASED
                                || !live.leaseToken().equals(claim.intent().leaseToken())) continue;
                        OutboxIntent updated;
                        switch (result.status()) {
                            case SENT ->
                                updated = live.accepted(
                                        live.leaseToken(),
                                        (claim.snapshot().routing().startsWith("ROOT:") ? "ROOT:" : "REPLY:")
                                                + live.deliveryId());
                            case RETRYABLE ->
                                updated =
                                        live.rejected(live.leaseToken(), true, retryAt(live.attempts(), completed, 0));
                            case PERMANENT_FAILURE ->
                                updated = safe(live.rejected(live.leaseToken(), false, completed), "EMAIL_REJECTED");
                            default -> updated = live.unknown(live.leaseToken());
                        }
                        intents.set(n, updated);
                        if (updated.state() == OutboxIntent.State.SENT)
                            for (int s = 0; s < submissions.size(); s++) {
                                var saved = submissions.get(s);
                                if (saved.deliveryId().equals(updated.deliveryId()))
                                    submissions.set(
                                            s,
                                            new DeliverySnapshot(
                                                    saved.deliveryId(),
                                                    saved.payload(),
                                                    saved.routing(),
                                                    saved.reservedAt()));
                            }
                    }
                    return with(current, current.policy(), intents, submissions);
                },
                result.status() == EmailTransport.Status.SENT ? "DELIVERY_SENT" : "DELIVERY_OUTCOME_RECORDED",
                completed);
        return result.status().name();
    }

    private static int priority(OutboxIntent intent, NotificationInvestigationRecord record) {
        if (intent.attempts() > 0) return 0;
        return record.events().stream()
                        .anyMatch(e -> e.snapshot()
                                        .path("eventId")
                                        .asText()
                                        .equals(intent.eventId().toString())
                                && List.of("RECOVERY_OBSERVED", "RESOLUTION_CONFIRMED", "CORRECTION")
                                        .contains(e.snapshot().path("eventType").asText()))
                ? 1
                : 2;
    }

    private static DeliverySnapshot rootSnapshot(DestinationState state) {
        for (var intent : state.intents()) {
            if (intent.state() != OutboxIntent.State.SENT || !intent.receipt().startsWith("ROOT:")) continue;
            for (var snapshot : state.submissions())
                if (snapshot.deliveryId().equals(intent.deliveryId())) return snapshot;
        }
        return null;
    }

    private static String routing(Target target) {
        return io.jenkins.plugins.changeinvestigator.notification.identity.IdentityCanonicalizer.digest(
                target.generation() + ":" + target.sender() + ":" + target.recipient() + ":"
                        + target.settings().host() + ":" + target.settings().port() + ":"
                        + target.settings().tls() + ":" + target.settings().allowInternal());
    }

    public static String status(DestinationState state) {
        if (state.intents().stream().anyMatch(i -> i.state() == OutboxIntent.State.UNKNOWN_OUTCOME))
            return "UNKNOWN_OUTCOME";
        if (state.intents().stream().anyMatch(i -> "THREAD_UNAVAILABLE".equals(i.safeCode())))
            return "THREAD_UNAVAILABLE";
        return state.intents().isEmpty()
                ? "NO_PENDING_DELIVERY"
                : state.intents().get(state.intents().size() - 1).state().name();
    }

    private static DestinationState with(
            DestinationState state,
            SuppressionPolicy.State policy,
            List<OutboxIntent> intents,
            List<DeliverySnapshot> submissions) {
        return new DestinationState(
                state.destinationId(), state.generation(), policy, intents, submissions, state.transport());
    }

    private static OutboxIntent safe(OutboxIntent intent, String code) {
        return new OutboxIntent(
                intent.deliveryId(),
                intent.eventId(),
                intent.destinationId(),
                intent.destinationGeneration(),
                intent.rendererVersion(),
                intent.state(),
                intent.attempts(),
                intent.createdAt(),
                intent.nextAttemptAt(),
                intent.leaseToken(),
                intent.leaseExpiresAt(),
                intent.receipt(),
                code);
    }

    private static long retryAt(int attempts, long now, long retryAfter) {
        long[] delays = {30_000, 120_000, 480_000, 1_800_000, 7_200_000};
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(delays[Math.min(attempts - 1, 4)] + 1);
        return now + Math.max(1000, Math.max(jitter, Math.min(retryAfter, 86_400_000)));
    }

    private static void cancel(NotificationEngine engine, UUID caseId, UUID destination, String code, long now)
            throws IOException {
        engine.updateDestination(
                caseId,
                destination,
                state -> with(
                        state,
                        state.policy(),
                        state.intents().stream()
                                .map(i -> safe(i.cancelForDestinationGeneration(0), code))
                                .toList(),
                        state.submissions()),
                code,
                now);
    }

    private static String settleCancelled(
            NotificationEngine engine, UUID caseId, UUID destination, Claim claim, long now) throws IOException {
        engine.updateDestination(
                caseId,
                destination,
                state -> with(
                        state,
                        state.policy(),
                        state.intents().stream()
                                .map(i -> i.deliveryId().equals(claim.intent().deliveryId())
                                                && i.state() == OutboxIntent.State.LEASED
                                                && i.leaseToken()
                                                        .equals(claim.intent().leaseToken())
                                        ? safe(i.rejected(i.leaseToken(), false, now), "REVOKED_BEFORE_SUBMISSION")
                                        : i)
                                .toList(),
                        state.submissions()),
                "REVOKED_BEFORE_SUBMISSION",
                now);
        return "REVOKED_BEFORE_SUBMISSION";
    }

    private static void finishWithoutSend(
            NotificationEngine engine, UUID caseId, UUID destination, OutboxIntent intent, String code, long now)
            throws IOException {
        engine.updateDestination(
                caseId,
                destination,
                state -> with(
                        state,
                        state.policy(),
                        state.intents().stream()
                                .map(i -> i.equals(intent)
                                        ? new OutboxIntent(
                                                i.deliveryId(),
                                                i.eventId(),
                                                i.destinationId(),
                                                i.destinationGeneration(),
                                                i.rendererVersion(),
                                                OutboxIntent.State.FAILED_PERMANENT,
                                                i.attempts(),
                                                i.createdAt(),
                                                i.nextAttemptAt(),
                                                null,
                                                0,
                                                null,
                                                code)
                                        : i)
                                .toList(),
                        state.submissions()),
                code,
                now);
    }

    private static void defer(
            NotificationEngine engine, UUID caseId, UUID destination, OutboxIntent intent, long retryAt, long now)
            throws IOException {
        engine.updateDestination(
                caseId,
                destination,
                state -> with(
                        state,
                        state.policy(),
                        state.intents().stream()
                                .map(i -> i.equals(intent)
                                        ? new OutboxIntent(
                                                i.deliveryId(),
                                                i.eventId(),
                                                i.destinationId(),
                                                i.destinationGeneration(),
                                                i.rendererVersion(),
                                                i.state(),
                                                i.attempts(),
                                                i.createdAt(),
                                                retryAt,
                                                null,
                                                0,
                                                null,
                                                "RATE_DEFERRED")
                                        : i)
                                .toList(),
                        state.submissions()),
                "RATE_DEFERRED",
                now);
    }
}

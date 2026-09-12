package io.jenkins.plugins.changeinvestigator.notification.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.notification.NotificationInvestigationRecord;
import io.jenkins.plugins.changeinvestigator.security.NotificationPermissions;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jenkins.model.Jenkins;

/** Read-only presentation of a persisted investigation and its distinct human review. */
public final class FeedbackView {
    private final Job<?, ?> job;
    private final NotificationInvestigationRecord record;
    private final JsonNode snapshot;

    private FeedbackView(Job<?, ?> job, NotificationInvestigationRecord record) {
        this.job = job;
        this.record = record;
        this.snapshot = record.events().isEmpty()
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : record.events().get(record.events().size() - 1).snapshot();
    }

    public static FeedbackView forCase(Job<?, ?> job, UUID id) throws IOException {
        return FeedbackAccess.read(job, id).map(r -> new FeedbackView(job, r)).orElse(null);
    }

    public static FeedbackView forRun(Run<?, ?> run) throws IOException {
        return FeedbackAccess.forRun(run)
                .map(r -> new FeedbackView(run.getParent(), r))
                .orElse(null);
    }

    public String getCaseId() {
        return record.investigationId().toString();
    }

    public long getRevision() {
        return record.caseRevision();
    }

    public long getEvidenceRevision() {
        return record.evidenceRevision();
    }

    public String getUrl() {
        return job.getUrl() + "change-investigation-feedback/?caseId=" + getCaseId();
    }

    public String getStatus() {
        return record.lifecycle().status().name().replace('_', ' ');
    }

    public String getRecoveryBuild() {
        return snapshot.path("recovery").path("build").path("runId").asText();
    }

    public String getRecoveryLabel() {
        return snapshot.path("recovery").path("build").path("number").asText("Unavailable");
    }

    public String getFailureLabel() {
        return Long.toString(record.lifecycle().lastFailureOrder());
    }

    public String getRecoveryEvidence() {
        return record.lifecycle().recoveryAssessment().name().replace('_', ' ')
                + (snapshot.path("recovery").path("basis").asText().isBlank()
                        ? ""
                        : ": " + snapshot.path("recovery").path("basis").asText());
    }

    public String getFailureSignal() {
        return snapshot.path("failureSummary").path("diagnostic").asText();
    }

    public String getFailureLocation() {
        return snapshot.path("failureSummary").path("location").asText();
    }

    public String getConfirmationId() {
        return record.feedback()
                .currentConfirmation()
                .map(c -> c.recordId().toString())
                .orElse("");
    }

    public String getResolutionId() {
        return record.feedback()
                .currentResolution()
                .map(c -> c.recordId().toString())
                .orElse("");
    }

    public List<Choice> getConfirmations() {
        var choices = new ArrayList<Choice>();
        record.feedback()
                .currentCause()
                .ifPresent(c -> choices.add(new Choice(c.recordId().toString(), "Cause · " + c.actorLabel())));
        record.feedback()
                .currentResolution()
                .ifPresent(c -> choices.add(new Choice(c.recordId().toString(), "Resolution · " + c.actorLabel())));
        return List.copyOf(choices);
    }

    public String getSummary() {
        var parts = new ArrayList<String>();
        record.feedback().currentCause().ifPresent(c -> parts.add("Cause confirmed by " + c.actorLabel()));
        record.feedback().currentResolution().ifPresent(c -> parts.add("Resolution confirmed by " + c.actorLabel()));
        if (!parts.isEmpty()) return String.join(" · ", parts);
        var records = record.feedback().records();
        if (records.isEmpty()) return "Not yet reviewed";
        var last = records.get(records.size() - 1);
        return label(last.request().action()) + " · " + last.actor().label();
    }

    public String getConfirmationDetail() {
        var parts = new ArrayList<String>();
        record.feedback()
                .currentCause()
                .ifPresent(c -> parts.add("Cause: " + c.correctiveAction() + " — " + c.validationBasis()));
        record.feedback()
                .currentResolution()
                .ifPresent(c -> parts.add("Resolution: " + c.correctiveAction() + " — " + c.validationBasis()));
        return String.join(" | ", parts);
    }

    public String getDeliveryWarning() {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) return "";
        long revision = record.audit().stream()
                .filter(a -> a.code().equals("HUMAN_CORRECTION_DELIVERY_UNAVAILABLE"))
                .mapToLong(NotificationInvestigationRecord.Audit::revision)
                .max()
                .orElse(0);
        return revision == 0
                ? ""
                : "A correction at review revision " + revision
                        + " could not be queued for all destinations. Review notification delivery before relying on external messages; it will not be replayed automatically.";
    }

    public boolean isMuted() {
        return record.destinations().stream().anyMatch(d -> d.policy().mutedUntil() > System.currentTimeMillis());
    }

    private boolean allowed(hudson.security.Permission permission) {
        var actor = Jenkins.getAuthentication2();
        return actor.isAuthenticated()
                && !ACL.isAnonymous2(actor)
                && !ACL.SYSTEM2.equals(actor)
                && job.hasPermission(Item.READ)
                && job.hasPermission(permission);
    }

    public boolean isTriage() {
        return allowed(NotificationPermissions.TRIAGE);
    }

    public List<Choice> getActions() {
        var actions = new ArrayList<Choice>();
        if (isTriage()) {
            actions.add(new Choice("ACKNOWLEDGE", "Acknowledge"));
            actions.add(new Choice("NOT_RELATED", "Mark candidate not related"));
        }
        if (allowed(NotificationPermissions.CONFIRM)) {
            if (record.feedback().currentCause().isEmpty()) actions.add(new Choice("CONFIRM_CAUSE", "Confirm cause"));
            if (record.lifecycle().status().name().equals("RECOVERED"))
                actions.add(new Choice("CONFIRM_RESOLUTION", "Confirm resolution"));
            if (!getConfirmationId().isEmpty()) {
                actions.add(new Choice("REVOKE_CONFIRMATION", "Revoke confirmation"));
                actions.add(new Choice("CORRECT_CONFIRMATION", "Correct confirmation"));
            }
            if (record.lifecycle().status().name().equals("CLOSED"))
                actions.add(new Choice("REOPEN", "Request guarded reopen"));
        }
        if (allowed(NotificationPermissions.MUTE)) {
            actions.add(new Choice("MUTE", "Mute notifications"));
            actions.add(new Choice("UNMUTE", "Unmute notifications"));
        }
        return List.copyOf(actions);
    }

    public List<Choice> getCandidates() {
        var choices = new ArrayList<Choice>();
        for (var candidate : snapshot.path("topCandidates"))
            choices.add(new Choice(
                    candidate.path("candidateId").asText(),
                    candidate.path("path").asText() + " · "
                            + candidate.path("commit").asText()));
        return List.copyOf(choices);
    }

    public String candidateId(String path, String commit) {
        for (var c : snapshot.path("topCandidates"))
            if (c.path("path").asText().equals(path)
                    && c.path("commit").asText().equals(commit))
                return c.path("candidateId").asText();
        return "";
    }

    public String candidateReview(String path, String commit) {
        for (var c : snapshot.path("topCandidates"))
            if (c.path("path").asText().equals(path)
                    && c.path("commit").asText().equals(commit)) {
                String id = c.path("candidateId").asText();
                var rows = record.feedback().records();
                for (int i = rows.size() - 1; i >= 0; i--) {
                    var row = rows.get(i);
                    if (row.request().action() == FeedbackRequest.Action.NOT_RELATED
                            && row.request().candidateId().equals(id)
                            && row.request().evidenceRevision() == getEvidenceRevision())
                        return "Not related · reviewed by " + row.actor().label();
                }
            }
        return "";
    }

    public List<HistoryRow> getHistory() {
        var rows = new ArrayList<HistoryRow>();
        for (var r : record.feedback().records())
            rows.add(new HistoryRow(
                    label(r.request().action()),
                    r.actor().label(),
                    Instant.ofEpochMilli(r.at()).toString(),
                    r.beforeRevision() + " → " + r.afterRevision(),
                    r.request().note(),
                    r.request().validationBasis()));
        java.util.Collections.reverse(rows);
        return List.copyOf(rows);
    }

    private static String label(FeedbackRequest.Action action) {
        return switch (action) {
            case ACKNOWLEDGE -> "Acknowledged";
            case NOT_RELATED -> "Candidate marked not related";
            case CONFIRM_CAUSE -> "Cause confirmed";
            case CONFIRM_RESOLUTION -> "Resolution confirmed";
            case REVOKE_CONFIRMATION -> "Confirmation revoked";
            case CORRECT_CONFIRMATION -> "Confirmation corrected";
            case REOPEN -> "Case reopened";
            case MUTE -> "Notifications muted";
            case UNMUTE -> "Notifications unmuted";
        };
    }

    public static final class Choice {
        private final String value;
        private final String label;

        Choice(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String getValue() {
            return value;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class HistoryRow {
        private final String action, actor, at, revision, note, basis;

        HistoryRow(String action, String actor, String at, String revision, String note, String basis) {
            this.action = action;
            this.actor = actor;
            this.at = at;
            this.revision = revision;
            this.note = note;
            this.basis = basis;
        }

        public String getAction() {
            return action;
        }

        public String getActor() {
            return actor;
        }

        public String getAt() {
            return at;
        }

        public String getRevision() {
            return revision;
        }

        public String getNote() {
            return note;
        }

        public String getBasis() {
            return basis;
        }
    }
}

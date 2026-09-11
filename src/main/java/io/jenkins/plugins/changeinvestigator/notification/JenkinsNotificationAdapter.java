package io.jenkins.plugins.changeinvestigator.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ExtensionList;
import hudson.model.Run;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import io.jenkins.plugins.changeinvestigator.investigation.RankedChange;
import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.IdentityCanonicalizer;
import io.jenkins.plugins.changeinvestigator.notification.identity.NotificationSignatureAdapter;
import io.jenkins.plugins.changeinvestigator.notification.identity.TrustedIdentityEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.CoverageEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;

/** Projection of already captured evidence. Never calls AI, a workspace, SCM network or page getters. */
final class JenkinsNotificationAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JenkinsNotificationAdapter() {}

    static NotificationObservation observe(Run<?, ?> run, String jobId, String runId, boolean complete) {
        InvestigationAction action = run.getAction(InvestigationAction.class);
        var evidence = action == null ? null : action.getEvidence();
        long assessmentRevision = action == null || action.getAiAssessment() == null
                ? 0
                : action.getAiAssessment().getGeneratedAtMillis();
        String observationId =
                runId + ":e" + (evidence == null ? 0 : evidence.getCollectedAtMillis()) + ":a" + assessmentRevision;
        FailureSignal signal = FailureSignal.extract(evidence == null ? List.of() : evidence.getLogExcerpt());
        TrustedIdentityEvidence.Snapshot trusted = trusted(run, jobId, observationId);
        ExecutionContextV1 context = trusted == null ? ExecutionContextV1.unknown(jobId) : trusted.context();
        FailureSignatureV1 signature = trusted == null
                ? FailureSignatureV1.unresolved(
                        FailureSignatureV1.Category.GENERIC,
                        FailureSignatureV1.ContextFields.empty(),
                        observationId,
                        "MISSING_CONTEXT")
                : NotificationSignatureAdapter.adapt(trusted, observationId);
        ObjectNode display = JSON.createObjectNode();
        display.put("jobLabel", run.getParent().getFullName());
        ObjectNode build = display.putObject("build");
        var result = run.getResult();
        build.put("runId", runId)
                .put("number", run.getNumber())
                .put("result", result == null ? "UNKNOWN" : result.toString());
        build.put("url", SafeContent.navigation(Jenkins.get().getRootUrl(), run.getUrl() + "change-investigation/"));
        ObjectNode boundary = boundary(run, jobId, runId, complete, context, signature);
        display.set("boundary", boundary);
        display.putObject("failureSummary")
                .put("category", signal.getCategory())
                .put("diagnostic", signal.getText())
                .put("location", signal.getLocation())
                .put("limitation", "Notification identity and boundary require trusted, complete provenance.");
        var top = display.putArray("topCandidates");
        List<MaterialFacts.Candidate> material = new ArrayList<>();
        List<String> relevantCommits = new ArrayList<>();
        boolean withheldCandidate = false;
        boolean boundedChanges = evidence != null && evidence.getChangeEntries().size() <= 50;
        long fileCount = 0;
        long pathBytes = 0;
        if (boundedChanges)
            for (var entry : evidence.getChangeEntries()) {
                if (entry.getAffectedFiles().size() > 10000) {
                    boundedChanges = false;
                    break;
                }
                for (String path : entry.getAffectedFiles()) {
                    fileCount++;
                    pathBytes += path == null ? 0 : path.length() * 3L;
                    if (fileCount > 10000 || pathBytes > 2 * 1024 * 1024) {
                        boundedChanges = false;
                        break;
                    }
                }
                if (!boundedChanges) break;
            }
        if (boundedChanges) {
            List<io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry> safeEntries = new ArrayList<>();
            for (var entry : evidence.getChangeEntries()) {
                if (!safeCritical(entry.getCommitId(), 100)) {
                    withheldCandidate = true;
                    continue;
                }
                List<String> paths = new ArrayList<>();
                for (String raw : entry.getAffectedFiles()) {
                    String path = raw == null ? null : raw.replace((char) 92, '/');
                    if (!safeCritical(path, 500)) withheldCandidate = true;
                    else paths.add(path);
                }
                safeEntries.add(new io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry(
                        entry.getCommitId(),
                        entry.getAuthor(),
                        entry.getMessage(),
                        paths,
                        entry.getTimestampMillis(),
                        entry.getFromBuildNumber()));
            }
            var ranked = RankedChange.rank(safeEntries, signal);
            for (var candidate : ranked.stream().limit(5).toList()) {
                String path = candidate.getPath();
                String commit = candidate.getCommit();
                if (!safeCritical(path, 512) || !safeCritical(commit, 128)) {
                    withheldCandidate = true;
                    continue;
                }
                String strength =
                        candidate.getStrength().toUpperCase(Locale.ROOT).replace(" EVIDENCE", "");
                if (!List.of("STRONG", "MODERATE", "WEAK").contains(strength)) strength = "WEAK";
                top.addObject()
                        .put("candidateId", IdentityCanonicalizer.digest(path.length() + ":" + path + ":" + commit))
                        .put("path", path)
                        .put("commit", commit)
                        .put("authorLabel", candidate.getAuthor())
                        .put("strength", strength)
                        .put("relationship", candidate.getReason())
                        .put("limitation", candidate.getLimitation())
                        .put("nextCheck", candidate.getNextCheck())
                        .putArray("evidenceRefs")
                        .add(runId);
                if (!"WEAK".equals(strength)) {
                    material.add(new MaterialFacts.Candidate("", path, commit, strength, List.of("OBSERVED_RANKING")));
                    relevantCommits.add(commit);
                }
            }
        }
        if (boundedChanges && evidence.isChangeDataAvailable()) display.put("allChangeCount", fileCount);
        else display.putNull("allChangeCount");
        display.put("changesComplete", boundedChanges && evidence.isChangeDataAvailable() && !withheldCandidate);
        if (withheldCandidate) {
            ((ObjectNode) display.get("failureSummary"))
                    .put(
                            "limitation",
                            "Some changed paths were withheld because their identities could not be preserved safely. "
                                    + "No causal relationship has been established.");
        }
        display.putArray("suggestedResponders");
        display.putArray("suggestedChecks").add("Inspect the matching failure evidence and related diff in Jenkins.");
        var ai = display.putObject("ai");
        var configuration = io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration.get();
        String state = !configuration.isAiEnabled()
                ? "AI_DISABLED"
                : configuration.getProviderConfig() == null ? "AI_NOT_CONFIGURED" : "AI_PENDING";
        if (action != null && action.getAiAssessment() != null) {
            var assessment = action.getAiAssessment();
            state = assessment.isDisabled() ? "AI_DISABLED" : assessment.isFailed() ? "AI_FAILED" : "AI_COMPLETE";
            // Completion is projected without interpreting its scope as current deterministic evidence.
        }
        ai.put("state", state).putNull("summary").putNull("suggestedCheck").putNull("evidenceRevision");
        MaterialFacts facts = new MaterialFacts(
                boundary.path("firstBadVerified").asBoolean()
                        ? boundary.path("firstBad").path("runId").asText()
                        : "",
                material,
                relevantCommits,
                "",
                "",
                "");
        var revisionFields = new java.util.TreeMap<String, String>();
        revisionFields.put("material", facts.fingerprint());
        revisionFields.put(
                "evidenceCollectedAt", Long.toString(evidence == null ? 0 : evidence.getCollectedAtMillis()));
        revisionFields.put("assessmentGeneratedAt", Long.toString(assessmentRevision));
        revisionFields.put("aiState", state);
        revisionFields.put("completeHistory", Boolean.toString(complete));
        revisionFields.put("contextDigest", context.digest());
        revisionFields.put("signatureDigest", signature.digest());
        observationId = runId + ":" + IdentityCanonicalizer.digest(IdentityCanonicalizer.canonical(revisionFields));
        signature = new FailureSignatureV1(
                signature.failureSignatureVersion(),
                signature.extractorVersion(),
                signature.normalizationProfile(),
                observationId,
                signature.quality(),
                signature.category(),
                signature.identityFields(),
                signature.contextFields(),
                signature.reasonCodes(),
                signature.digest());
        return new NotificationObservation(
                observationId,
                runId,
                run.getNumber(),
                build.path("result").asText(),
                evidence != null && evidence.isLogAvailable(),
                complete,
                context,
                signature,
                signature.digest() == null ? "" : signature.digest(),
                facts,
                CoverageEvidence.unknown(),
                List.of(),
                display);
    }

    private static boolean safeCritical(String value, int limit) {
        return value != null
                && !value.isBlank()
                && value.length() <= limit
                && value.equals(SafeContent.text(value, limit));
    }

    private static TrustedIdentityEvidence.Snapshot trusted(Run<?, ?> run, String jobId, String observationId) {
        TrustedIdentityEvidence.Snapshot selected = null;
        var providers = ExtensionList.lookup(TrustedIdentityEvidence.class);
        if (providers.size() > 50) return null;
        for (TrustedIdentityEvidence producer : providers) {
            var result = producer.observe(run, jobId, observationId);
            if (result == null) continue;
            if (selected != null) return null;
            selected = result;
        }
        return selected;
    }

    private static ObjectNode boundary(
            Run<?, ?> current,
            String jobId,
            String runId,
            boolean complete,
            ExecutionContextV1 context,
            FailureSignatureV1 signature) {
        ObjectNode unknown = JSON.createObjectNode()
                .putNull("lastKnownGood")
                .putNull("firstBad")
                .put("firstBadVerified", false)
                .put("proofStatus", complete ? "UNKNOWN" : "HISTORY_GAP");
        if (!complete
                || current.getResult() != hudson.model.Result.FAILURE
                || signature.quality() != FailureSignatureV1.Quality.SPECIFIC
                || !context.trusted()
                || !context.ancestryKnown()
                || !context.jobId().equals(jobId)) return unknown;
        var currentIdentity = current.getAction(
                io.jenkins.plugins.changeinvestigator.notification.identity.NotificationRunIdentity.class);
        if (currentIdentity == null || !currentIdentity.getId().equals(runId)) return unknown;
        Run<?, ?> firstBad = current;
        String firstBadId = runId;
        Run<?, ?> cursor = current.getPreviousBuild();
        int expected = current.getNumber() - 1;
        for (int inspected = 0; inspected < 100 && cursor != null; inspected++) {
            if (cursor.getNumber() != expected || cursor.isBuilding()) return unknown;
            var identity = cursor.getAction(
                    io.jenkins.plugins.changeinvestigator.notification.identity.NotificationRunIdentity.class);
            if (identity == null) return unknown;
            var prior = trusted(cursor, jobId, identity.getId());
            if (prior == null || !prior.inventoryComplete() || !sameContext(context, prior.context())) return unknown;
            if (cursor.getResult() == hudson.model.Result.SUCCESS) {
                ObjectNode proven = JSON.createObjectNode();
                proven.set("lastKnownGood", boundaryBuild(cursor, identity.getId()));
                proven.set("firstBad", boundaryBuild(firstBad, firstBadId));
                proven.put("firstBadVerified", true).put("proofStatus", "VERIFIED");
                return proven;
            }
            if (cursor.getResult() != hudson.model.Result.FAILURE
                    || cursor.getAction(InvestigationAction.class) == null) return unknown;
            FailureSignatureV1 priorSignature = NotificationSignatureAdapter.adapt(prior, identity.getId());
            if (!signature.sameIdentity(priorSignature)) return unknown;
            firstBad = cursor;
            firstBadId = identity.getId();
            expected--;
            cursor = cursor.getPreviousBuild();
        }
        return unknown;
    }

    private static boolean sameContext(ExecutionContextV1 current, ExecutionContextV1 prior) {
        return prior != null
                && prior.trusted()
                && prior.ancestryKnown()
                && current.contextVersion() == prior.contextVersion()
                && current.jobId().equals(prior.jobId())
                && current.origin() == prior.origin()
                && current.digest().equals(prior.digest())
                && java.util.Set.copyOf(current.sources()).equals(java.util.Set.copyOf(prior.sources()));
    }

    private static ObjectNode boundaryBuild(Run<?, ?> run, String id) {
        java.util.UUID.fromString(id);
        var result = run.getResult();
        if (result == null) throw new IllegalArgumentException("Completed boundary build required");
        return JSON.createObjectNode()
                .put("runId", id)
                .put("number", run.getNumber())
                .put("result", result.toString())
                .put("url", SafeContent.navigation(Jenkins.get().getRootUrl(), run.getUrl()));
    }
}

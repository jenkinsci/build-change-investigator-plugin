package io.jenkins.plugins.changeinvestigator.slack.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Run;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.evidence.EvidenceCollector;
import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import io.jenkins.plugins.changeinvestigator.investigation.RankedChange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import jenkins.model.JenkinsLocationConfiguration;

/** Bounded notification projection of existing BCI evidence, independent of AI interpretation. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(
        fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY,
        getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
        setterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class SlackSnapshot {
    int build, lastGood, firstBad;
    String job = "",
            signature = "",
            material = "",
            category = "",
            failure = "",
            stage = "",
            module = "",
            test = "",
            source = "",
            commit = "",
            author = "",
            reason = "",
            limitation = "",
            check = "",
            strength = "",
            changes = "",
            ai = "",
            aiScope = "",
            investigationUrl = "",
            buildUrl = "",
            changesUrl = "";
    boolean aiPending;

    public int getLastGood() {
        return lastGood;
    }

    public int getFirstBad() {
        return firstBad;
    }

    public String getSignature() {
        return signature;
    }

    public String getMaterial() {
        return material;
    }

    public String getAuthor() {
        return author;
    }

    public String getAi() {
        return ai;
    }

    public String getAiScope() {
        return aiScope;
    }

    public String getSource() {
        return source;
    }

    public boolean isAiPending() {
        return aiPending;
    }

    public void setFirstBad(int value) {
        firstBad = value;
    }

    public void setMaterial(String value) {
        material = value;
    }

    public void includeAi(SlackSnapshot current) {
        if (current != null && aiScope != null && aiScope.equals(current.aiScope)) ai = current.ai;
    }

    public static SlackSnapshot capture(Run<?, ?> run, int episodeBaseline) throws IOException {
        InvestigationAction action = run.getAction(InvestigationAction.class);
        if (action == null) return null;
        var evidence = action.getEvidence();
        if (episodeBaseline > 0 && episodeBaseline != evidence.getPreviousSuccessfulBuildNumber()) {
            Run<?, ?> baseline = run.getParent().getBuildByNumber(episodeBaseline);
            if (baseline == null) return null;
            evidence = new EvidenceCollector(12000).collect(run, baseline);
        }
        var view = action.getView();
        FailureSignal signal = FailureSignal.extract(evidence.getLogExcerpt());
        SlackSnapshot s = new SlackSnapshot();
        s.build = run.getNumber();
        s.job = safe(run.getParent().getFullDisplayName(), 180);
        s.lastGood = evidence.getPreviousSuccessfulBuildNumber();
        s.firstBad = view.getHistory().isVerified() ? view.getHistory().getFirstBad() : 0;
        s.category = safe(signal.getCategory(), 120);
        s.failure = safe(signal.getLocation() + "\n" + signal.getText(), 1200);
        s.stage = safe(signal.getStage(), 160);
        s.module = safe(signal.getModule(), 160);
        s.test = safe(signal.getTest(), 250);
        s.source = safe(signal.getFile(), 400);
        String diagnostic = signal.getText()
                .lines()
                .findFirst()
                .orElse("")
                .replaceAll(":\\d+(?::\\d+)?", ":#")
                .replaceAll("\\[\\d+,\\d+\\]", "[#]")
                .replaceAll("\\s+", " ");
        s.signature = signal.isSpecific()
                ? digest(String.join(
                        "|",
                        s.category,
                        s.source,
                        signal.getSymbol(),
                        signal.getException(),
                        signal.getMethod(),
                        s.test,
                        s.stage,
                        s.module,
                        diagnostic))
                : "";
        List<RankedChange> ranked = RankedChange.rank(evidence.getChangeEntries(), signal);
        var relevant = ranked.stream()
                .filter(r -> !r.getGroup().equals("Other"))
                .limit(3)
                .toList();
        s.changes = SlackMessageText.summary(
                evidence.getChangeEntries().size(),
                ranked.stream().map(RankedChange::getPath).distinct().count());
        if (!relevant.isEmpty()) {
            RankedChange top = relevant.get(0);
            s.source = safe(top.getPath(), 400);
            s.commit = safe(top.getCommit(), 100);
            s.author = safe(top.getAuthor(), 160);
            s.reason = safe(top.getReason(), 600);
            s.limitation = safe(top.getLimitation(), 500);
            s.check = safe(top.getNextCheck(), 350);
            s.strength = safe(top.getStrength(), 80);
        } else {
            s.reason = "No change has a strong direct relationship to this failure.";
            s.limitation = "The changed files do not establish a cause; unrelated changes are not blamed.";
            s.check = "Inspect the failing check, its inputs and runtime environment.";
        }
        s.material = digest(relevant.stream()
                .map(r -> String.join("|", r.getPath(), r.getCommit(), r.getStrength(), r.getReason()))
                .sorted()
                .reduce("", (a, b) -> a + "\n" + b));
        s.buildUrl = trustedBuildUrl(run);
        if (!s.buildUrl.isEmpty()) {
            s.investigationUrl = s.buildUrl + "change-investigation/";
            if (episodeBaseline > 0 && episodeBaseline != action.getEvidence().getPreviousSuccessfulBuildNumber())
                s.investigationUrl += "?baseline=" + episodeBaseline + "&target=" + run.getNumber();
            s.changesUrl = s.buildUrl + "changes";
        }
        s.aiScope = evidence == action.getEvidence() && view.getHistory().isVerified()
                ? evidence.forChangeWindow(view.getChanges(), view.getWindowEnd())
                        .getAiScope()
                : evidence.getAiScope();
        var config = ChangeInvestigatorGlobalConfiguration.get();
        if (config.isAiEnabled() && config.getProviderConfig() != null) {
            var presentation = action.getAiPresentation();
            var ai = presentation.getAssessment();
            String scope = view.getHistory().isVerified()
                    ? action.getEvidence()
                            .forChangeWindow(view.getChanges(), view.getWindowEnd())
                            .getAiScope()
                    : action.getEvidence().getAiScope();
            boolean sameCorpus = evidence == action.getEvidence();
            if (sameCorpus && scope.equals(presentation.getScope()) && ai != null && ai.isCompleted())
                s.ai = SlackMessageText.ai(ai.getMostLikelyCause(), ai.getRecommendedChecks(), s.check);
            s.aiPending = sameCorpus && s.ai.isEmpty() && action.isAiAnalysisRunning();
        }
        return s;
    }

    public static String trustedBuildUrl(Run<?, ?> run) {
        String base = JenkinsLocationConfiguration.get().getUrl();
        if (base == null || base.length() > 1500) return "";
        try {
            URI root = URI.create(base);
            if (!("http".equals(root.getScheme()) || "https".equals(root.getScheme()))
                    || root.getHost() == null
                    || root.getUserInfo() != null
                    || root.getFragment() != null
                    || root.getQuery() != null) return "";
            String relative = run.getUrl();
            if (relative.startsWith("/")
                    || relative.contains(":")
                    || relative.contains("\\")
                    || relative.contains("..")) return "";
            return (base.endsWith("/") ? base : base + "/") + relative;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public static String safe(String value, int limit) {
        return FailureSignal.safe(value, limit).replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
    }

    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-256 support unavailable", e);
        }
    }

    public String toJson() throws IOException {
        String json = new ObjectMapper().writeValueAsString(this);
        if (json.length() > 16000) throw new IOException("Notification snapshot exceeds bound");
        return json;
    }

    public static SlackSnapshot fromJson(String json) throws IOException {
        if (json == null || json.length() > 16000) throw new IOException("Invalid notification snapshot");
        return new ObjectMapper().readValue(json, SlackSnapshot.class);
    }
}

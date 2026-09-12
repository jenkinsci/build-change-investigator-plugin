package io.jenkins.plugins.changeinvestigator.notification.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Locked Field Brief, Resolution Thread and Small Closure from an immutable evidence snapshot. */
public final class SlackRenderer {
    public static final int VERSION = 1;
    public static final int MAX_BLOCKS = 16;
    public static final int MAX_BYTES = 48 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final URI approvedJenkinsRoot;

    /** The root comes from administrator-approved Jenkins configuration, never from evidence. */
    public SlackRenderer(URI approvedJenkinsRoot) {
        this.approvedJenkinsRoot = Objects.requireNonNull(approvedJenkinsRoot);
        if (SafeContent.navigation(approvedJenkinsRoot.toString(), "") == null)
            throw new IllegalArgumentException("Invalid approved Jenkins root");
    }

    /** Only the explicit preview supports unchanged observations; dispatch must suppress them. */
    public enum Presentation {
        EVENT,
        INITIAL_BRIEF,
        CONTINUING_PREVIEW
    }

    public ObjectNode render(NotificationEvent event) {
        return render(event, Presentation.EVENT);
    }

    public ObjectNode render(NotificationEvent event, Presentation presentation) {
        ObjectNode e = event.snapshot();
        if ("AI_AVAILABLE".equals(t(e, "eventType")) && !currentAi(e))
            throw new IllegalArgumentException("Current-scope interpretation unavailable");
        Message m = new Message();
        String job = escape(t(e, "jobLabel"), 90, true);
        String current = "#" + e.path("build").path("number").asInt();
        if (e.path("humanReview").isObject()
                && "INVESTIGATION_UPDATED".equals(t(e, "eventType"))
                && presentation == Presentation.INITIAL_BRIEF) {
            brief(m, e, job, current);
            humanAnnotation(m, e);
        } else if (e.path("humanReview").isObject()
                && List.of("INVESTIGATION_UPDATED", "CORRECTION").contains(t(e, "eventType"))) {
            humanUpdate(m, e, job, current);
        } else if (List.of("RECOVERY_OBSERVED", "RESOLUTION_CONFIRMED").contains(t(e, "eventType"))) {
            closure(m, e, job, presentation == Presentation.INITIAL_BRIEF);
        } else if (presentation == Presentation.INITIAL_BRIEF) {
            brief(m, e, job, current);
        } else if (presentation == Presentation.CONTINUING_PREVIEW) {
            m.header("Continuing investigation · " + job + " " + current);
            m.section("*Current failure signal*\n" + signal(e, 950));
            m.context(
                    "Investigation unchanged. No material evidence update. Preview only; delivery suppressed by default.");
            actions(m, e, false);
        } else
            switch (t(e, "eventType")) {
                case "INVESTIGATION_OPENED" -> brief(m, e, job, current);
                case "AI_AVAILABLE" -> {
                    if (!currentAi(e)) throw new IllegalArgumentException("Current-scope interpretation unavailable");
                    m.header("AI interpretation available · " + job + " " + current);
                    ai(m, e);
                    m.context("Interpretation supplements the deterministic evidence; it is not proof of cause.");
                    actions(m, e, false);
                }
                case "RECOVERY_CANDIDATE" -> {
                    m.header("Possible recovery change · " + job + " " + current);
                    m.section("*Possible recovery change · unverified*\n"
                            + escape(
                                    or(t(e.path("recovery"), "basis"), "Inspect the candidate in the investigation."),
                                    400,
                                    false));
                    m.context("Recovery and a causal fix have not been established by this candidate.");
                    actions(m, e, false);
                }
                case "RECOVERY_OBSERVED", "RESOLUTION_CONFIRMED" -> closure(m, e, job, false);
                case "INVESTIGATION_UPDATED", "CORRECTION", "CASE_CLOSED" -> {
                    m.header("Investigation updated · " + job + " " + current);
                    List<String> changes = new ArrayList<>();
                    for (JsonNode reason : e.path("materialReasons")) changes.add(reason(reason.asText()));
                    if (e.path("materialReasons")
                            .toString()
                            .matches(".*(TOP_CANDIDATE_CHANGED|EVIDENCE_STRENGTH_CHANGED|RELEVANT_CHANGE_ADDED).*")) {
                        JsonNode candidate = e.path("topCandidates").path(0);
                        if (candidate.isObject())
                            changes.add("`" + code(t(candidate, "path"), 180, true) + "`\n*Why surfaced:* "
                                    + escape(t(candidate, "relationship"), 320, false) + "\n*Limitation:* "
                                    + escape(t(candidate, "limitation"), 280, false));
                    }
                    m.section("*What changed*\n"
                            + (changes.isEmpty()
                                    ? "Inspect the investigation for the current evidence and limitations."
                                    : String.join("\n", changes)));
                    m.context(boundary(e));
                    actions(m, e, false);
                }
                default -> throw new IllegalArgumentException("Unsupported investigation event");
            }
        return m.finish();
    }

    private static void humanAnnotation(Message m, JsonNode e) {
        JsonNode review = e.path("humanReview");
        String label = "CAUSE_CONFIRMED".equals(t(review, "action"))
                ? "Cause confirmed"
                : "CANDIDATE_NOT_RELATED".equals(t(review, "action"))
                        ? "Candidate marked not related"
                        : "Investigation reopened";
        m.context("*Human review:* " + label + " · " + escape(t(review, "actorLabel"), 100, false)
                + "\nHuman judgment supplements the original evidence; it does not establish a fix.");
        if (!t(review, "correctiveAction").isBlank()
                || !t(review, "validationBasis").isBlank())
            m.section("*Review:* " + escape(t(review, "correctiveAction"), 240, false) + "\n*Validation:* "
                    + escape(t(review, "validationBasis"), 200, false));
    }

    private void humanUpdate(Message m, JsonNode e, String job, String current) {
        JsonNode review = e.path("humanReview");
        boolean correction = "CORRECTION".equals(t(e, "eventType"));
        boolean cause = "CAUSE_CONFIRMED".equals(t(review, "action"));
        String assertion = "RESOLUTION".equals(t(review, "assertionKind"))
                ? "resolution confirmation"
                : "CAUSE".equals(t(review, "assertionKind")) ? "cause confirmation" : "confirmation";
        String outcome = "CONFIRMATION_REVOKED".equals(t(review, "action"))
                ? "The previous " + assertion + " was revoked. Review the retained evidence in Jenkins."
                : t(review, "correctiveAction");
        m.header((correction ? "Correction" : cause ? "Cause confirmed" : "Human review updated") + " · " + job + " "
                + current);
        m.context("Human review · authenticated Jenkins action");
        if (correction)
            m.section(
                    "*Previous " + assertion
                            + " is no longer current.*\nReview the corrected human outcome in Jenkins. Original evidence and audit history are retained.");
        else if (cause)
            m.section("*Confirmed cause · human review*\nThis assertion does not establish recovery or a fix.");
        else
            m.section(
                    "CANDIDATE_NOT_RELATED".equals(t(review, "action"))
                            ? "*Candidate marked not related · human review*\nOriginal deterministic evidence is retained."
                            : "*Investigation reopened · human review*\nThe unresolved investigation remains open.");
        m.section("*Reviewed by:* " + escape(t(review, "actorLabel"), 100, false)
                + "\n*" + (correction ? "Current outcome" : cause ? "Cause" : "Review") + ":* "
                + escape(outcome, 300, false)
                + "\n*Validation:* " + escape(t(review, "validationBasis"), 240, false));
        for (JsonNode candidate : e.path("topCandidates"))
            if (t(candidate, "candidateId").equals(t(review, "candidateId"))
                    && !t(review, "candidateId").isBlank())
                m.context("*Reviewed change:* `" + code(t(candidate, "path"), 180, true)
                        + "` · Original evidence retained.");
        m.context("*Observed failure:* " + signal(e, 420));
        actions(m, e, false);
    }

    private void brief(Message m, JsonNode e, String job, String current) {
        m.header("New investigation · " + job + " " + current);
        m.context(boundary(e));
        String stage = t(e.path("failureSignature").path("contextFields"), "stageLabel");
        m.section("*Observed failure*" + (stage.isBlank() ? "" : " · " + escape(stage, 140, true)) + "\n```"
                + signal(e, 950, true) + "```");
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode candidate : e.path("topCandidates"))
            if ("STRONG".equals(t(candidate, "strength"))) candidates.add(candidate);
        if (!candidates.isEmpty()) {
            for (JsonNode c : candidates.subList(0, Math.min(2, candidates.size()))) {
                m.section("*Most relevant change*\n`" + code(t(c, "path"), 180, true) + "`"
                        + (t(c, "commit").isBlank()
                                ? ""
                                : " · `"
                                        + escape(
                                                t(c, "commit")
                                                        .substring(
                                                                0,
                                                                Math.min(
                                                                        8,
                                                                        t(c, "commit")
                                                                                .length())),
                                                8,
                                                false)
                                        + "`")
                        + "\nStrong evidence · Author: " + escape(or(t(c, "authorLabel"), "unavailable"), 70, false)
                        + "\n*Why surfaced:* "
                        + escape(or(t(c, "relationship"), "Relationship detail unavailable."), 320, false)
                        + "\n*Limitation:* "
                        + escape(
                                or(t(c, "limitation"), "A causal relationship has not been established."), 280, false));
            }
        } else {
            StringBuilder text = new StringBuilder("*No strong related change found*\n");
            int count = 0;
            for (JsonNode c : e.path("topCandidates")) {
                if (count++ == 2) break;
                text.append('`').append(code(t(c, "path"), 160, true)).append("`\n");
                if ("MODERATE".equals(t(c, "strength")))
                    text.append("Possible relationship: ")
                            .append(escape(t(c, "relationship"), 180, false))
                            .append('\n');
            }
            text.append(
                    e.path("changesComplete").asBoolean()
                            ? "No strong direct relationship established."
                            : "SCM change evidence is incomplete; no strong relationship established.");
            text.append("\n*Limitation:* ")
                    .append(escape(
                            or(
                                    t(e.path("failureSummary"), "limitation"),
                                    "The supplied changes do not establish a cause."),
                            240,
                            false));
            m.section(text.toString());
        }
        m.context(
                e.path("allChangeCount").isNull()
                        ? "Changed-file count unavailable; full evidence remains in Jenkins."
                        : e.path("allChangeCount").asInt()
                                + (e.path("allChangeCount").asInt() == 1
                                        ? " changed file retained in Jenkins."
                                        : " changed files retained in Jenkins.")
                                + (e.path("changesComplete").asBoolean() ? "" : " Change set incomplete."));
        String check = e.path("suggestedChecks").path(0).asText("");
        if (check.isBlank() && !candidates.isEmpty()) check = t(candidates.get(0), "nextCheck");
        m.section("*Check first*\n*"
                + escape(
                        or(check, "Open the investigation to inspect the failure evidence and missing relationship."),
                        240,
                        false)
                + "*");
        JsonNode responders = e.path("suggestedResponders");
        if (responders.size() == 1
                && !"AMBIGUOUS".equals(t(responders.get(0), "resolutionStatus"))
                && !t(responders.get(0), "basis").isBlank()) {
            JsonNode r = responders.get(0);
            m.context(("TEAM".equals(t(r, "kind")) ? "*Suggested team:* " : "*Suggested responder:* ")
                    + escape(t(r, "label"), 100, false) + "\n*Routing basis:* " + escape(t(r, "basis"), 220, false)
                    + "\nSuggested responder, not an assignment.");
        } else m.context("Suggested responder unavailable; no responder has been assigned.");
        ai(m, e);
        actions(m, e, false);
        String build = buildUrl(e.path("build"));
        if (build != null) m.context("<" + build + "console|Console Output>");
    }

    private void closure(Message m, JsonNode e, String job, boolean root) {
        JsonNode recovery = e.path("recovery");
        int number = recovery.path("build")
                .path("number")
                .asInt(e.path("build").path("number").asInt());
        if (!"VERIFIED".equals(t(recovery, "coverage")) || !"SUCCESS".equals(t(recovery.path("build"), "result"))) {
            m.header(
                    "SUCCESS".equals(t(e.path("build"), "result"))
                            ? "Build #" + number + " succeeded · recovery unverified"
                            : "Recovery unverified · " + job);
            m.section("Affected-path coverage is not verified. This successful build does not establish recovery.");
        } else {
            m.header("Recovered in #" + number + " · " + job);
            if (root) m.context(boundary(e));
            m.context("Observed recovery build: #" + number + " SUCCESS");
            String assessment = t(recovery, "assessment");
            if ("CONFIRMED_FIX".equals(assessment)
                    && "CONFIRMED_RESOLUTION".equals(t(e, "lifecycleState"))
                    && e.path("confirmation").isObject()) {
                JsonNode c = e.path("confirmation");
                m.section("*Confirmed resolution · human review*\nCorrective action: "
                        + escape(t(c, "correctiveAction"), 300, false) + "\nConfirmed by: "
                        + escape(t(c, "actorLabel"), 100, false) + "\nValidation: "
                        + escape(t(c, "validationBasis"), 220, false));
            } else if ("LIKELY_RECOVERY_CHANGE".equals(assessment)
                    && !t(recovery, "basis").isBlank()) {
                m.section("*Likely recovery change*\n" + recoveryIdentity(e) + escape(t(recovery, "basis"), 400, false)
                        + "\nRelated change observed; this is not proof of a fix.");
            } else m.section("*Recovered — fix unknown*\nNo specific fix has been established.");
        }
        m.context("*Previous failure:* " + signal(e, 420));
        actions(m, e, true);
    }

    private static String recoveryIdentity(JsonNode e) {
        for (JsonNode id : e.path("recovery").path("candidateIds")) {
            for (JsonNode candidate : e.path("topCandidates")) {
                if (id.asText().equals(t(candidate, "candidateId"))) {
                    String commit = t(candidate, "commit");
                    return (commit.isBlank()
                                    ? ""
                                    : "`" + escape(commit.substring(0, Math.min(8, commit.length())), 8, false)
                                            + "` · ")
                            + "`" + code(t(candidate, "path"), 160, true) + "`\n";
                }
            }
        }
        return "";
    }

    private static void ai(Message m, JsonNode e) {
        JsonNode a = e.path("ai");
        if (currentAi(e)) {
            String text = "*AI interpretation*\n" + escape(t(a, "summary"), 360, false);
            if (!t(a, "suggestedCheck").isBlank())
                text += "\n*Suggested check:* " + escape(t(a, "suggestedCheck"), 200, false);
            m.section(text);
        } else if ("AI_FAILED".equals(t(a, "state")))
            m.context("AI interpretation unavailable. The deterministic investigation is unaffected.");
    }

    private static boolean currentAi(JsonNode e) {
        JsonNode a = e.path("ai");
        return "AI_COMPLETE".equals(t(a, "state"))
                && a.path("evidenceRevision").asLong(-1)
                        == e.path("evidenceRevision")
                                .asLong(e.path("caseRevision").asLong())
                && !t(a, "summary").isBlank();
    }

    private void actions(Message m, JsonNode e, boolean recovery) {
        ArrayNode buttons = MAPPER.createArrayNode();
        String build = buildUrl(e.path("build"));
        if (build != null) button(buttons, "Open Investigation", build + "change-investigation/", "open_investigation");
        if (recovery) {
            String target = buildUrl(e.path("recovery").path("build"));
            if (target != null) button(buttons, "View Recovery Build", target, "open_recovery");
        }
        if (!buttons.isEmpty()) m.blocks.addObject().put("type", "actions").set("elements", buttons);
    }

    private String buildUrl(JsonNode build) {
        String url = t(build, "url");
        try {
            URI target = URI.create(url);
            URI relative = approvedJenkinsRoot.relativize(target);
            if (relative.isAbsolute()
                    || relative.equals(target)
                    || !url.equals(SafeContent.navigation(approvedJenkinsRoot.toString(), relative.toString())))
                return null;
            String result = url.endsWith("change-investigation/")
                    ? url.substring(0, url.length() - "change-investigation/".length())
                    : url;
            if (!result.endsWith("/" + build.path("number").asInt() + "/")
                    || result.length() > 1800
                    || result.matches(".*[<>|\\s].*")) return null;
            return result;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void button(ArrayNode buttons, String label, String url, String id) {
        ObjectNode b = buttons.addObject().put("type", "button").put("url", url).put("action_id", id);
        b.putObject("text").put("type", "plain_text").put("text", label).put("emoji", false);
    }

    private static String boundary(JsonNode e) {
        JsonNode b = e.path("boundary");
        String good = b.path("lastKnownGood").isObject()
                ? "#" + b.path("lastKnownGood").path("number").asInt() + " last good"
                : "Last good unavailable";
        String first =
                b.path("firstBadVerified").asBoolean() && b.path("firstBad").isObject()
                        ? "#" + b.path("firstBad").path("number").asInt() + " first bad"
                        : "First bad not verified";
        return good + " → " + first + " → #" + e.path("build").path("number").asInt() + " current";
    }

    private static String signal(JsonNode e, int limit) {
        return signal(e, limit, false);
    }

    private static String signal(JsonNode e, int limit, boolean codeContext) {
        JsonNode f = e.path("failureSummary");
        String location = t(f, "location");
        return escape(
                (location.isBlank() ? "" : location + "\n") + or(t(f, "diagnostic"), "Failure diagnostic unavailable."),
                limit,
                false,
                !codeContext);
    }

    private static String t(JsonNode node, String key) {
        return node.path(key).asText("");
    }

    private static String or(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    /** Neutralizes data before inserting it into renderer-owned formatting or fallback text. */
    static String escape(String raw, int limit, boolean suffix) {
        return escape(raw, limit, suffix, true);
    }

    private static String code(String raw, int limit, boolean suffix) {
        return escape(raw, limit, suffix, false);
    }

    private static String escape(String raw, int limit, boolean suffix, boolean neutralizeFormatting) {
        String value = SafeContent.text(raw, 8192);
        if (value == null) return "";
        if (value.codePointCount(0, value.length()) > limit)
            value = suffix
                    ? "…"
                            + value.substring(
                                    value.offsetByCodePoints(0, value.codePointCount(0, value.length()) - limit + 1))
                    : value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
        value = value.replace('`', '′').replace('|', '｜').replace('@', '＠').replace("://", "：//");
        if (neutralizeFormatting)
            value = value.replace('*', '＊').replace('_', '＿').replace('~', '～');
        StringBuilder escaped = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int c = value.codePointAt(offset);
            offset += Character.charCount(c);
            String piece =
                    switch (c) {
                        case '&' -> "&amp;";
                        case '<' -> "&lt;";
                        case '>' -> "&gt;";
                        default -> new String(Character.toChars(c));
                    };
            if (escaped.length() + piece.length() > limit
                    || escaped.length() + piece.length() == limit && offset < value.length()) {
                escaped.append('…');
                break;
            }
            escaped.append(piece);
        }
        return escaped.toString();
    }

    private static String reason(String reason) {
        return switch (reason) {
            case "FIRST_BAD_VERIFIED" -> "First bad is now verified from comparable build evidence.";
            case "BOUNDARY_CORRECTED" -> "The verified comparison boundary changed.";
            case "TOP_CANDIDATE_CHANGED" -> "The highest-ranked relevant change changed.";
            case "EVIDENCE_STRENGTH_CHANGED" -> "The deterministic evidence relationship changed.";
            case "RELEVANT_CHANGE_ADDED" -> "A new relevant change is available.";
            case "RESPONDER_CHANGED" -> "The justified responder suggestion changed.";
            case "AI_COMPLETED" -> "A current-scope AI interpretation became available.";
            case "RECOVERY_CANDIDATE_ADDED" -> "A possible recovery change is available.";
            case "RECOVERY_VERIFIED" -> "Comparable execution establishes recovery.";
            case "HUMAN_CONFIRMATION" -> "Explicit resolution confirmation is recorded.";
            case "HISTORY_EXPIRED" -> "Historical evidence is no longer available.";
            default -> "The investigation evidence was corrected.";
        };
    }

    private static final class Message {
        private final ArrayNode blocks = MAPPER.createArrayNode();

        void header(String text) {
            blocks.addObject()
                    .put("type", "header")
                    .putObject("text")
                    .put("type", "plain_text")
                    .put("text", text.substring(0, Math.min(150, text.length())))
                    .put("emoji", false);
        }

        void section(String text) {
            blocks.addObject()
                    .put("type", "section")
                    .putObject("text")
                    .put("type", "mrkdwn")
                    .put("text", text)
                    .put("verbatim", true);
        }

        void context(String text) {
            blocks.addObject()
                    .put("type", "context")
                    .putArray("elements")
                    .addObject()
                    .put("type", "mrkdwn")
                    .put("text", text)
                    .put("verbatim", true);
        }

        ObjectNode finish() {
            StringBuilder fallback = new StringBuilder();
            for (JsonNode b : blocks) {
                if (b.has("text"))
                    fallback.append(b.path("text").path("text").asText()).append('\n');
                for (JsonNode item : b.path("elements"))
                    fallback.append(
                                    "button".equals(t(item, "type"))
                                            ? item.path("text").path("text").asText() + ": " + t(item, "url")
                                            : t(item, "text"))
                            .append('\n');
                if (b.path("text").path("text").asText().length() > 3000)
                    throw new IllegalArgumentException("Slack section limit");
            }
            ObjectNode result = MAPPER.createObjectNode()
                    .put("text", fallback.toString())
                    .put("parse", "none")
                    .put("link_names", false)
                    .put("unfurl_links", false)
                    .put("unfurl_media", false)
                    .put("reply_broadcast", false);
            result.set("blocks", blocks);
            if (blocks.size() > MAX_BLOCKS || result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IllegalArgumentException("Slack payload limit");
            return result;
        }
    }
}

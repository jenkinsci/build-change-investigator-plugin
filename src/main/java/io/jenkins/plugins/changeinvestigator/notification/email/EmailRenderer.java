package io.jenkins.plugins.changeinvestigator.notification.email;

import com.fasterxml.jackson.databind.JsonNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Locked Fieldnote and Case Update bodies from an immutable evidence snapshot. */
public final class EmailRenderer {
    public static final int VERSION = 1;
    public static final int MAX_HTML_BYTES = 24 * 1024;
    public static final int MAX_TEXT_BYTES = 8 * 1024;
    private final URI approvedJenkinsRoot;

    /** Navigation is scoped to the administrator-approved Jenkins root. */
    public EmailRenderer(URI approvedJenkinsRoot) {
        this.approvedJenkinsRoot = Objects.requireNonNull(approvedJenkinsRoot);
        if (SafeContent.navigation(approvedJenkinsRoot.toString(), "") == null)
            throw new IllegalArgumentException("Invalid approved Jenkins root");
    }

    /** A subject seed and both alternatives; the delivery layer owns MIME and stable thread headers. */
    public record Content(String subject, String html, String plainText) {}

    public enum Presentation {
        EVENT,
        INITIAL_FIELDNOTE,
        CONTINUING_PREVIEW
    }

    public Content render(NotificationEvent event) {
        return render(event, Presentation.EVENT);
    }

    public Content render(NotificationEvent event, Presentation presentation) {
        for (double scale : new double[] {1.0, 0.5, 0.25}) {
            Content result = renderBounded(event, presentation, scale);
            if (result != null) return result;
        }
        throw new IllegalArgumentException("Email content limit");
    }

    private Content renderBounded(NotificationEvent event, Presentation presentation, double scale) {
        JsonNode e = event.snapshot();
        String type = t(e, "eventType");
        if ("AI_AVAILABLE".equals(type) && !currentAi(e))
            throw new IllegalArgumentException("Current-scope interpretation unavailable");
        String rawJob = t(e, "jobLabel");
        if (rawJob.codePoints().anyMatch(c -> c == '\r' || c == '\n' || Character.isISOControl(c)))
            throw new IllegalArgumentException("Invalid email subject input");
        String job = bounded(or(rawJob, "Build investigation"), 100, true);
        boolean recovery = List.of("RECOVERY_OBSERVED", "RESOLUTION_CONFIRMED").contains(type);
        boolean initial =
                !recovery && ("INVESTIGATION_OPENED".equals(type) || presentation == Presentation.INITIAL_FIELDNOTE);
        boolean preview = presentation == Presentation.CONTINUING_PREVIEW;
        int number = recovery
                ? e.path("recovery")
                        .path("build")
                        .path("number")
                        .asInt(e.path("build").path("number").asInt())
                : e.path("build").path("number").asInt();
        String heading = preview
                ? "Continuing investigation"
                : recovery ? "Recovered" : initial ? "New investigation" : "Investigation update";
        if (recovery && !verifiedRecovery(e)) heading = "Successful build — recovery unverified";
        String subject = "[BCI] " + job + " #" + number + " — "
                + (preview
                        ? "continuing investigation"
                        : recovery
                                ? "recovery update"
                                : initial ? "regression investigation" : "investigation updated");
        Message m = new Message(scale);
        m.label("BUILD CHANGE INVESTIGATOR");
        m.heading(heading + " · #" + number);
        m.text(job, 13, "#657184", false, 100);
        if (preview) {
            m.text("Still failing with the same observed signature.", 14, "#354052", true, 100);
            m.text(signal(e, 170), 13, "#657184", false, 170);
            m.label("WHAT CHANGED");
            m.text("Investigation unchanged. No material evidence update.", 14, "#354052", false, 160);
            m.text("Next check unchanged. Preview only; delivery suppressed by default.", 12, "#657184", false, 160);
            actions(m, e, false);
        } else if (initial) {
            fieldnote(m, e);
        } else if (recovery) {
            recovery(m, e, number, presentation == Presentation.INITIAL_FIELDNOTE);
        } else if (List.of("INVESTIGATION_UPDATED", "CORRECTION", "CASE_CLOSED", "AI_AVAILABLE", "RECOVERY_CANDIDATE")
                .contains(type)) {
            update(m, e);
        } else throw new IllegalArgumentException("Unsupported investigation event");
        return m.finish(subject);
    }

    private void fieldnote(Message m, JsonNode e) {
        m.text(boundary(e), 14, "#273b4e", true, 180);
        String count = e.path("allChangeCount").isNull()
                ? "Changed-file count unavailable."
                : e.path("allChangeCount").asInt()
                        + (e.path("allChangeCount").asInt() == 1 ? " changed file" : " changed files");
        m.text(
                count + (e.path("changesComplete").asBoolean() ? "" : " · Change set incomplete."),
                12,
                "#657184",
                false,
                180);
        m.label("OBSERVED · FAILURE");
        m.diagnostic(signal(e, 420));
        String stage = t(e.path("failureSignature").path("contextFields"), "stageLabel");
        if (!stage.isBlank()) m.text("Stage: " + bounded(stage, 80, false), 12, "#657184", false, 100);
        List<JsonNode> strong = strong(e);
        if (!strong.isEmpty()) {
            m.label(strong.size() == 1 ? "MOST RELEVANT CHANGE" : "MOST RELEVANT CHANGES · SHARED STRONG EVIDENCE");
            for (JsonNode c : strong.subList(0, Math.min(2, strong.size()))) {
                m.text(bounded(t(c, "path"), 180, true), 14, "#172334", true, 180);
                m.text(
                        shortCommit(c) + " · " + bounded(or(t(c, "authorLabel"), "Author unavailable"), 50, false)
                                + " · Strong evidence",
                        12,
                        "#657184",
                        false,
                        100);
                candidateEvidence(m, c);
            }
            if (strong.size() > 2)
                m.text("Additional strong candidates retained in the full investigation.", 12, "#657184", false, 100);
        } else {
            m.text("No strong related change found.", 16, "#172334", true, 100);
            m.text(
                    or(
                            t(e.path("failureSummary"), "limitation"),
                            "No strong direct relationship is established by the available evidence."),
                    13,
                    "#354052",
                    false,
                    220);
            int shown = 0;
            for (JsonNode c : e.path("topCandidates")) {
                if (shown++ == 2) break;
                m.text("Changed path retained: " + bounded(t(c, "path"), 150, true), 12, "#657184", false, 180);
                if ("MODERATE".equals(t(c, "strength"))) {
                    m.text("Possible relationship: " + t(c, "relationship"), 12, "#657184", false, 180);
                    m.text(
                            "Limit: " + or(t(c, "limitation"), "This is not an established cause."),
                            12,
                            "#657184",
                            false,
                            180);
                }
            }
            if (shown == 0)
                m.text(
                        e.path("changesComplete").asBoolean()
                                ? "No changed paths supplied."
                                : "SCM change evidence unavailable or incomplete.",
                        12,
                        "#657184",
                        false,
                        100);
        }
        check(m, e, strong);
        JsonNode responders = e.path("suggestedResponders");
        if (responders.size() == 1
                && !"AMBIGUOUS".equals(t(responders.path(0), "resolutionStatus"))
                && !t(responders.path(0), "basis").isBlank()) {
            JsonNode r = responders.path(0);
            m.text(
                    ("TEAM".equals(t(r, "kind")) ? "Suggested team: " : "Suggested responder: ") + t(r, "label"),
                    13,
                    "#354052",
                    true,
                    140);
            m.text("Routing basis: " + t(r, "basis"), 12, "#657184", false, 180);
            m.text("Suggestion, not an assignment.", 11, "#657184", false, 100);
        } else m.text("Suggested responder not established.", 12, "#657184", false, 100);
        actions(m, e, false);
        ai(m, e);
    }

    private void update(Message m, JsonNode e) {
        m.text(signal(e, 170), 13, "#657184", false, 170);
        String type = t(e, "eventType");
        if (!"AI_AVAILABLE".equals(type) && !"RECOVERY_CANDIDATE".equals(type)) {
            List<JsonNode> current = strong(e);
            if (!current.isEmpty()) {
                List<String> paths = new ArrayList<>();
                for (JsonNode c : current.subList(0, Math.min(2, current.size())))
                    paths.add(bounded(t(c, "path"), 70, true));
                m.text(
                        (paths.size() == 1 ? "Current relevant change: " : "Current relevant changes: ")
                                + String.join(" · ", paths),
                        13,
                        "#354052",
                        false,
                        180);
            }
        }
        m.label("WHAT CHANGED");
        if ("AI_AVAILABLE".equals(type)) {
            m.text("A current-scope AI interpretation became available.", 14, "#354052", false, 180);
            actions(m, e, false);
            ai(m, e);
            m.text(
                    "Interpretation supplements deterministic evidence; it is not proof of cause.",
                    12,
                    "#657184",
                    false,
                    180);
            return;
        }
        if ("RECOVERY_CANDIDATE".equals(type)) {
            m.text("Possible recovery change · unverified", 14, "#354052", true, 100);
            m.text(
                    or(t(e.path("recovery"), "basis"), "Inspect the candidate in the investigation."),
                    14,
                    "#354052",
                    false,
                    280);
            m.text("Recovery and a causal fix have not been established by this candidate.", 12, "#657184", false, 180);
        } else {
            List<String> reasons = new ArrayList<>();
            for (JsonNode reason : e.path("materialReasons")) reasons.add(reason(reason.asText()));
            m.text(
                    reasons.isEmpty()
                            ? "Inspect the current investigation evidence and limitations."
                            : String.join(" ", reasons),
                    14,
                    "#354052",
                    false,
                    280);
            if (e.path("materialReasons")
                    .toString()
                    .matches(".*(TOP_CANDIDATE_CHANGED|EVIDENCE_STRENGTH_CHANGED|RELEVANT_CHANGE_ADDED).*")) {
                int shown = 0;
                for (JsonNode c : e.path("topCandidates")) {
                    if (shown++ == 2) break;
                    m.text("Current change: " + bounded(t(c, "path"), 140, true), 13, "#354052", false, 170);
                    m.text(
                            "STRONG".equals(t(c, "strength"))
                                    ? "Strong evidence"
                                    : "Possible relationship; not a strong match",
                            12,
                            "#657184",
                            false,
                            100);
                    candidateEvidence(m, c);
                }
            }
            m.text(boundary(e), 12, "#657184", false, 180);
            check(m, e, strong(e));
        }
        actions(m, e, false);
        if ("AI_FAILED".equals(t(e.path("ai"), "state"))) ai(m, e);
    }

    private void recovery(Message m, JsonNode e, int number, boolean root) {
        JsonNode r = e.path("recovery");
        if (root) m.text(boundary(e), 12, "#657184", false, 180);
        m.text("Previous failure: " + signal(e, 160), 13, "#354052", false, 180);
        if (!verifiedRecovery(e)) {
            m.text(
                    "Affected-check coverage has not been verified. Recovery is not established.",
                    13,
                    "#354052",
                    false,
                    180);
        } else {
            m.text("Build: #" + number + " SUCCESS", 14, "#28694b", true, 100);
            JsonNode c = e.path("confirmation");
            if ("CONFIRMED_FIX".equals(t(r, "assessment"))
                    && "CONFIRMED_RESOLUTION".equals(t(e, "lifecycleState"))
                    && c.isObject()
                    && !t(c, "actorLabel").isBlank()
                    && !t(c, "validationBasis").isBlank()) {
                m.text("Confirmed fix", 16, "#28694b", true, 100);
                m.text("Confirmation: " + t(c, "correctiveAction"), 12, "#657184", false, 160);
                m.text(
                        "Provenance: " + t(c, "actorLabel") + " · " + t(c, "validationBasis"),
                        12,
                        "#657184",
                        false,
                        160);
            } else if ("LIKELY_RECOVERY_CHANGE".equals(t(r, "assessment"))
                    && !t(r, "basis").isBlank()) {
                m.text("Likely recovery change — not proof.", 15, "#28694b", true, 100);
                for (JsonNode id : r.path("candidateIds")) {
                    boolean found = false;
                    for (JsonNode candidate : e.path("topCandidates")) {
                        if (id.asText().equals(t(candidate, "candidateId"))) {
                            m.text(
                                    shortCommit(candidate) + " · " + bounded(t(candidate, "path"), 140, true),
                                    13,
                                    "#354052",
                                    false,
                                    160);
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
                m.text(t(r, "basis"), 12, "#657184", false, 180);
            } else m.text("Recovered — fix unknown.", 16, "#28694b", true, 100);
        }
        actions(m, e, true);
    }

    private static void candidateEvidence(Message m, JsonNode c) {
        m.text(
                "Why surfaced: " + or(t(c, "relationship"), "Relationship detail unavailable."),
                13,
                "#354052",
                false,
                220);
        m.text(
                "Limit: " + or(t(c, "limitation"), "This relationship does not establish the cause."),
                12,
                "#657184",
                false,
                200);
    }

    private static void check(Message m, JsonNode e, List<JsonNode> strong) {
        String check = e.path("suggestedChecks").path(0).asText("");
        if (check.isBlank() && !strong.isEmpty()) check = t(strong.get(0), "nextCheck");
        m.label("CHECK FIRST");
        m.text(
                or(check, "Open the investigation to inspect the failure evidence and missing relationship."),
                16,
                "#172334",
                true,
                180);
    }

    private static void ai(Message m, JsonNode e) {
        JsonNode a = e.path("ai");
        if (currentAi(e)) {
            m.label("AI INTERPRETATION · OPTIONAL");
            m.text(t(a, "summary"), 13, "#584b68", false, 220);
            if (!t(a, "suggestedCheck").isBlank())
                m.text("Suggested check: " + t(a, "suggestedCheck"), 13, "#584b68", false, 160);
        } else if ("AI_FAILED".equals(t(a, "state")))
            m.text(
                    "AI interpretation unavailable. The deterministic investigation is unaffected.",
                    12,
                    "#657184",
                    false,
                    180);
    }

    private static boolean currentAi(JsonNode e) {
        JsonNode a = e.path("ai");
        return "AI_COMPLETE".equals(t(a, "state"))
                && !t(a, "summary").isBlank()
                && a.path("evidenceRevision").asLong(-1)
                        == e.path("caseRevision").asLong();
    }

    private static boolean verifiedRecovery(JsonNode e) {
        return "VERIFIED".equals(t(e.path("recovery"), "coverage"))
                && "SUCCESS".equals(t(e.path("recovery").path("build"), "result"));
    }

    private void actions(Message m, JsonNode e, boolean recovery) {
        String build = buildUrl(e.path("build"));
        if (build != null) m.action("Open Investigation", build + "change-investigation/", true);
        else m.text("Investigation link unavailable.", 12, "#657184", false, 100);
        if (recovery) {
            String recovered = buildUrl(e.path("recovery").path("build"));
            if (recovered != null) m.action("View Recovery Build", recovered, false);
        } else if (build != null) m.action("Console Output", build + "console", false);
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
            return result.endsWith("/" + build.path("number").asInt() + "/")
                            && result.length() <= 1800
                            && !result.matches(".*[<>|\\s].*")
                    ? result
                    : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<JsonNode> strong(JsonNode e) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode c : e.path("topCandidates")) if ("STRONG".equals(t(c, "strength"))) result.add(c);
        return result;
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
        JsonNode f = e.path("failureSummary");
        String location = bounded(t(f, "location"), 180, true);
        return bounded(
                (location.isBlank() ? "" : location + "\n") + or(t(f, "diagnostic"), "Failure diagnostic unavailable."),
                limit,
                false);
    }

    private static String shortCommit(JsonNode c) {
        String commit = t(c, "commit");
        return commit.isBlank() ? "Revision unavailable" : commit.substring(0, Math.min(8, commit.length()));
    }

    private static String t(JsonNode n, String key) {
        return n.path(key).asText("");
    }

    private static String or(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private static String bounded(String raw, int limit, boolean suffix) {
        String value = SafeContent.text(raw, 8192);
        if (value == null) return "";
        value = value.trim().replace("://", "：//");
        int count = value.codePointCount(0, value.length());
        if (count > limit)
            return suffix
                    ? "…" + value.substring(value.offsetByCodePoints(0, count - limit + 1))
                    : value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
        return value;
    }

    private static String html(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
        private final StringBuilder parts = new StringBuilder();
        private final List<String> plain = new ArrayList<>();
        private final double scale;

        Message(double scale) {
            this.scale = scale;
        }

        void label(String value) {
            text(value, 11, "#657184", true, 100);
        }

        void heading(String value) {
            plain.add(value);
            parts.append("<h1 style=\"margin:0 0 10px;font-size:24px;line-height:1.3;color:#172334\">")
                    .append(html(value))
                    .append("</h1>");
        }

        void text(String raw, int size, String color, boolean bold, int limit) {
            String value = bounded(raw, Math.max(32, (int) (limit * scale)), false);
            if (value.isBlank()) return;
            plain.add(value.replace('<', '‹').replace('>', '›'));
            parts.append("<p style=\"margin:0 0 10px;font-size:")
                    .append(size)
                    .append("px;line-height:1.45;color:")
                    .append(color)
                    .append(';')
                    .append(bold ? "font-weight:600;" : "")
                    .append("overflow-wrap:anywhere;word-break:break-word\">")
                    .append(html(value))
                    .append("</p>");
        }

        void diagnostic(String value) {
            value = bounded(value, Math.max(160, (int) (420 * scale)), false);
            plain.add(value.replace('<', '‹').replace('>', '›'));
            parts.append(
                            "<div style=\"margin:0 0 12px;padding:11px 13px;border-left:3px solid #a9474f;background:#f8f3f3;color:#592c33;font:13px/1.45 Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word\">")
                    .append(html(value))
                    .append("</div>");
        }

        void action(String label, String target, boolean primary) {
            plain.add(label + ": " + target);
            if (primary)
                parts.append(
                                "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:12px 0\"><tr><td bgcolor=\"#245d91\" style=\"border-radius:4px\"><a href=\"")
                        .append(html(target))
                        .append(
                                "\" style=\"display:inline-block;padding:11px 17px;color:#fff;text-decoration:none;font-size:14px;font-weight:600\">")
                        .append(label)
                        .append("</a></td></tr></table>");
            else
                parts.append("<p style=\"margin:0 0 12px;font-size:12px\"><a href=\"")
                        .append(html(target))
                        .append("\" style=\"color:#245d91\">")
                        .append(label)
                        .append("</a></p>");
        }

        Content finish(String subject) {
            String body =
                    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
                            + html(subject)
                            + "</title></head><body style=\"margin:0;background:#eef1f4;font-family:Arial,Helvetica,sans-serif\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" style=\"padding:20px 10px\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:640px;background:#fff;border:1px solid #e0e5eb;table-layout:fixed\"><tr><td style=\"padding:24px\">"
                            + parts + "</td></tr></table></td></tr></table></body></html>";
            String text = String.join("\n\n", plain);
            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES
                    || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
                    || subject.codePointCount(0, subject.length()) > 180) return null;
            return new Content(subject, body, text);
        }
    }
}

package io.jenkins.plugins.changeinvestigator.slack.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Slack-only presentation: untrusted evidence is always plain text, never mrkdwn. */
public final class SlackMessageRenderer {
    private SlackMessageRenderer() {}

    /** Revoke only the responder mention without rebuilding frozen evidence or interpretation. */
    public static String withoutResponderMention(String frozenPayload, String frozenAuthor) throws IOException {
        if (frozenPayload == null || frozenPayload.length() > 32000)
            throw new IOException("Notification message exceeds bounds");
        var mapper = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
                        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                                .maxNestingDepth(20)
                                .maxStringLength(32000)
                                .build())
                        .build())
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var message = mapper.readTree(frozenPayload);
        var blocks = message == null ? null : message.get("blocks");
        if (blocks == null || !blocks.isArray() || blocks.size() > 40)
            throw new IOException("Invalid notification message");
        for (var block : blocks) {
            var text = block.get("text");
            if (text != null
                    && "mrkdwn".equals(text.path("type").asText())
                    && (text.path("text").asText().startsWith("Suggested responder: ")
                            || text.path("text").asText().matches("<@[UW][A-Z0-9]{8,31}>"))) {
                String prefix =
                        text.path("text").asText().startsWith("Suggested responder: ") ? "Suggested responder: " : "";
                ((com.fasterxml.jackson.databind.node.ObjectNode) block)
                        .set("text", mapper.valueToTree(plain(prefix + frozenAuthor, 1200)));
            }
        }
        String result = mapper.writeValueAsString(message);
        if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32000)
            throw new IOException("Notification message exceeds bounds");
        return result;
    }

    public static String initial(SlackSnapshot s, String verifiedMappedUser) throws IOException {
        List<Map<String, Object>> blocks = new ArrayList<>();
        header(blocks, "Build regression detected");
        paragraph(blocks, s.job + " · #" + s.build);
        context(
                blocks,
                (s.lastGood > 0 ? "#" + s.lastGood + " last good" : "Last good unavailable")
                        + " → " + (s.firstBad > 0 ? "#" + s.firstBad + " first bad" : "First bad not verified")
                        + (s.firstBad == s.build ? "" : " → #" + s.build + " current"));
        section(blocks, "What broke", SlackMessageText.failure(s.failure));
        if (!s.stage.isBlank()) context(blocks, "Affected check: " + s.stage);
        context(blocks, s.changes);
        if (!s.commit.isBlank())
            section(
                    blocks,
                    "Most relevant change",
                    abbreviated(s.commit) + " · " + s.author + "\n" + s.source
                            + (SlackMessageText.strength(s.strength).isBlank()
                                    ? ""
                                    : "\nEvidence strength: " + SlackMessageText.strength(s.strength)));
        section(blocks, "Why BCI surfaced it", SlackMessageText.reason(s.reason));
        section(blocks, "Limitation", SlackMessageText.limitation(s.limitation, s.failure));
        section(blocks, "Check first", SlackMessageText.check(s.check));
        if (!s.author.isBlank() && !s.author.equalsIgnoreCase("unknown")) {
            label(blocks, "Suggested responder");
            if (io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport.isValidUserId(
                    verifiedMappedUser)) {
                blocks.add(Map.of(
                        "type",
                        "section",
                        "text",
                        Map.of("type", "mrkdwn", "verbatim", true, "text", "<@" + verifiedMappedUser + ">")));
            } else paragraph(blocks, s.author);
        }
        String interpretation = SlackMessageText.compactAi(s.ai, s.check);
        if (!interpretation.isBlank()) {
            blocks.add(Map.of("type", "divider"));
            section(blocks, "AI Analysis", interpretation);
            context(blocks, "Interpretation only, not a confirmed cause.");
        }
        actions(blocks, s.investigationUrl, s.buildUrl, s.changesUrl);
        return payload(s.job + " #" + s.build + " — build regression investigation", blocks);
    }

    public static String material(SlackSnapshot s) throws IOException {
        List<Map<String, Object>> blocks = new ArrayList<>();
        header(blocks, "Investigation updated");
        paragraph(blocks, s.job + " · Build #" + s.build);
        if (s.firstBad > 0) context(blocks, "First bad: #" + s.firstBad);
        section(
                blocks,
                "Evidence worth checking",
                s.source + (s.commit.isBlank() ? "" : " · " + abbreviated(s.commit)) + "\n" + s.reason);
        context(blocks, "Limitation: " + SlackMessageText.limitation(s.limitation, s.failure));
        actions(blocks, s.investigationUrl, s.buildUrl, "");
        return payload(s.job + " #" + s.build + " — material investigation update", blocks);
    }

    public static String recovery(
            SlackSnapshot previous,
            int successfulBuild,
            String coverage,
            String likelyChange,
            String successfulBuildUrl)
            throws IOException {
        List<Map<String, Object>> blocks = new ArrayList<>();
        header(blocks, "🟢 Recovered");
        paragraph(
                blocks,
                previous.job + " · Build #" + successfulBuild + " passed after the regression at #"
                        + (previous.firstBad > 0 ? previous.firstBad : previous.build) + ".");
        section(blocks, "Recovery", coverage);
        section(
                blocks,
                likelyChange == null || likelyChange.isBlank() ? "Fix unknown" : "Likely recovery change",
                likelyChange == null || likelyChange.isBlank()
                        ? "The affected check passed. The evidence does not identify which change resolved it."
                        : likelyChange + "\nA related change is not proof of causation.");
        actions(blocks, previous.investigationUrl, successfulBuildUrl, "", "View Successful Build");
        return payload(previous.job + " #" + successfulBuild + " — verified recovery", blocks);
    }

    private static String abbreviated(String value) {
        return value.substring(0, Math.min(12, value.length()));
    }

    private static Map<String, Object> plain(String value, int limit) {
        return Map.of("type", "plain_text", "text", SlackSnapshot.safe(value, limit), "emoji", false);
    }

    private static void header(List<Map<String, Object>> blocks, String value) {
        blocks.add(Map.of("type", "header", "text", plain(value, 150)));
    }

    private static void paragraph(List<Map<String, Object>> blocks, String value) {
        blocks.add(Map.of("type", "section", "text", plain(value, 1800)));
    }

    private static void section(List<Map<String, Object>> blocks, String label, String value) {
        label(blocks, label);
        paragraph(blocks, value);
    }

    private static void label(List<Map<String, Object>> blocks, String value) {
        blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "*" + value + "*")));
    }

    private static void context(List<Map<String, Object>> blocks, String value) {
        blocks.add(Map.of("type", "context", "elements", List.of(plain(value, 1200))));
    }

    private static void actions(List<Map<String, Object>> blocks, String investigation, String build, String changes) {
        actions(blocks, investigation, build, changes, "View Build");
    }

    private static void actions(
            List<Map<String, Object>> blocks, String investigation, String build, String changes, String buildLabel) {
        List<Map<String, Object>> buttons = new ArrayList<>();
        button(buttons, "Open Investigation", investigation);
        button(buttons, buildLabel, build);
        button(buttons, "View Changes", changes);
        if (!buttons.isEmpty()) blocks.add(Map.of("type", "actions", "elements", buttons));
    }

    private static void button(List<Map<String, Object>> buttons, String title, String url) {
        if (url == null || url.isBlank() || url.length() > 2200) return;
        try {
            URI uri = URI.create(url);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) return;
            buttons.add(Map.of("type", "button", "text", plain(title, 75), "url", url));
        } catch (IllegalArgumentException ignored) {
            // An unavailable trusted link does not prevent the investigation message.
        }
    }

    private static String payload(String text, List<Map<String, Object>> blocks) throws IOException {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(
                "text",
                SlackSnapshot.safe(text, 400)
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;"));
        message.put("blocks", blocks);
        message.put("parse", "none");
        message.put("mrkdwn", false);
        message.put("link_names", false);
        message.put("unfurl_links", false);
        message.put("unfurl_media", false);
        String json = new ObjectMapper().writeValueAsString(message);
        if (blocks.size() > 40 || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32000)
            throw new IOException("Notification message exceeds bounds");
        return json;
    }
}

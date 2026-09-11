package io.jenkins.plugins.changeinvestigator.notification.slack;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class SlackRendererTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SlackRenderer RENDERER = new SlackRenderer(URI.create("https://jenkins.example.invalid/"));

    private static ObjectNode fixture(String name) throws Exception {
        try (var in = SlackRendererTest.class.getResourceAsStream(
                "/io/jenkins/plugins/changeinvestigator/notification/events-v1.json")) {
            return ((ObjectNode) JSON.readTree(in).path(name)).deepCopy();
        }
    }

    private static ObjectNode strong() throws Exception {
        ObjectNode e = fixture("specific");
        ObjectNode c = e.withArray("topCandidates").addObject();
        c.put("candidateId", "a".repeat(64))
                .put("path", "src/main/java/demo/risk/CollateralTrade.java")
                .put("commit", "d6689b963f4e91a7742f95ae4fca652f3a396622")
                .put("authorLabel", "mira.vale")
                .put("strength", "STRONG")
                .put(
                        "relationship",
                        "This file changed in the comparison window. The failure names the same source file.")
                .put("limitation", "The failing line is in a changed file; the exact line is not verified as changed.")
                .put("nextCheck", "Inspect diff near line 853.")
                .putArray("evidenceRefs")
                .add("changed-file");
        e.withArray("suggestedResponders")
                .addObject()
                .put("kind", "PERSON")
                .put("label", "mira.vale")
                .put("basis", "Authored the highest-ranked change.")
                .put("resolutionStatus", "SUGGESTED");
        return e;
    }

    private static void completed(ObjectNode e) {
        ((ObjectNode) e.path("ai"))
                .put("state", "AI_COMPLETE")
                .put("summary", "The change may have introduced a reference to an unavailable or misspelled variable.")
                .put("suggestedCheck", "Check the intended variable declaration and spelling.")
                .put("evidenceRevision", e.path("caseRevision").asInt());
    }

    private static ObjectNode render(ObjectNode event) {
        return RENDERER.render(NotificationEvent.freeze(event));
    }

    @Test
    public void allTenLockedVariantsAndExportActualPayloads() throws Exception {
        ObjectNode base = strong();
        completed(base);
        String[] names = {
            "01-new-strong",
            "02-new-ambiguous",
            "03-new-ai-disabled",
            "04-continuing-unchanged",
            "05-material-update",
            "06-ai-available",
            "07-possible-recovery",
            "08-recovered-likely",
            "09-recovered-unknown",
            "10-confirmed"
        };
        for (int i = 0; i < names.length; i++) {
            ObjectNode e = base.deepCopy();
            if (i == 1) {
                e = fixture("unresolved");
                ((ObjectNode) e.path("failureSummary"))
                        .put("limitation", "No supplied source change matches this compiler error.");
                e.putArray("suggestedChecks")
                        .add(
                                "Inspect the failing code path and runtime inputs; docs changes have no demonstrated relationship.");
                e.withArray("topCandidates").add(strong().path("topCandidates").get(0));
                ((ObjectNode) e.path("topCandidates").get(0))
                        .put("path", "docs/README.md")
                        .put("strength", "WEAK");
            }
            if (i == 2) e = strong();
            if (i == 4) {
                e.put("eventType", "INVESTIGATION_UPDATED");
                e.withArray("materialReasons").add("FIRST_BAD_VERIFIED");
            }
            if (i == 5) e.put("eventType", "AI_AVAILABLE");
            if (i == 6) {
                e.put("eventType", "RECOVERY_CANDIDATE");
                ((ObjectNode) e.path("recovery"))
                        .put(
                                "basis",
                                "A new change restores the variable spelling in CollateralTrade.java. Execution has not verified recovery.");
            }
            if (i == 7 || i == 8) {
                e = fixture("recovered");
                if (i == 7) {
                    e.withArray("topCandidates")
                            .add(strong().path("topCandidates").get(0));
                    ((ObjectNode) e.path("topCandidates").get(0))
                            .put("commit", "a17f6902a17f6902a17f6902a17f6902a17f6902");
                    ((ObjectNode) e.path("recovery"))
                            .put("assessment", "LIKELY_RECOVERY_CHANGE")
                            .put(
                                    "basis",
                                    "A newly introduced change updates CollateralTrade.java; comparable execution passed.")
                            .withArray("candidateIds")
                            .add("a".repeat(64));
                }
            }
            if (i == 9) e = fixture("confirmed");
            ObjectNode payload = i == 3
                    ? RENDERER.render(NotificationEvent.freeze(e), SlackRenderer.Presentation.CONTINUING_PREVIEW)
                    : render(e);
            String text = payload.path("text").asText();
            StringBuilder order = new StringBuilder();
            for (JsonNode block : payload.path("blocks"))
                order.append(block.path("type").asText()).append(' ');
            String expected =
                    switch (i) {
                        case 0 -> "header context section section context section context section actions context ";
                        case 1, 2 -> "header context section section context section context actions context ";
                        case 3 -> "header section context actions ";
                        case 4, 5, 6 -> "header section context actions ";
                        default -> "header context section context actions ";
                    };
            assertEquals(expected, order.toString());
            assertTrue(text.contains("Open Investigation"));
            assertFalse(payload.path("unfurl_links").asBoolean());
            assertEquals("none", payload.path("parse").asText());
            assertTrue(payload.path("blocks").size() <= SlackRenderer.MAX_BLOCKS);
            assertTrue(payload.toString().getBytes(StandardCharsets.UTF_8).length <= SlackRenderer.MAX_BYTES);
            if (i == 0) assertTrue(text.indexOf("Observed failure") < text.indexOf("Why surfaced"));
            if (i == 1) {
                assertTrue(text.contains("No strong related change found"));
                assertFalse(text.contains("Most relevant change"));
            }
            if (i == 2) assertFalse(text.contains("AI interpretation"));
            if (i == 3) assertTrue(text.contains("suppressed by default"));
            if (i >= 7) {
                assertFalse(text.contains("Check first"));
                assertFalse(text.contains("AI interpretation"));
            }
            Path dir = Path.of("target/slack-renderer-fixtures");
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve(names[i] + ".json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        }
    }

    @Test
    public void strictTwoCandidateMaximumAndAdjacentLimitations() throws Exception {
        ObjectNode e = strong();
        JsonNode c = e.path("topCandidates").get(0).deepCopy();
        for (int i = 0; i < 4; i++) e.withArray("topCandidates").add(c.deepCopy());
        String result = render(e).path("text").asText();
        assertEquals(2, result.split("Most relevant change", -1).length - 1);
        assertEquals(2, result.split("Why surfaced", -1).length - 1);
        assertEquals(2, result.split("Limitation", -1).length - 1);
    }

    @Test
    public void staleAiAndDisabledAiNeverDisplaceFacts() throws Exception {
        ObjectNode e = strong();
        completed(e);
        ((ObjectNode) e.path("ai")).put("evidenceRevision", 2);
        String text = render(e).path("text").asText();
        assertFalse(text.contains("AI interpretation"));
        assertTrue(text.contains("isPortolioIM"));
        ((ObjectNode) e.path("ai")).put("state", "AI_FAILED").putNull("summary").putNull("suggestedCheck");
        assertTrue(render(e).path("text").asText().contains("deterministic investigation is unaffected"));
    }

    @Test
    public void maliciousScmCannotCreateFormattingMentionsOrLinks() throws Exception {
        ObjectNode e = strong();
        String attack =
                "<@U123> <!channel> <!here> <!everyone> <https://evil.example.invalid|click> ``` *fake* _fake_ ~fake~ @everyone";
        ((ObjectNode) e.path("failureSummary")).put("diagnostic", attack);
        ((ObjectNode) e.path("topCandidates").get(0))
                .put("authorLabel", "<@U123>")
                .put("relationship", attack)
                .put("limitation", attack);
        completed(e);
        ((ObjectNode) e.path("ai")).put("summary", attack);
        ObjectNode payload = render(e);
        String text = payload.path("text").asText();
        assertFalse(text.contains("<@"));
        assertFalse(text.contains("<!"));
        assertFalse(text.contains("<https://evil"));
        assertFalse(
                payload.path("blocks").get(3).path("text").path("text").asText().contains("*fake*"));
        assertTrue(text.contains("&lt;＠U123&gt;"));
        for (JsonNode b : payload.path("blocks"))
            if (b.path("text").path("type").asText().equals("mrkdwn"))
                assertTrue(b.path("text").path("verbatim").asBoolean());
    }

    @Test
    public void oversizedUnicodeDegradesWithinBounds() throws Exception {
        ObjectNode e = strong();
        ((ObjectNode) e.path("failureSummary")).put("diagnostic", "&".repeat(1200));
        ObjectNode c = (ObjectNode) e.path("topCandidates").get(0);
        c.put("path", "&".repeat(512)).put("relationship", "&".repeat(400)).put("limitation", "&".repeat(400));
        completed(e);
        ((ObjectNode) e.path("ai")).put("summary", "&".repeat(400));
        ObjectNode p = render(e);
        assertTrue(p.path("text").asText().contains("…"));
        for (JsonNode b : p.path("blocks"))
            assertTrue(b.path("text").path("text").asText().length() <= 3000);
    }

    @Test
    public void navigationRequiresApprovedOriginAndBuildRoute() throws Exception {
        ObjectNode e = strong();
        for (String url : new String[] {
            "https://evil.example.invalid/job/demo/223/",
            "https://jenkins.example.invalid/job/../223/",
            "https://jenkins.example.invalid/job/%252e%252e/223/"
        }) {
            ((ObjectNode) e.path("build")).put("url", url);
            assertFalse(render(e).path("text").asText().contains("Open Investigation:"));
        }
        ((ObjectNode) e.path("build")).put("url", "https://jenkins.example.invalid/job/demo/223/change-investigation/");
        String text = render(e).path("text").asText();
        assertTrue(text.contains("/223/change-investigation/"));
        assertFalse(text.contains("change-investigation/change-investigation"));
        assertTrue(text.contains("/223/console"));
    }

    @Test
    public void unknownBoundaryAndAmbiguousRespondersRemainExplicit() throws Exception {
        ObjectNode e = strong();
        ((ObjectNode) e.path("boundary"))
                .putNull("firstBad")
                .putNull("lastKnownGood")
                .put("firstBadVerified", false)
                .put("proofStatus", "UNKNOWN");
        e.withArray("suggestedResponders")
                .add(e.path("suggestedResponders").get(0).deepCopy());
        String text = render(e).path("text").asText();
        assertTrue(text.contains("Last good unavailable"));
        assertTrue(text.contains("First bad not verified"));
        assertTrue(text.contains("Suggested responder unavailable"));
    }

    @Test
    public void aiOnlyRejectsStaleOrAbsentInterpretation() throws Exception {
        ObjectNode e = strong();
        e.put("eventType", "AI_AVAILABLE");
        assertThrows(IllegalArgumentException.class, () -> render(e));
        completed(e);
        ((ObjectNode) e.path("ai")).put("evidenceRevision", 2);
        assertThrows(IllegalArgumentException.class, () -> render(e));
    }

    @Test
    public void materialCandidateUpdateRetainsExactEvidenceAndUncertainty() throws Exception {
        ObjectNode e = strong();
        e.put("eventType", "INVESTIGATION_UPDATED");
        e.withArray("materialReasons").add("TOP_CANDIDATE_CHANGED");
        String text = render(e).path("text").asText();
        assertTrue(text.contains("CollateralTrade.java"));
        assertTrue(text.contains("Why surfaced"));
        assertTrue(text.contains("Limitation"));
        assertFalse(text.contains("Suggested responder"));
        assertFalse(text.contains("Confirmed fix"));
    }

    @Test
    public void codeContextsPreserveLiteralIdentifierCharacters() throws Exception {
        ObjectNode e = strong();
        ((ObjectNode) e.path("failureSummary")).put("diagnostic", "cannot find symbol: is_portfolio_im");
        ((ObjectNode) e.path("topCandidates").get(0)).put("path", "src/main_java/Collateral_Trade.java");
        String text = render(e).path("text").asText();
        assertTrue(text.contains("is_portfolio_im"));
        assertTrue(text.contains("src/main_java/Collateral_Trade.java"));
        assertFalse(text.contains("is＿portfolio＿im"));
    }
}

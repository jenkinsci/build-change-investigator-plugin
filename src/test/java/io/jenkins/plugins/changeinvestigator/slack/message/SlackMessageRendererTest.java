package io.jenkins.plugins.changeinvestigator.slack.message;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlackMessageRendererTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ATTACK = "<@U12345678> <!channel> <!here> <https://evil.invalid|click> *SCM*";

    private SlackSnapshot sample() {
        SlackSnapshot s = new SlackSnapshot();
        s.job = "Demo service";
        s.build = 223;
        s.lastGood = 220;
        s.firstBad = 221;
        s.failure = "CollateralTrade.java:853\ncannot find symbol: isPortolioIM";
        s.source = "src/main/java/CollateralTrade.java";
        s.commit = "a254c24e12345678";
        s.author = "Alex Morrison";
        s.strength = "Strong evidence";
        s.reason = "The failure names the same changed source file.";
        s.limitation = "The exact failing line has not been compared.";
        s.check = "Inspect the diff around line 853.";
        s.changes = "1 change · 1 file changed";
        s.buildUrl = "https://jenkins.example.invalid/job/demo/223/";
        s.investigationUrl = s.buildUrl + "change-investigation/";
        s.changesUrl = s.buildUrl + "changes";
        return s;
    }

    private List<String> markdown(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isObject() && node.path("type").asText().equals("mrkdwn"))
            values.add(node.path("text").asText());
        for (JsonNode child : node) values.addAll(markdown(child));
        return values;
    }

    @Test
    void revokedMentionKeepsFrozenAiAndEvidenceAndUsesPlainIdentity() throws Exception {
        SlackSnapshot snapshot = sample();
        snapshot.ai = "Frozen interpretation and suggested check.";
        String original = SlackMessageRenderer.initial(snapshot, "U12345678");
        String stripped = SlackMessageRenderer.withoutResponderMention(original, ATTACK);
        JsonNode message = JSON.readTree(stripped);
        assertTrue(stripped.contains(snapshot.ai));
        assertTrue(stripped.contains(snapshot.failure.split("\\n")[0]));
        assertTrue(markdown(message).stream().noneMatch(text -> text.contains("<@")));
        assertEquals(message, JSON.readTree(SlackMessageRenderer.withoutResponderMention(stripped, ATTACK)));
        assertThrows(IOException.class, () -> SlackMessageRenderer.withoutResponderMention(original + "{}", "Alex"));
        assertThrows(IOException.class, () -> SlackMessageRenderer.withoutResponderMention("{}", "Alex"));
        String workspaceMember = SlackMessageRenderer.initial(snapshot, "W12345678");
        assertTrue(workspaceMember.contains("<@W12345678>"));
        assertFalse(SlackMessageRenderer.withoutResponderMention(workspaceMember, "Alex")
                .contains("<@"));
    }

    @Test
    void allUntrustedEvidenceStaysOutsideMarkdown() throws Exception {
        SlackSnapshot s = sample();
        s.job = s.failure = s.source = s.author = s.reason = s.limitation = s.check = s.ai = ATTACK;
        JsonNode message = JSON.readTree(SlackMessageRenderer.initial(s, null));
        for (String value : markdown(message)) {
            assertFalse(value.contains("U12345678"));
            assertFalse(value.contains("evil.invalid"));
            assertFalse(value.contains("SCM"));
        }
        assertFalse(message.path("text").asText().contains("<@"));
        assertFalse(message.path("link_names").asBoolean());
        assertTrue(message.has("mrkdwn"));
        assertFalse(message.path("mrkdwn").asBoolean());
        assertFalse(message.path("unfurl_links").asBoolean());
    }

    @Test
    void initialContainsComparisonExactSignalAndNextCheck() throws Exception {
        String rendered = SlackMessageRenderer.initial(sample(), null);
        assertTrue(rendered.contains("#220 last good"));
        assertTrue(rendered.contains("#221 first bad"));
        assertTrue(rendered.contains("#223 current"));
        assertTrue(rendered.contains("CollateralTrade.java:853"));
        assertTrue(rendered.contains("isPortolioIM"));
        assertTrue(rendered.contains("a254c24e1234"));
        assertTrue(rendered.contains("Inspect the diff around line 853"));
        assertTrue(rendered.contains("Limitation"));
    }

    @Test
    void onlyExplicitValidMappingCanCreateMention() throws Exception {
        JsonNode mapped = JSON.readTree(SlackMessageRenderer.initial(sample(), "U12345678"));
        assertEquals(
                1,
                markdown(mapped).stream()
                        .filter(s -> s.contains("<@U12345678>"))
                        .count());
        JsonNode invalid = JSON.readTree(SlackMessageRenderer.initial(sample(), "U12345678> <!channel"));
        assertTrue(markdown(invalid).stream().noneMatch(s -> s.contains("<@") || s.contains("<!")));
        assertTrue(invalid.toString().contains("Alex Morrison"));
    }

    @Test
    void updatesAndRecoveryNeverMentionResponders() throws Exception {
        SlackSnapshot s = sample();
        s.author = ATTACK;
        JsonNode update = JSON.readTree(SlackMessageRenderer.material(s));
        JsonNode recovery =
                JSON.readTree(SlackMessageRenderer.recovery(s, 226, "Affected compilation passed.", "", s.buildUrl));
        assertTrue(markdown(update).stream().noneMatch(text -> text.contains("<@") || text.contains("<!")));
        assertTrue(markdown(recovery).stream().noneMatch(text -> text.contains("<@") || text.contains("<!")));
        assertTrue(recovery.toString().contains("Fix unknown"));
        assertFalse(recovery.toString().contains("Fixed by"));
    }

    @Test
    void aiIsSeparateInterpretationInsideInitialPayload() throws Exception {
        SlackSnapshot s = sample();
        s.ai = "The declaration may be missing; verify the diff.";
        String rendered = SlackMessageRenderer.initial(s, null);
        assertTrue(rendered.contains("AI Analysis"));
        assertTrue(rendered.contains("not a confirmed cause"));
        assertTrue(rendered.indexOf("Why BCI surfaced it") < rendered.indexOf("AI Analysis"));
        assertFalse(SlackMessageRenderer.material(s).contains(s.ai));
    }

    @Test
    void unsafeLinksAreOmitted() throws Exception {
        SlackSnapshot s = sample();
        s.buildUrl = "javascript:alert(1)";
        s.investigationUrl = "https://user:password@evil.invalid/";
        s.changesUrl = "https://evil.invalid/#fragment";
        JsonNode rendered = JSON.readTree(SlackMessageRenderer.initial(s, null));
        for (JsonNode block : rendered.path("blocks"))
            assertNotEquals("actions", block.path("type").asText());
        assertFalse(rendered.toString().contains("password"));
    }

    @Test
    void ambiguityDoesNotInventCandidateOrResponder() throws Exception {
        SlackSnapshot s = sample();
        s.commit = s.author = "";
        s.reason = "No change has a strong direct relationship to this failure.";
        String rendered = SlackMessageRenderer.initial(s, null);
        assertTrue(rendered.contains(s.reason));
        assertFalse(rendered.contains("Most relevant change"));
        assertFalse(rendered.contains("Suggested responder"));
    }

    @Test
    void oversizedEvidenceIsBoundedOrRejected() throws Exception {
        SlackSnapshot s = sample();
        s.failure = "x".repeat(200000);
        String rendered = SlackMessageRenderer.initial(s, null);
        assertTrue(rendered.length() < 32000);
        for (JsonNode block : JSON.readTree(rendered).path("blocks")) {
            JsonNode text = block.path("text");
            if (text.path("type").asText().equals("plain_text"))
                assertTrue(text.path("text").asText().length() <= 1800);
        }
    }

    @Test
    void snapshotsRoundTripAndRejectOversizedInput() throws Exception {
        SlackSnapshot s = sample();
        SlackSnapshot restored = SlackSnapshot.fromJson(s.toJson());
        assertEquals(s.failure, restored.failure);
        assertEquals(s.build, restored.build);
        assertEquals(s.investigationUrl, restored.investigationUrl);
        assertThrows(IOException.class, () -> SlackSnapshot.fromJson("x".repeat(16001)));
        s.failure = "x".repeat(16001);
        assertThrows(IOException.class, s::toJson);
    }

    @Test
    void compilerDiagnosticKeepsSymbolAndCoordinatesWithoutWorkspaceNoise() throws Exception {
        String relative = "[ERROR] src/main/java/Foo.java:[10,2] incompatible types: String cannot be converted to int";
        assertEquals(relative.replace("[ERROR] ", ""), SlackMessageText.failure(relative));
        for (String prefix :
                List.of("/var/jenkins_home/workspace/Demo Project/", "C:\\Jenkins\\workspace\\Demo Project\\")) {
            SlackSnapshot s = sample();
            s.failure = "[ERROR] " + prefix + "src/main/java/CollateralTrade.java:[853,16] cannot find symbol\n"
                    + "symbol: variable isPortolioIM\nlocation: class demo.CollateralTrade";
            String rendered = SlackMessageRenderer.initial(s, null);
            assertTrue(rendered.contains("CollateralTrade.java:853:16"));
            assertTrue(rendered.contains("cannot find symbol: isPortolioIM"));
            assertFalse(rendered.contains("workspace"));
            assertFalse(rendered.contains("location: class"));
        }
    }

    @Test
    void nonSourceFailurePathsKeepOnlyTheFilename() throws Exception {
        for (String prefix :
                List.of("/var/jenkins_home/workspace/Demo Project/", "C:\\Jenkins\\workspace\\Demo Project\\")) {
            SlackSnapshot s = sample();
            s.failure = "java.io.FileNotFoundException: " + prefix
                    + "config/runtime.properties (No such file or directory)";
            String rendered = SlackMessageRenderer.initial(s, null);
            assertTrue(rendered.contains("FileNotFoundException: runtime.properties"));
            assertTrue(rendered.contains("No such file or directory"));
            assertFalse(rendered.contains("workspace"));
            assertFalse(rendered.contains("Demo Project"));
        }
        assertEquals(
                "Cannot read config/runtime.properties",
                SlackMessageText.failure("Cannot read config/runtime.properties"));
        assertEquals(
                "Unable to reach https://example.invalid/config/runtime.properties",
                SlackMessageText.failure("Unable to reach https://example.invalid/config/runtime.properties"));
    }

    @Test
    void summariesAreNaturalAndStrengthIsLabeled() throws Exception {
        assertEquals("1 change · 1 file changed", SlackMessageText.summary(1, 1));
        assertEquals("2 changes · 3 files changed", SlackMessageText.summary(2, 3));
        assertEquals("0 changes · 0 files changed", SlackMessageText.summary(0, 0));
        String rendered = SlackMessageRenderer.initial(sample(), null);
        assertTrue(rendered.contains("Evidence strength: Strong"));
        assertFalse(rendered.contains("Strong evidence"));
    }

    @Test
    void aiDoesNotRepeatTheDeterministicRecommendationOrDisclaimer() throws Exception {
        SlackSnapshot s = sample();
        s.ai = SlackMessageText.ai(
                "The variable may be misspelled.",
                List.of(s.check, "Check the declaration and spelling of isPortolioIM."),
                s.check);
        String rendered = SlackMessageRenderer.initial(s, "U12345678");
        assertEquals(1, rendered.split(java.util.regex.Pattern.quote(s.check), -1).length - 1);
        assertEquals(1, rendered.split("Interpretation only, not a confirmed cause.", -1).length - 1);
        assertTrue(rendered.contains("Check the declaration and spelling"));
        assertFalse(rendered.contains("Mapped from"));
        assertFalse(rendered.contains("Initial investigation"));
        assertTrue(SlackMessageText.compactAi(s.check, s.check).isBlank());
        assertEquals(
                "Check the declaration.", SlackMessageText.ai(s.check, List.of("Check the declaration."), s.check));
        assertEquals(
                "The variable may be misspelled.",
                SlackMessageText.ai(
                        "The variable may be misspelled.",
                        List.of("Inspect the diff near line 853."),
                        "Inspect diff near line 853"));
    }

    @Test
    void rootOmitsRedundantCurrentBuildAndRepliesStaySmall() throws Exception {
        SlackSnapshot s = sample();
        s.firstBad = s.build;
        s.ai = "The declaration may be missing.";
        s.changesUrl = "";
        JsonNode root = JSON.readTree(SlackMessageRenderer.initial(s, null));
        assertFalse(root.toString().contains("#223 current"));
        JsonNode buttons =
                root.path("blocks").get(root.path("blocks").size() - 1).path("elements");
        assertEquals(2, buttons.size());
        assertFalse(root.toString().contains("View Changes"));
        JsonNode material = JSON.readTree(SlackMessageRenderer.material(s));
        JsonNode recovery =
                JSON.readTree(SlackMessageRenderer.recovery(s, 224, "Affected compilation passed.", "", s.buildUrl));
        assertTrue(root.path("blocks").size() <= 30);
        assertTrue(material.path("blocks").size() <= 9);
        assertTrue(recovery.path("blocks").size() <= 8);
        assertEquals(
                "🟢 Recovered",
                recovery.path("blocks").get(0).path("text").path("text").asText());
        assertFalse(material.toString().contains("What broke"));
        assertFalse(recovery.toString().contains(s.failure.split("\\n")[0]));
        assertTrue(recovery.toString().contains("View Successful Build"));
        assertFalse(material.toString().contains(s.ai));
    }
}

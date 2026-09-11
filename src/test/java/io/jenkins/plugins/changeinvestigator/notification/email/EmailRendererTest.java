package io.jenkins.plugins.changeinvestigator.notification.email;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.event.NotificationEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailRendererTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EmailRenderer RENDERER = new EmailRenderer(URI.create("https://jenkins.example.invalid/"));

    private static ObjectNode fixture(String name) throws Exception {
        try (var in = EmailRendererTest.class.getResourceAsStream(
                "/io/jenkins/plugins/changeinvestigator/notification/events-v1.json")) {
            return ((ObjectNode) JSON.readTree(in).path(name)).deepCopy();
        }
    }

    private static ObjectNode strong() throws Exception {
        ObjectNode e = fixture("specific");
        e.withArray("topCandidates")
                .addObject()
                .put("candidateId", "a".repeat(64))
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

    private static EmailRenderer.Content render(ObjectNode e) {
        return RENDERER.render(NotificationEvent.freeze(e));
    }

    private static void export(String name, ObjectNode event, EmailRenderer.Content content) throws Exception {
        Path dir = Path.of("target/email-renderer-fixtures");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".html"), content.html());
        Files.writeString(dir.resolve(name + ".txt"), content.plainText());
        Files.writeString(
                dir.resolve(name + ".json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(content));
        Files.writeString(
                dir.resolve(name + "-event.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(event));
        UUID delivery = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        boolean update = name.startsWith("14-")
                || name.startsWith("15-")
                || name.startsWith("16-")
                || name.startsWith("17-")
                || name.startsWith("18-");
        String root = update
                ? EmailMessage.messageId(
                        UUID.nameUUIDFromBytes("11-fieldnote-strong".getBytes(StandardCharsets.UTF_8)),
                        "investigations@example.invalid")
                : null;
        String subject = update ? "Re: [BCI] Synthetic compiler #223 — regression investigation" : content.subject();
        var mime = EmailMessage.prepare(
                delivery,
                "investigations@example.invalid",
                "engineering@example.invalid",
                subject,
                content.plainText(),
                content.html(),
                root,
                List.of(),
                1789041600000L);
        Files.write(dir.resolve(name + ".eml"), Base64.getDecoder().decode(mime.mimeBase64()));
    }

    @Test
    void canonicalEightStatesExportActualAlternatives() throws Exception {
        String[] names = {
            "11-fieldnote-strong",
            "12-fieldnote-ambiguous",
            "13-fieldnote-ai-disabled",
            "14-case-continuing",
            "15-case-material-update",
            "16-case-recovered-likely",
            "17-case-recovered-unknown",
            "18-case-confirmed"
        };
        for (int i = 0; i < names.length; i++) {
            ObjectNode e = strong();
            completed(e);
            if (i == 1) {
                e = fixture("unresolved");
                ((ObjectNode) e.path("failureSummary"))
                        .put("limitation", "No supplied source change matches this compiler error.");
                e.putArray("suggestedChecks")
                        .add(
                                "Inspect the failing code path and runtime inputs; docs changes have no demonstrated relationship.");
                e.withArray("topCandidates").add(strong().path("topCandidates").path(0));
                ((ObjectNode) e.path("topCandidates").path(0))
                        .put("path", "docs/README.md")
                        .put("strength", "WEAK");
            }
            if (i == 2) e = strong();
            if (i == 4) {
                e.put("eventType", "INVESTIGATION_UPDATED");
                e.withArray("materialReasons").add("FIRST_BAD_VERIFIED");
            }
            if (i == 5 || i == 6) {
                e = fixture("recovered");
                if (i == 5) {
                    e.withArray("topCandidates")
                            .add(strong().path("topCandidates").path(0));
                    ((ObjectNode) e.path("topCandidates").path(0))
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
            if (i == 7) e = fixture("confirmed");
            EmailRenderer.Content content = i == 3
                    ? RENDERER.render(NotificationEvent.freeze(e), EmailRenderer.Presentation.CONTINUING_PREVIEW)
                    : render(e);
            assertTrue(content.subject().startsWith("[BCI] "));
            assertTrue(content.html().contains("max-width:640px"));
            assertTrue(content.html().contains("<h1 "));
            assertTrue(content.html().contains("role=\"presentation\""));
            assertTrue(content.plainText().contains("Open Investigation: https://jenkins.example.invalid/"));
            assertFalse(content.html().contains("<script"));
            assertFalse(content.html().contains("<img"));
            assertFalse(content.html().contains("<form"));
            assertTrue(content.html().getBytes(StandardCharsets.UTF_8).length <= EmailRenderer.MAX_HTML_BYTES);
            assertTrue(content.plainText().getBytes(StandardCharsets.UTF_8).length <= EmailRenderer.MAX_TEXT_BYTES);
            if (i < 3) {
                assertTrue(content.plainText().contains("#220 last good → #221 first bad → #223 current"));
                assertTrue(content.plainText().contains("isPortolioIM"));
                assertTrue(content.plainText().contains("CHECK FIRST"));
            }
            if (i == 1) {
                assertTrue(content.plainText().contains("No strong related change found"));
                assertFalse(content.plainText().contains("MOST RELEVANT CHANGE"));
                assertFalse(content.plainText().contains("Suggested responder: mira"));
            }
            if (i == 2) assertFalse(content.plainText().contains("AI INTERPRETATION"));
            if (i == 3) assertTrue(content.plainText().contains("suppressed by default"));
            if (i >= 5) {
                assertFalse(content.plainText().contains("CHECK FIRST"));
                assertFalse(content.plainText().contains("AI INTERPRETATION"));
                assertTrue(content.plainText().contains("Previous failure:"));
                assertTrue(content.plainText().contains("View Recovery Build:"));
            }
            export(names[i], e, content);
        }
    }

    @Test
    void fieldnoteOrderAndTwoStrongCandidatesWithAdjacentLimits() throws Exception {
        ObjectNode e = strong();
        completed(e);
        for (int i = 0; i < 4; i++)
            e.withArray("topCandidates").add(e.path("topCandidates").path(0).deepCopy());
        String plain = render(e).plainText();
        String[] ordered = {
            "New investigation",
            "#220 last good",
            "1 changed file",
            "OBSERVED · FAILURE",
            "MOST RELEVANT CHANGES",
            "d6689b96",
            "Why surfaced:",
            "Limit:",
            "CHECK FIRST",
            "Suggested responder:",
            "Routing basis:",
            "Open Investigation:",
            "Console Output:",
            "AI INTERPRETATION"
        };
        int at = -1;
        for (String value : ordered) {
            int next = plain.indexOf(value, at + 1);
            assertTrue(next > at, value);
            at = next;
        }
        assertEquals(2, plain.split("Why surfaced:", -1).length - 1);
        assertEquals(2, plain.split("Limit:", -1).length - 1);
        assertTrue(plain.contains("Additional strong candidates retained"));
    }

    @Test
    void materialSupersessionCanBecomeFullRootWithoutReplayingRecovery() throws Exception {
        ObjectNode e = strong();
        e.put("eventType", "INVESTIGATION_UPDATED");
        e.withArray("materialReasons").add("TOP_CANDIDATE_CHANGED");
        assertFalse(render(e).plainText().contains("OBSERVED · FAILURE"));
        String root = RENDERER.render(NotificationEvent.freeze(e), EmailRenderer.Presentation.INITIAL_FIELDNOTE)
                .plainText();
        assertTrue(root.contains("OBSERVED · FAILURE"));
        assertTrue(root.contains("Why surfaced:"));
        assertTrue(root.contains("CHECK FIRST"));
        String recovery = RENDERER.render(
                        NotificationEvent.freeze(fixture("recovered")), EmailRenderer.Presentation.INITIAL_FIELDNOTE)
                .plainText();
        assertTrue(recovery.contains("Previous failure:"));
        assertTrue(recovery.contains("last good"));
        assertFalse(recovery.contains("New investigation"));
        assertFalse(recovery.contains("CHECK FIRST"));
    }

    @Test
    void staleDisabledAndFailedAiDoNotReplaceEvidenceOrLeakErrors() throws Exception {
        ObjectNode e = strong();
        completed(e);
        ((ObjectNode) e.path("ai")).put("evidenceRevision", 2);
        assertFalse(render(e).plainText().contains("AI INTERPRETATION"));
        assertTrue(render(e).plainText().contains("isPortolioIM"));
        e.put("eventType", "AI_AVAILABLE");
        ObjectNode stale = e;
        assertThrows(IllegalArgumentException.class, () -> render(stale));
        e.put("eventType", "INVESTIGATION_OPENED");
        ((ObjectNode) e.path("ai"))
                .put("state", "AI_FAILED")
                .put("summary", "PRIVATE_PROVIDER_BODY")
                .putNull("suggestedCheck");
        assertThrows(IllegalArgumentException.class, () -> render(e));
        ((ObjectNode) e.path("ai")).putNull("summary").putNull("suggestedCheck");
        assertTrue(render(e).plainText().contains("deterministic investigation is unaffected"));
        assertFalse(render(e).html().contains("PRIVATE_PROVIDER_BODY"));
    }

    @Test
    void aiUpdateIsCompactCurrentInterpretationAndMaterialDoesNotReplayIt() throws Exception {
        ObjectNode e = strong();
        completed(e);
        e.put("eventType", "AI_AVAILABLE");
        String ai = render(e).plainText();
        assertTrue(ai.contains("AI INTERPRETATION"));
        assertTrue(ai.contains("not proof of cause"));
        assertFalse(ai.contains("Suggested responder:"));
        assertFalse(ai.contains("MOST RELEVANT CHANGE"));
        e.put("eventType", "INVESTIGATION_UPDATED");
        e.withArray("materialReasons").add("FIRST_BAD_VERIFIED");
        assertFalse(render(e).plainText().contains("AI INTERPRETATION"));
    }

    @Test
    void maliciousHtmlDataCannotCreateMarkupLinksOrHeaderFields() throws Exception {
        ObjectNode e = strong();
        completed(e);
        String attack = "<img src=x onerror=alert(1)> <a href='https://evil.example.invalid/'>fake</a> & \"quoted\"";
        ((ObjectNode) e.path("failureSummary")).put("diagnostic", attack);
        ((ObjectNode) e.path("topCandidates").path(0))
                .put("authorLabel", attack)
                .put("path", attack)
                .put("relationship", attack)
                .put("limitation", attack);
        ((ObjectNode) e.path("suggestedResponders").path(0))
                .put("label", attack)
                .put("basis", attack);
        ((ObjectNode) e.path("ai")).put("summary", attack);
        e.putArray("suggestedChecks").add(attack);
        EmailRenderer.Content c = render(e);
        assertFalse(c.html().contains("<img"));
        assertFalse(c.html().contains("href='https://evil"));
        assertTrue(c.html().contains("&lt;img"));
        assertFalse(c.plainText().contains("<img"));
        assertFalse(c.plainText().contains("https://evil"));
        e.put("jobLabel", "demo\r\nBcc: victim@example.invalid");
        assertThrows(IllegalArgumentException.class, () -> render(e));
    }

    @Test
    void oversizedUnicodeAndEscapingRemainBoundedWithVisibleEllipsis() throws Exception {
        ObjectNode e = strong();
        completed(e);
        String huge = "😀<&>\"'".repeat(700);
        ((ObjectNode) e.path("failureSummary")).put("diagnostic", huge);
        ObjectNode candidate = (ObjectNode) e.path("topCandidates").path(0);
        candidate
                .put("path", "deep/".repeat(80) + "CollateralTrade.java")
                .put("relationship", huge)
                .put("limitation", huge)
                .put("authorLabel", huge);
        e.withArray("topCandidates").add(candidate.deepCopy());
        ((ObjectNode) e.path("ai")).put("summary", huge).put("suggestedCheck", huge);
        e.putArray("suggestedChecks").add(huge);
        EmailRenderer.Content content = render(e);
        assertTrue(content.html().getBytes(StandardCharsets.UTF_8).length <= EmailRenderer.MAX_HTML_BYTES);
        assertTrue(content.plainText().getBytes(StandardCharsets.UTF_8).length <= EmailRenderer.MAX_TEXT_BYTES);
        assertTrue(content.html().contains("…"));
        assertTrue(content.plainText().contains("CollateralTrade.java"));
        assertFalse(content.html().contains("<img"));
        export("19-long-path-message", e, content);
    }

    @Test
    void moderateOnlyAndMissingHistoryStayExplicitWithoutInventingCounts() throws Exception {
        ObjectNode e = strong();
        ((ObjectNode) e.path("topCandidates").path(0)).put("strength", "MODERATE");
        e.putArray("suggestedResponders");
        ((ObjectNode) e.path("boundary")).put("firstBadVerified", false).putNull("lastKnownGood");
        e.putNull("allChangeCount").put("changesComplete", false);
        String plain = render(e).plainText();
        assertTrue(plain.contains("Last good unavailable"));
        assertTrue(plain.contains("First bad not verified"));
        assertTrue(plain.contains("Changed-file count unavailable"));
        assertTrue(plain.contains("Possible relationship:"));
        assertFalse(plain.contains("MOST RELEVANT CHANGE"));
        assertFalse(plain.contains("0 changed files"));
    }

    @Test
    void navigationUsesOnlyApprovedRootAndDoesNotDuplicateActionSlug() throws Exception {
        ObjectNode e = strong();
        EmailRenderer.Content c = render(e);
        assertFalse(c.html().contains("change-investigation/change-investigation"));
        assertFalse(c.html().contains("View Diff"));
        assertEquals(2, c.html().split("href=", -1).length - 1);
        for (String url : new String[] {
            "https://other.example.invalid/job/demo/223/",
            "https://jenkins.example.invalid/job/demo/223/?token=synthetic",
            "javascript:alert(1)",
            "https://jenkins.example.invalid/job/demo/%2e%2e/223/"
        }) {
            ObjectNode altered = e.deepCopy();
            ((ObjectNode) altered.path("build")).put("url", url);
            try {
                EmailRenderer.Content result = render(altered);
                assertFalse(result.html().contains("href="));
                assertTrue(result.plainText().contains("Investigation link unavailable"));
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected);
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailRenderer(URI.create("https://user:pass@example.invalid/")));
    }
}

package io.jenkins.plugins.changeinvestigator.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiResponseParserTest {

    private final AiResponseParser parser = new AiResponseParser(new ObjectMapper(), "test-model");

    @Test
    void parsesWellFormedJson() throws AiAnalysisException {
        String json = """
                {
                  "mostLikelyCause": "Bumped the logging library to an incompatible major version",
                  "confidence": "HIGH",
                  "reasoning": "The only change was the dependency bump and the log shows a NoSuchMethodError from that library.",
                  "supportingEvidence": ["commit abc123 bumps log4j from 1.x to 2.x", "log line: NoSuchMethodError"],
                  "recommendedChecks": ["Revert the dependency bump", "Run tests locally"],
                  "insufficientEvidence": false
                }
                """;

        AiAssessment result = parser.parse(json);

        assertTrue(result.isCompleted());
        assertEquals(Confidence.HIGH, result.getConfidence());
        assertEquals(2, result.getSupportingEvidence().size());
        assertEquals(2, result.getRecommendedChecks().size());
        assertFalse(result.isInsufficientEvidence());
        assertEquals("test-model", result.getModelUsed());
    }

    @Test
    void toleratesMarkdownCodeFences() throws AiAnalysisException {
        String wrapped = "Here is my analysis:\n```json\n"
                + "{\"mostLikelyCause\":\"config change\",\"confidence\":\"low\",\"reasoning\":\"weak signal\","
                + "\"supportingEvidence\":[],\"recommendedChecks\":[],\"insufficientEvidence\":false}"
                + "\n```\nLet me know if you need more.";

        AiAssessment result = parser.parse(wrapped);

        assertTrue(result.isCompleted());
        assertEquals(Confidence.LOW, result.getConfidence());
        assertEquals("config change", result.getMostLikelyCause());
    }

    @Test
    void acceptsInsufficientEvidenceWithoutCause() throws AiAnalysisException {
        String json = "{\"insufficientEvidence\": true, \"reasoning\": \"No changes were recorded.\"}";

        AiAssessment result = parser.parse(json);

        assertTrue(result.isInsufficientEvidence());
    }

    @Test
    void throwsOnCompletelyInvalidJson() {
        assertThrows(AiAnalysisException.class, () -> parser.parse("this is not json at all {{{"));
    }

    @Test
    void throwsOnEmptyResponse() {
        assertThrows(AiAnalysisException.class, () -> parser.parse(""));
        assertThrows(AiAnalysisException.class, () -> parser.parse(null));
    }

    @Test
    void throwsOnJsonArrayInsteadOfObject() {
        assertThrows(AiAnalysisException.class, () -> parser.parse("[1, 2, 3]"));
    }

    @Test
    void throwsWhenNoCauseAndNotFlaggedInsufficient() {
        String json = "{\"reasoning\": \"I have thoughts but no conclusion\"}";
        assertThrows(AiAnalysisException.class, () -> parser.parse(json));
    }

    @Test
    void unknownConfidenceValueBecomesNullRatherThanFailing() throws AiAnalysisException {
        String json = "{\"mostLikelyCause\":\"x\",\"confidence\":\"SUPER_HIGH\",\"reasoning\":\"y\"}";
        AiAssessment result = parser.parse(json);
        assertEquals(null, result.getConfidence());
        assertEquals("UNKNOWN", result.getConfidenceLabel());
    }
}

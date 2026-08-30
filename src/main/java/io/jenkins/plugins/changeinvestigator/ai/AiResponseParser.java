package io.jenkins.plugins.changeinvestigator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the model's chat-completion message content into an {@link AiAssessment}.
 *
 * <p>Models frequently do not follow "JSON only" instructions perfectly - they wrap the JSON
 * in markdown code fences, add a sentence before/after it, or occasionally omit a field. This
 * parser is deliberately tolerant of all of that; a parsing problem must never propagate as an
 * exception that could disrupt a build. Any input this cannot make sense of results in a
 * {@link AiAnalysisException} with kind {@code MALFORMED_RESPONSE}, which the caller turns into
 * a visible "AI unavailable" state rather than a mysterious failure.
 */
public final class AiResponseParser {

    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final String modelUsed;

    public AiResponseParser(ObjectMapper objectMapper, String modelUsed) {
        this.objectMapper = objectMapper;
        this.modelUsed = modelUsed;
    }

    public AiAssessment parse(String rawContent) throws AiAnalysisException {
        if (rawContent == null || rawContent.isBlank()) {
            throw new AiAnalysisException(AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "The AI provider returned an empty response.");
        }

        String jsonCandidate = extractJson(rawContent);
        JsonNode node;
        try {
            node = objectMapper.readTree(jsonCandidate);
        } catch (Exception e) {
            throw new AiAnalysisException(AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "The AI provider's response was not valid JSON and could not be parsed.", e);
        }

        if (!node.isObject()) {
            throw new AiAnalysisException(AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "The AI provider's response was not a JSON object.");
        }

        String mostLikelyCause = textOrNull(node, "mostLikelyCause");
        Confidence confidence = Confidence.parse(textOrNull(node, "confidence"));
        String reasoning = textOrNull(node, "reasoning");
        List<String> supportingEvidence = stringArray(node, "supportingEvidence");
        List<String> recommendedChecks = stringArray(node, "recommendedChecks");
        boolean insufficientEvidence = node.path("insufficientEvidence").asBoolean(false);

        if ((mostLikelyCause == null || mostLikelyCause.isBlank()) && !insufficientEvidence) {
            // A model that gave no cause and did not flag insufficient evidence is not usable;
            // treat it as malformed rather than silently showing an empty assessment as fact.
            throw new AiAnalysisException(AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "The AI provider's response did not include a usable assessment.");
        }

        return AiAssessment.completed(mostLikelyCause, confidence, reasoning, supportingEvidence,
                recommendedChecks, insufficientEvidence, modelUsed);
    }

    private static String extractJson(String rawContent) {
        Matcher fenceMatcher = CODE_FENCE.matcher(rawContent);
        String candidate = fenceMatcher.find() ? fenceMatcher.group(1) : rawContent;
        candidate = candidate.trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return candidate.substring(start, end + 1);
        }
        return candidate;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText(null);
    }

    private static List<String> stringArray(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        List<String> result = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode item : arr) {
                if (!item.isNull()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }
}

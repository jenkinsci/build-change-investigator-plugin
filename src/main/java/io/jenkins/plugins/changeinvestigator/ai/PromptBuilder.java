package io.jenkins.plugins.changeinvestigator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.evidence.ChangeEntry;

/**
 * Builds the prompt sent to the configured OpenAI-compatible endpoint. Only the fields
 * assembled here ever leave Jenkins - this is the single place that decides what data is
 * transmitted to an external AI provider.
 */
public final class PromptBuilder {

    static final String SYSTEM_PROMPT = """
            You are assisting a software engineer in investigating a Jenkins build regression.

            You will be given a JSON "evidence" object containing ONLY facts collected directly \
            from Jenkins: the failed build, the last successful build, source-control changes \
            between them, and a redacted excerpt of the failure log. Some fields may be marked \
            unavailable - Jenkins genuinely does not have that data, it was not omitted from you.

            Rules you MUST follow:
            1. Use ONLY the supplied evidence. Do not invent commits, files, authors, error \
               messages, or facts that are not present in the evidence object.
            2. Identify the single change (or small set of changes) most likely responsible for \
               the regression, and explain why, citing the exact evidence entries that support \
               your reasoning (e.g. a specific commit message, changed file, or log line).
            3. Report a confidence level of exactly "LOW", "MEDIUM", or "HIGH".
            4. Recommend concrete, practical verification steps an engineer could take next.
            5. If the evidence is too sparse to identify a likely cause (e.g. no changes were \
               recorded, or the log excerpt does not indicate a clear failure point), set \
               "insufficientEvidence" to true and say so plainly instead of guessing.
            6. Respond with a single JSON object and nothing else - no markdown fences, no prose \
               before or after it - matching exactly this shape:
            {
              "mostLikelyCause": "string, short description of the change most likely at fault",
              "confidence": "LOW|MEDIUM|HIGH",
              "reasoning": "string, why you believe this, citing specific evidence",
              "supportingEvidence": ["short quotes or references to specific evidence items"],
              "recommendedChecks": ["practical next steps to verify the hypothesis"],
              "insufficientEvidence": false
            }
            """;

    private final ObjectMapper objectMapper;

    public PromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /** Renders the evidence bundle as the compact JSON document sent as the user message. */
    public String userContent(BuildInvestigationEvidence evidence) {
        ObjectNode root = objectMapper.createObjectNode();

        root.put("jobFullName", evidence.getJobFullName());

        ObjectNode failedBuild = root.putObject("failedBuild");
        failedBuild.put("number", evidence.getFailedBuildNumber());
        failedBuild.put("result", evidence.getFailedBuildResult());
        failedBuild.put("durationString", evidence.getFailedBuildDurationString());

        ObjectNode node = root.putObject("agentNode");
        node.put("available", evidence.isNodeInfoAvailable());
        node.put("name", evidence.isNodeInfoAvailable() ? evidence.getNodeName() : "unavailable");

        ObjectNode previousSuccess = root.putObject("previousSuccessfulBuild");
        previousSuccess.put("found", evidence.isPreviousSuccessfulBuildFound());
        if (evidence.isPreviousSuccessfulBuildFound()) {
            previousSuccess.put("number", evidence.getPreviousSuccessfulBuildNumber());
        }

        root.put("lastKnownRevision",
                evidence.hasLastKnownRevision() ? evidence.getLastKnownRevision() : "unavailable");

        ObjectNode changes = root.putObject("changes");
        changes.put("dataAvailable", evidence.isChangeDataAvailable());
        ArrayNode entriesNode = changes.putArray("entries");
        for (ChangeEntry entry : evidence.getChangeEntries()) {
            ObjectNode entryNode = entriesNode.addObject();
            entryNode.put("fromBuildNumber", entry.getFromBuildNumber());
            entryNode.put("commitId", entry.hasCommitId() ? entry.getCommitId() : "unavailable");
            entryNode.put("author", entry.getAuthor());
            entryNode.put("message", entry.getMessage());
            ArrayNode filesNode = entryNode.putArray("affectedFiles");
            entry.getAffectedFiles().forEach(filesNode::add);
        }

        ObjectNode log = root.putObject("failureLogExcerpt");
        log.put("available", evidence.isLogAvailable());
        log.put("truncated", evidence.isLogExcerptTruncated());
        ArrayNode logLines = log.putArray("lines");
        evidence.getLogExcerpt().forEach(logLines::add);

        ArrayNode warnings = root.putArray("evidenceWarnings");
        evidence.getWarnings().forEach(warnings::add);

        return root.toPrettyString();
    }
}

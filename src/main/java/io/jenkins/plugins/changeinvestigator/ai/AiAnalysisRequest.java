package io.jenkins.plugins.changeinvestigator.ai;

/**
 * Provider-agnostic analysis request: a system prompt, the evidence rendered as the user
 * message, and the sampling temperature. Every {@link AiProvider} adapter translates this into
 * its own wire format - nothing upstream of this record needs to know what that format is.
 */
public record AiAnalysisRequest(String systemPrompt, String userContent, double temperature) {}

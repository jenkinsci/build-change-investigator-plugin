package io.jenkins.plugins.changeinvestigator.ai;

/**
 * Provider-agnostic analysis result: the raw assistant text (not yet parsed as the
 * evidence-assessment JSON - see {@link AiResponseParser}), plus which provider and model
 * actually produced it, for display alongside the assessment.
 */
public record AiAnalysisResult(String responseText, String providerDisplayName, String model) {}

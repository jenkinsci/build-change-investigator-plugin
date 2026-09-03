package io.jenkins.plugins.changeinvestigator.ai;

/**
 * One outbound call to one AI provider's chat/completion API. Implementations own everything
 * provider-specific - endpoint shape, auth headers, request/response JSON - and translate to and
 * from the normalized {@link AiAnalysisRequest}/{@link AiAnalysisResult} pair so the rest of the
 * plugin never needs to know which provider is configured.
 *
 * <p>Implementations must never retain the resolved credential as object state beyond the
 * constructor call that builds the outbound request within {@link #chatCompletion}; see the
 * individual implementations for how each one avoids that.
 */
public interface AiProvider {

    AiAnalysisResult chatCompletion(AiAnalysisRequest request) throws AiAnalysisException;
}

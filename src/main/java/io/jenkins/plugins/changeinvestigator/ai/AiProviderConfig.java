package io.jenkins.plugins.changeinvestigator.ai;

/**
 * Plain, Jenkins-independent snapshot of the <b>non-secret</b> settings needed to call an
 * OpenAI-compatible chat-completions endpoint. Kept separate from the Jenkins
 * {@code GlobalConfiguration} descriptor so the AI call/parse pipeline can be unit tested
 * without a running Jenkins instance.
 *
 * <p>Deliberately does <b>not</b> hold the API token. This is a {@code record}, and records
 * generate {@code toString()}/{@code equals()}/{@code hashCode()} automatically from every
 * field - a secret-bearing field here would be trivially leaked the first time this object was
 * logged, compared, or included in a diagnostic message, with no override to catch it. The
 * resolved token is instead passed as its own narrowly-scoped parameter directly to
 * {@link OpenAiCompatibleClient}, which is a plain (non-record) class that never auto-generates
 * those methods, and only ever converts it to plaintext at the point of building the outbound
 * {@code Authorization} header.
 *
 * @param baseUrl        e.g. {@code https://api.openai.com/v1} - "/chat/completions" is appended by the client
 * @param model          model name to request
 * @param timeoutSeconds connect + request timeout
 * @param temperature    sampling temperature; low by default to favor consistent analysis
 */
public record AiProviderConfig(
        String baseUrl, String model, int timeoutSeconds, double temperature, int maxLogContextChars) {}

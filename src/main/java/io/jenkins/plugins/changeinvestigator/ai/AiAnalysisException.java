package io.jenkins.plugins.changeinvestigator.ai;

/** Represents any way AI analysis can fail to produce a usable result. Never propagates to build logic. */
public final class AiAnalysisException extends Exception {

    public enum Kind {
        DISABLED,
        CREDENTIALS_MISSING,
        CONFIGURATION_INVALID,
        TIMEOUT,
        CONNECTION_FAILED,
        /** Generic non-2xx HTTP response not covered by a more specific kind below (e.g. HTTP 400). */
        HTTP_ERROR,
        MALFORMED_RESPONSE,

        // --- Finer-grained kinds layered on top of the original set above, so existing callers
        // that only handle the original 7 still compile and behave the same; provider code is
        // encouraged to use the most specific kind it can determine. ---

        /** The provider rejected the credential itself (HTTP 401 or equivalent). */
        AUTHENTICATION_FAILED,
        /** The credential was accepted but lacks permission for the requested operation (HTTP 403 or equivalent). */
        AUTHORIZATION_FAILED,
        /** The configured model/inference target does not exist or is not available to this account. */
        MODEL_NOT_FOUND,
        /** The configured Azure deployment does not exist. */
        DEPLOYMENT_NOT_FOUND,
        /** The configured region is invalid or not recognized by the provider/SDK. */
        INVALID_REGION,
        /** The configured endpoint URL is malformed or does not point at a usable provider endpoint. */
        INVALID_ENDPOINT,
        /** AWS STS AssumeRole failed (trust policy, permissions, or a nonexistent role). */
        ASSUME_ROLE_FAILED,
        /** The provider signaled a request-rate limit (as opposed to an account/billing quota - see {@link #QUOTA_EXCEEDED}). */
        RATE_LIMITED,
        /** The provider rejected the request because the account/project has insufficient quota or credits. */
        QUOTA_EXCEEDED,
        /** The provider is temporarily unavailable (HTTP 5xx / service outage). */
        PROVIDER_UNAVAILABLE,
        /** The response was well-formed but semantically unusable (e.g. content blocked, unsupported content type). */
        UNSUPPORTED_RESPONSE,
        /**
         * A provider integration failed in a way that could not be classified more specifically -
         * including a provider SDK/library failing to load or link correctly at runtime. Always
         * paired with a message that points the administrator at the server log rather than
         * exposing internal details directly.
         */
        UNKNOWN_PROVIDER_ERROR
    }

    private final Kind kind;

    public AiAnalysisException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiAnalysisException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}

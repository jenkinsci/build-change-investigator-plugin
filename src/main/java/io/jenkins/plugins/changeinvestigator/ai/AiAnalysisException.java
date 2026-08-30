package io.jenkins.plugins.changeinvestigator.ai;

/** Represents any way AI analysis can fail to produce a usable result. Never propagates to build logic. */
public final class AiAnalysisException extends Exception {

    public enum Kind {
        DISABLED,
        CREDENTIALS_MISSING,
        CONFIGURATION_INVALID,
        TIMEOUT,
        CONNECTION_FAILED,
        HTTP_ERROR,
        MALFORMED_RESPONSE
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

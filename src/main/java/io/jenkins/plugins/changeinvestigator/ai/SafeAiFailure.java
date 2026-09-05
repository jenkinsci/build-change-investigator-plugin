package io.jenkins.plugins.changeinvestigator.ai;

/** Public diagnostics contain only category guidance, never provider payloads or exception messages. */
public final class SafeAiFailure {
    private SafeAiFailure() {}

    public static String describe(AiAnalysisException.Kind kind) {
        if (kind == null) {
            return describe(AiAnalysisException.Kind.UNKNOWN_PROVIDER_ERROR);
        }
        return switch (kind) {
            case DISABLED -> "AI analysis is disabled.";
            case CREDENTIALS_MISSING ->
                "AI credentials are unavailable. Select a valid Jenkins credential or configure the provider's supported credential chain.";
            case CONFIGURATION_INVALID -> "Check the AI provider configuration and required model settings.";
            case INVALID_REGION -> "Check the configured AWS region.";
            case INVALID_ENDPOINT -> "Check the provider endpoint: use a valid HTTP or HTTPS URL.";
            case MODEL_NOT_FOUND -> "Check that the configured model exists and is available to this account.";
            case DEPLOYMENT_NOT_FOUND -> "Check the Azure deployment name and endpoint.";
            case AUTHENTICATION_FAILED ->
                "The provider rejected the credential. Check or replace the Jenkins credential.";
            case AUTHORIZATION_FAILED -> "The provider denied access. Check account and model permissions.";
            case ASSUME_ROLE_FAILED -> "Check the AWS role trust policy and permission to assume the configured role.";
            case TIMEOUT -> "The provider did not respond in time. Retry or review the configured timeout.";
            case CONNECTION_FAILED ->
                "Could not reach the provider. Check the endpoint, proxy, TLS and network access.";
            case RATE_LIMITED -> "The provider request rate limit was reached. Wait before retrying.";
            case QUOTA_EXCEEDED -> "The provider account has insufficient quota or credits. Review account limits.";
            case PROVIDER_UNAVAILABLE -> "The provider is temporarily unavailable. Retry later.";
            case HTTP_ERROR -> "The provider rejected the request. Review the provider configuration.";
            case MALFORMED_RESPONSE -> "The provider returned an invalid response. Check API compatibility or retry.";
            case UNSUPPORTED_RESPONSE ->
                "The provider returned no usable interpretation, possibly due to content filtering. Review provider policies.";
            case UNKNOWN_PROVIDER_ERROR ->
                "An internal provider error occurred. Ask an administrator to check plugin compatibility and the Jenkins log.";
        };
    }
}

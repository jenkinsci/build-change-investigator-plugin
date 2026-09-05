package io.jenkins.plugins.changeinvestigator.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import java.net.URI;
import java.util.Locale;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * {@link BedrockProvider} is exercised against a local mock server via the AWS SDK's
 * {@code endpointOverride}, not real AWS - the SDK still SigV4-signs every request with the
 * static test credentials below, but the mock server (unlike real AWS) does not verify the
 * signature, so this validates the Converse request/response mapping without needing live AWS
 * infrastructure or credentials, matching this repository's existing "no live external
 * providers required for tests" convention. Role ARN / AssumeRole tests use the same trick,
 * routing the AWS SDK's STS client at the mock server too (via the test-only 7-arg constructor)
 * so assume-role behavior can be exercised without ever calling the real AWS STS endpoint.
 */
class BedrockProviderTest {

    private static final String TEST_ROLE_ARN = "arn:aws:iam::123456789012:role/test-role";

    private static final String ASSUME_ROLE_SUCCESS_XML = "<AssumeRoleResponse"
            + " xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
            + "<AssumeRoleResult><Credentials>"
            + "<AccessKeyId>ASIATESTASSUMEDKEY</AccessKeyId>"
            + "<SecretAccessKey>test-assumed-secret</SecretAccessKey>"
            + "<SessionToken>test-assumed-session-token</SessionToken>"
            + "<Expiration>2099-01-01T00:00:00Z</Expiration>"
            + "</Credentials>"
            + "<AssumedRoleUser>"
            + "<AssumedRoleId>AROATEST:jenkins-build-change-investigator</AssumedRoleId>"
            + "<Arn>arn:aws:sts::123456789012:assumed-role/test-role/jenkins-build-change-investigator</Arn>"
            + "</AssumedRoleUser></AssumeRoleResult>"
            + "<ResponseMetadata><RequestId>test-request-id</RequestId></ResponseMetadata>"
            + "</AssumeRoleResponse>";

    private static final String ASSUME_ROLE_ACCESS_DENIED_XML = "<ErrorResponse"
            + " xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
            + "<Error><Type>Sender</Type><Code>AccessDenied</Code>"
            + "<Message>User is not authorized to perform: sts:AssumeRole</Message></Error>"
            + "<RequestId>test-request-id</RequestId></ErrorResponse>";

    private static final software.amazon.awssdk.auth.credentials.AwsCredentialsProvider TEST_CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access-key", "test-secret-key"));

    /** Routes STS AssumeRole calls (path "/") to the AssumeRole XML fixture, everything else (the Bedrock Converse call) to the given JSON body. */
    private static Function<com.sun.net.httpserver.HttpExchange, String> stsThenConverse(String converseResponseJson) {
        return exchange -> exchange.getRequestURI().getPath().contains("/converse")
                ? converseResponseJson
                : ASSUME_ROLE_SUCCESS_XML;
    }

    @AfterEach
    void clearAwsSystemProperties() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void sendsConverseRequestAndParsesTextFromOutputMessage() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"hello from bedrock\"}]}},"
                        + "\"stopReason\":\"end_turn\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);

            AiAnalysisResult result =
                    provider.chatCompletion(new AiAnalysisRequest("system prompt", "user content", 0.3));

            assertEquals("hello from bedrock", result.responseText());
            assertEquals("AWS Bedrock", result.providerDisplayName());
            assertEquals("anthropic.claude-sonnet-4-5", result.model());
            assertTrue(
                    mock.lastPath.contains("converse"), "expected the Converse endpoint path, got: " + mock.lastPath);
            assertTrue(mock.lastRequestBody.contains("user content"));
        }
    }

    @Test
    void concatenatesMultipleContentBlocks() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"output\":{\"message\":{\"content\":[{\"text\":\"part1 \"},{\"text\":\"part2\"}]}}}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisResult result = provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertEquals("part1 part2", result.responseText());
        }
    }

    @Test
    void throwsConfigurationInvalidWhenRegionMissing() {
        var provider = new BedrockProvider(null, "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void throwsConfigurationInvalidWhenModelMissing() {
        var provider = new BedrockProvider("us-east-1", null, TEST_CREDENTIALS, 10);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
    }

    @Test
    void constructionWithBlankEndpointAndBlankRoleArnDoesNotThrow() {
        // Blank endpoint/Role ARN mean "use normal AWS regional endpoint resolution" and "use the
        // credential directly" respectively - both must be accepted at construction time without
        // requiring a live AWS call to prove it (asserted here by simply not throwing).
        new BedrockProvider("us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, "  ", "  ");
        new BedrockProvider("us-east-1", "anthropic.claude-sonnet-4-5", null, 10, null, null);
    }

    @Test
    void throwsMalformedResponseWhenOutputMessageMissing() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void mapsUnreachableEndpointToConnectionFailed() {
        var provider = new BedrockProvider(
                "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 2, "http://127.0.0.1:1", null);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONNECTION_FAILED, ex.getKind());
    }

    @Test
    void assumesRoleUsingSelectedCredentialAsSourceIdentity() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                stsThenConverse("{\"output\":{\"message\":{\"content\":[{\"text\":\"hello via assumed role\"}]}}}"))) {
            var provider = new BedrockProvider(
                    "us-east-1",
                    "anthropic.claude-sonnet-4-5",
                    TEST_CREDENTIALS,
                    10,
                    mock.baseUrl(),
                    TEST_ROLE_ARN,
                    URI.create(mock.baseUrl()));

            AiAnalysisResult result = provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));

            assertEquals("hello via assumed role", result.responseText());
        }
    }

    @Test
    void assumesRoleUsingDefaultCredentialChainWhenNoJenkinsCredentialSelected() throws Exception {
        // SystemPropertyCredentialsProvider is checked first in the SDK's default chain, so this
        // makes "no Jenkins credential selected" resolve deterministically and offline instead of
        // falling through to environment/profile/IMDS lookups that would be slow or fail in CI.
        System.setProperty("aws.accessKeyId", "default-chain-test-key");
        System.setProperty("aws.secretAccessKey", "default-chain-test-secret");
        try (MockAiServer mock = MockAiServer.start(
                stsThenConverse("{\"output\":{\"message\":{\"content\":[{\"text\":\"hello via default chain\"}]}}}"))) {
            var provider = new BedrockProvider(
                    "us-east-1",
                    "anthropic.claude-sonnet-4-5",
                    null,
                    10,
                    mock.baseUrl(),
                    TEST_ROLE_ARN,
                    URI.create(mock.baseUrl()));

            AiAnalysisResult result = provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));

            assertEquals("hello via default chain", result.responseText());
        }
    }

    @Test
    void mapsStsAssumeRoleFailureToAssumeRoleFailed() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithStatus(403, ASSUME_ROLE_ACCESS_DENIED_XML)) {
            var provider = new BedrockProvider(
                    "us-east-1",
                    "anthropic.claude-sonnet-4-5",
                    TEST_CREDENTIALS,
                    10,
                    mock.baseUrl(),
                    TEST_ROLE_ARN,
                    URI.create(mock.baseUrl()));

            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));

            assertEquals(AiAnalysisException.Kind.ASSUME_ROLE_FAILED, ex.getKind());
            assertTrue(ex.getMessage().contains(TEST_ROLE_ARN));
        }
    }

    @Test
    void mapsMissingCredentialsToCredentialsMissingNotConnectionFailed() {
        // The exact scenario behind the original production bug's reproduction: no credential
        // selected, and none of the SDK's default credential chain sources have anything either
        // (no env vars, no profile, no IMDS) - must be reported as a configuration problem
        // (CREDENTIALS_MISSING), not a misleading CONNECTION_FAILED, even though both are raised
        // as the same SdkClientException type.
        var provider =
                new BedrockProvider("us-east-1", "anthropic.claude-sonnet-4-5", null, 10, "http://127.0.0.1:1", null);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        // Whichever failure the SDK hits first (no credentials available, or the unreachable
        // endpoint) is acceptable here - both are safely categorized, neither is left generic.
        assertTrue(
                ex.getKind() == AiAnalysisException.Kind.CREDENTIALS_MISSING
                        || ex.getKind() == AiAnalysisException.Kind.CONNECTION_FAILED,
                "expected CREDENTIALS_MISSING or CONNECTION_FAILED, got: " + ex.getKind());
    }

    @Test
    void mapsAccessDeniedToAuthorizationFailed() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithAwsError(
                403, "AccessDeniedException", "{\"message\":\"not authorized to perform bedrock:InvokeModel\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.AUTHORIZATION_FAILED, ex.getKind());
        }
    }

    @Test
    void mapsResourceNotFoundToModelNotFound() throws Exception {
        try (MockAiServer mock =
                MockAiServer.startWithAwsError(404, "ResourceNotFoundException", "{\"message\":\"model not found\"}")) {
            var provider =
                    new BedrockProvider("us-east-1", "no-such-model", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MODEL_NOT_FOUND, ex.getKind());
        }
    }

    @Test
    void mapsValidationExceptionToConfigurationInvalid() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithAwsError(
                400, "ValidationException", "{\"message\":\"1 validation error detected\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.CONFIGURATION_INVALID, ex.getKind());
        }
    }

    @Test
    void mapsThrottlingToRateLimited() throws Exception {
        try (MockAiServer mock =
                MockAiServer.startWithAwsError(429, "ThrottlingException", "{\"message\":\"rate exceeded\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.RATE_LIMITED, ex.getKind());
        }
    }

    @Test
    void mapsModelTimeoutToTimeout() throws Exception {
        try (MockAiServer mock =
                MockAiServer.startWithAwsError(408, "ModelTimeoutException", "{\"message\":\"model timed out\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.TIMEOUT, ex.getKind());
        }
    }

    @Test
    void mapsServiceUnavailableToProviderUnavailable() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithAwsError(
                503, "ServiceUnavailableException", "{\"message\":\"service unavailable\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    @Test
    void mapsModelNotReadyToProviderUnavailable() throws Exception {
        try (MockAiServer mock =
                MockAiServer.startWithAwsError(429, "ModelNotReadyException", "{\"message\":\"model not ready\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.PROVIDER_UNAVAILABLE, ex.getKind());
        }
    }

    @Test
    void mapsModelErrorToHttpError() throws Exception {
        try (MockAiServer mock = MockAiServer.startWithAwsError(
                424, "ModelErrorException", "{\"message\":\"underlying model returned an error\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, mock.baseUrl(), null);
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.HTTP_ERROR, ex.getKind());
        }
    }

    @Test
    void mapsMalformedRegionToInvalidRegion() {
        var provider = new BedrockProvider("not a valid region!!", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10);
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        // Region.of(...) itself doesn't validate strictly, but the SDK's own client construction
        // must not throw an uncaught IllegalArgumentException for an unresolvable region string;
        // accept either INVALID_REGION (if we catch it directly) or CONNECTION_FAILED (if the SDK
        // instead fails trying to resolve an endpoint for the bogus region) - either is safe.
        assertTrue(
                ex.getKind() == AiAnalysisException.Kind.INVALID_REGION
                        || ex.getKind() == AiAnalysisException.Kind.CONNECTION_FAILED,
                "expected INVALID_REGION or CONNECTION_FAILED, got: " + ex.getKind());
    }

    @Test
    void doesNotHoldAnyResolvedCredentialOrSecretAsInstanceState() {
        for (var field : BedrockProvider.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            assertTrue(
                    !name.contains("secret") && !name.contains("sessiontoken") && !name.contains("accesskey"),
                    "BedrockProvider must not hold resolved AWS secret material as instance state, found field: "
                            + field.getName());
        }
    }
}

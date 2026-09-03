package io.jenkins.plugins.changeinvestigator.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * {@link BedrockProvider} is exercised against a local mock server via the AWS SDK's
 * {@code endpointOverride}, not real AWS - the SDK still SigV4-signs every request with the
 * static test credentials below, but the mock server (unlike real AWS) does not verify the
 * signature, so this validates the Converse request/response mapping without needing live AWS
 * infrastructure or credentials, matching this repository's existing "no live external
 * providers required for tests" convention.
 */
class BedrockProviderTest {

    private static final software.amazon.awssdk.auth.credentials.AwsCredentialsProvider TEST_CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access-key", "test-secret-key"));

    @Test
    void sendsConverseRequestAndParsesTextFromOutputMessage() throws Exception {
        try (MockAiServer mock = MockAiServer.start(
                "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"hello from bedrock\"}]}},"
                        + "\"stopReason\":\"end_turn\"}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, URI.create(mock.baseUrl()));

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
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, URI.create(mock.baseUrl()));
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
    void throwsMalformedResponseWhenOutputMessageMissing() throws Exception {
        try (MockAiServer mock = MockAiServer.start("{}")) {
            var provider = new BedrockProvider(
                    "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 10, URI.create(mock.baseUrl()));
            AiAnalysisException ex = assertThrows(
                    AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(AiAnalysisException.Kind.MALFORMED_RESPONSE, ex.getKind());
        }
    }

    @Test
    void mapsUnreachableEndpointToConnectionFailed() {
        var provider = new BedrockProvider(
                "us-east-1", "anthropic.claude-sonnet-4-5", TEST_CREDENTIALS, 2, URI.create("http://127.0.0.1:1"));
        AiAnalysisException ex = assertThrows(
                AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
        assertEquals(AiAnalysisException.Kind.CONNECTION_FAILED, ex.getKind());
    }
}

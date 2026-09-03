package io.jenkins.plugins.changeinvestigator.ai.provider;

import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

/**
 * AWS Bedrock via the Runtime {@code Converse} API - a single normalized request/response shape
 * that works across every model family Bedrock hosts (Anthropic, Amazon, Meta, ...), so this
 * adapter does not need model-family-specific request building the way a raw
 * {@code InvokeModel} call would.
 *
 * <p>Credentials: if an {@code AwsCredentialsProvider} is supplied (backed by an optional
 * Jenkins {@code AmazonWebServicesCredentials} credential - see {@link BedrockProviderConfig}),
 * it is used directly. Otherwise this falls back to the AWS SDK's standard
 * {@link DefaultCredentialsProvider} chain (environment variables, the shared credentials file,
 * or - the common case on EC2/ECS Jenkins infrastructure - an IAM role attached to the
 * controller or agent). Nothing here stores a long-lived AWS client or credential as instance
 * state beyond the single call in {@link #chatCompletion}.
 */
final class BedrockProvider implements AiProvider {

    private final String region;
    private final String modelId;
    private final AwsCredentialsProvider credentialsProvider;
    private final int timeoutSeconds;
    private final URI endpointOverride;

    /** @param credentialsProvider resolved AWS credentials, or {@code null} to use the SDK's default credential chain. */
    BedrockProvider(String region, String modelId, AwsCredentialsProvider credentialsProvider, int timeoutSeconds) {
        this(region, modelId, credentialsProvider, timeoutSeconds, null);
    }

    /**
     * Test-only overload: overrides the Bedrock endpoint so unit tests can point at a local
     * mock server instead of real AWS infrastructure, without adding an endpoint-override field
     * to the admin-facing {@link BedrockProviderConfig} (Bedrock's endpoint is determined by
     * {@code region}, not independently configurable, in normal use).
     */
    BedrockProvider(
            String region,
            String modelId,
            AwsCredentialsProvider credentialsProvider,
            int timeoutSeconds,
            URI endpointOverride) {
        this.region = region;
        this.modelId = modelId;
        this.credentialsProvider = credentialsProvider;
        this.timeoutSeconds = timeoutSeconds;
        this.endpointOverride = endpointOverride;
    }

    @Override
    public AiAnalysisResult chatCompletion(AiAnalysisRequest request) throws AiAnalysisException {
        if (region == null || region.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID, "No AWS region is configured for Bedrock.");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID,
                    "No model ID / inference profile is configured for Bedrock.");
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        var clientBuilder = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        credentialsProvider != null ? credentialsProvider : DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(timeout)
                        .socketTimeout(timeout));
        if (endpointOverride != null) {
            clientBuilder = clientBuilder.endpointOverride(endpointOverride);
        }
        try (BedrockRuntimeClient client = clientBuilder.build()) {

            ConverseRequest converseRequest = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.builder()
                            .text(request.systemPrompt())
                            .build())
                    .messages(Message.builder()
                            .role("user")
                            .content(ContentBlock.builder()
                                    .text(request.userContent())
                                    .build())
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                            .temperature((float) request.temperature())
                            .build())
                    .build();

            ConverseResponse response = client.converse(converseRequest);
            return new AiAnalysisResult(extractText(response), "AWS Bedrock", modelId);
        } catch (ThrottlingException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.HTTP_ERROR, "AWS Bedrock throttled the request: " + e.getMessage(), e);
        } catch (BedrockRuntimeException e) {
            AiAnalysisException.Kind kind = e.statusCode() == 401 || e.statusCode() == 403
                    ? AiAnalysisException.Kind.CREDENTIALS_MISSING
                    : AiAnalysisException.Kind.HTTP_ERROR;
            throw new AiAnalysisException(
                    kind, "AWS Bedrock returned an error (HTTP " + e.statusCode() + "): " + e.getMessage(), e);
        } catch (SdkClientException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONNECTION_FAILED, "Could not call AWS Bedrock: " + e.getMessage(), e);
        }
    }

    private static String extractText(ConverseResponse response) throws AiAnalysisException {
        if (response.output() == null || response.output().message() == null) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE, "AWS Bedrock's response contained no output message.");
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.output().message().content()) {
            if (block.text() != null) {
                text.append(block.text());
            }
        }
        if (text.isEmpty()) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MALFORMED_RESPONSE,
                    "AWS Bedrock's response contained no text content block.");
        }
        return text.toString();
    }
}

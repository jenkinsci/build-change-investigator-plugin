package io.jenkins.plugins.changeinvestigator.ai.provider;

import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelErrorException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.StsException;

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
 * controller or agent). If a Role ARN is also supplied, that source (the selected credential, or
 * the default chain) is used only as the <em>identity</em> that calls AWS STS
 * {@code AssumeRole}; the actual Bedrock calls then use the resulting temporary,
 * automatically-refreshed session credentials, obtained entirely through
 * {@link StsAssumeRoleCredentialsProvider} - never hand-signed. Nothing here stores a resolved
 * credential, session token, or long-lived client as instance state beyond the single call in
 * {@link #chatCompletion}.
 */
final class BedrockProvider implements AiProvider {

    private static final Logger LOGGER = Logger.getLogger(BedrockProvider.class.getName());

    private final String region;
    private final String modelId;
    private final AwsCredentialsProvider credentialsProvider;
    private final int timeoutSeconds;
    private final URI endpointOverride;
    private final String roleArn;
    private final URI stsEndpointOverride;

    /**
     * @param credentialsProvider resolved AWS credentials, or {@code null} to use the SDK's default credential chain.
     * @param endpointUrl optional custom Bedrock endpoint (VPC endpoint, private routing, or a
     *     test double); {@code null}/blank uses normal AWS regional endpoint resolution.
     * @param roleArn optional IAM role to assume via AWS STS before calling Bedrock;
     *     {@code null}/blank uses {@code credentialsProvider} (or the default chain) directly.
     */
    BedrockProvider(
            String region,
            String modelId,
            AwsCredentialsProvider credentialsProvider,
            int timeoutSeconds,
            String endpointUrl,
            String roleArn) {
        this(region, modelId, credentialsProvider, timeoutSeconds, endpointUrl, roleArn, null);
    }

    /**
     * Test-only overload that also lets the AWS STS client used for {@code AssumeRole} be pointed
     * at a local mock server instead of the real AWS STS endpoint, so Role ARN behavior can be
     * exercised without live AWS infrastructure or credentials. Never invoked from production
     * code - {@link BedrockProviderConfig} always uses the 6-arg constructor above, which leaves
     * this {@code null} and gets normal AWS regional STS endpoint resolution.
     */
    BedrockProvider(
            String region,
            String modelId,
            AwsCredentialsProvider credentialsProvider,
            int timeoutSeconds,
            String endpointUrl,
            String roleArn,
            URI stsEndpointOverride) {
        this.region = region;
        this.modelId = modelId;
        this.credentialsProvider = credentialsProvider;
        this.timeoutSeconds = timeoutSeconds;
        this.endpointOverride = (endpointUrl == null || endpointUrl.isBlank()) ? null : URI.create(endpointUrl);
        this.roleArn = roleArn;
        this.stsEndpointOverride = stsEndpointOverride;
    }

    /** Convenience overload for callers with no endpoint override or Role ARN (the common case). */
    BedrockProvider(String region, String modelId, AwsCredentialsProvider credentialsProvider, int timeoutSeconds) {
        this(region, modelId, credentialsProvider, timeoutSeconds, null, null, null);
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
        AwsCredentialsProvider sourceCredentials =
                credentialsProvider != null ? credentialsProvider : DefaultCredentialsProvider.create();

        StsClient stsClient = null;
        StsAssumeRoleCredentialsProvider assumedRoleCredentials = null;
        try {
            AwsCredentialsProvider effectiveCredentials = sourceCredentials;
            if (roleArn != null && !roleArn.isBlank()) {
                var stsClientBuilder = StsClient.builder()
                        .region(Region.of(region))
                        .credentialsProvider(sourceCredentials)
                        .httpClientBuilder(UrlConnectionHttpClient.builder()
                                .connectionTimeout(timeout)
                                .socketTimeout(timeout));
                if (stsEndpointOverride != null) {
                    stsClientBuilder = stsClientBuilder.endpointOverride(stsEndpointOverride);
                }
                stsClient = stsClientBuilder.build();
                assumedRoleCredentials = StsAssumeRoleCredentialsProvider.builder()
                        .stsClient(stsClient)
                        .refreshRequest(AssumeRoleRequest.builder()
                                .roleArn(roleArn)
                                .roleSessionName("jenkins-build-change-investigator")
                                .build())
                        .build();
                effectiveCredentials = assumedRoleCredentials;
            }

            var clientBuilder = BedrockRuntimeClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(effectiveCredentials)
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
            }
        } catch (StsException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.ASSUME_ROLE_FAILED,
                    "Could not assume AWS role " + roleArn + ": "
                            + e.awsErrorDetails().errorMessage(),
                    e);
        } catch (AccessDeniedException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.AUTHORIZATION_FAILED,
                    "AWS Bedrock denied access to model/inference profile '" + modelId
                            + "' (check the bedrock:InvokeModel IAM permission): " + e.getMessage(),
                    e);
        } catch (ResourceNotFoundException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.MODEL_NOT_FOUND,
                    "AWS Bedrock model/inference profile '" + modelId + "' was not found in region " + region + ": "
                            + e.getMessage(),
                    e);
        } catch (ValidationException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONFIGURATION_INVALID,
                    "AWS Bedrock rejected the request configuration: " + e.getMessage(),
                    e);
        } catch (ModelTimeoutException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.TIMEOUT, "AWS Bedrock model timed out: " + e.getMessage(), e);
        } catch (ModelNotReadyException | ServiceUnavailableException | InternalServerException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.PROVIDER_UNAVAILABLE,
                    "AWS Bedrock is temporarily unavailable: " + e.getMessage(),
                    e);
        } catch (ThrottlingException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.RATE_LIMITED, "AWS Bedrock throttled the request: " + e.getMessage(), e);
        } catch (ModelErrorException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.HTTP_ERROR,
                    "AWS Bedrock's underlying model returned an error: " + e.getMessage(),
                    e);
        } catch (BedrockRuntimeException e) {
            // Fallback for any Bedrock service exception not covered by a more specific kind
            // above - still safely categorized by status code rather than left generic.
            AiAnalysisException.Kind kind =
                    switch (e.statusCode()) {
                        case 401 -> AiAnalysisException.Kind.AUTHENTICATION_FAILED;
                        case 403 -> AiAnalysisException.Kind.AUTHORIZATION_FAILED;
                        case 404 -> AiAnalysisException.Kind.MODEL_NOT_FOUND;
                        case 429 -> AiAnalysisException.Kind.RATE_LIMITED;
                        default ->
                            e.statusCode() >= 500
                                    ? AiAnalysisException.Kind.PROVIDER_UNAVAILABLE
                                    : AiAnalysisException.Kind.HTTP_ERROR;
                    };
            throw new AiAnalysisException(
                    kind, "AWS Bedrock returned an error (HTTP " + e.statusCode() + "): " + e.getMessage(), e);
        } catch (SdkClientException e) {
            throw new AiAnalysisException(
                    classifySdkClientException(e), "Could not call AWS Bedrock: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            // Region.of(...) and the SDK's own builder validation throw this for malformed input
            // (e.g. an unparsable region string) rather than a checked exception.
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.INVALID_REGION,
                    "AWS Bedrock configuration was rejected: " + e.getMessage(),
                    e);
        } catch (LinkageError e) {
            // Belt-and-braces: AiProviderConfig.testConnection and AiAnalysisService.analyze
            // already carry an outermost LinkageError safety net so nothing from any provider
            // can ever reach Jenkins uncaught, but catching it here too means a Bedrock-specific
            // SDK version/classloading mismatch (see aws-sdk.version in pom.xml) gets a properly
            // categorized, user-facing message instead of the generic outer fallback text.
            LOGGER.log(
                    Level.WARNING,
                    "AWS Bedrock SDK failed to load/link correctly ({0})",
                    e.getClass().getName());
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.UNKNOWN_PROVIDER_ERROR,
                    "AWS Bedrock's SDK failed to load correctly ("
                            + e.getClass().getSimpleName() + "). See the Jenkins log for details.",
                    e);
        } finally {
            // Closing the assume-role provider stops its background credential-refresh thread;
            // it holds only an in-memory Credentials object obtained from STS for its own
            // lifetime (never persisted, logged, or exposed beyond this method) - see the class
            // javadoc for the full guarantee.
            if (assumedRoleCredentials != null) {
                assumedRoleCredentials.close();
            }
            if (stsClient != null) {
                stsClient.close();
            }
        }
    }

    /**
     * {@link SdkClientException} covers both "no AWS credentials were found at all" (thrown
     * lazily by the credential-provider chain when the SDK first tries to sign a request) and
     * genuine network-level failures (DNS, connection refused, TLS) - two very different,
     * equally common misconfigurations that deserve different {@link AiAnalysisException.Kind}s
     * rather than being flattened into one generic message.
     */
    private static AiAnalysisException.Kind classifySdkClientException(SdkClientException e) {
        String message = e.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("unable to load credentials") || lower.contains("no aws credentials")) {
                return AiAnalysisException.Kind.CREDENTIALS_MISSING;
            }
        }
        return AiAnalysisException.Kind.CONNECTION_FAILED;
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

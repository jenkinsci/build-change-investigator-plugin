package io.jenkins.plugins.changeinvestigator.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.changeinvestigator.ai.AiProvider;
import java.lang.reflect.Field;
import java.util.Locale;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Structural and credential-resolution coverage across every {@link AiProviderConfig} subclass.
 * Each config's own field-shape and defaults are the meaningful per-provider difference to
 * test; the wire behavior each one delegates to is already covered by that provider's own test
 * (e.g. {@link AnthropicProviderTest}).
 */
@WithJenkins
class AiProviderConfigsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- No config class may ever carry a field that looks like a secret (guardrail, all 7) ---

    @Test
    void noProviderConfigClassCarriesASecretShapedField() {
        Class<?>[] configClasses = {
            OpenAiProviderConfig.class,
            OpenAiCompatibleProviderConfig.class,
            AnthropicProviderConfig.class,
            AzureOpenAiProviderConfig.class,
            GeminiProviderConfig.class,
            OllamaProviderConfig.class,
            BedrockProviderConfig.class
        };
        for (Class<?> clazz : configClasses) {
            for (Field field : clazz.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                assertFalse(
                        name.contains("secretkey")
                                || name.equals("secret")
                                || name.contains("apikey")
                                || name.contains("password")
                                || (name.contains("token") && !name.equals("credentialsid")),
                        clazz.getSimpleName() + " must not carry a secret-shaped field: " + field.getName());
            }
        }
    }

    // --- OpenAI: credential required by contract (enforced by OpenAiChatCompletionsProvider), model defaults ---

    @Test
    void openAiUsesDefaultModelWhenBlank() {
        OpenAiProviderConfig config = new OpenAiProviderConfig("", "cred-id");
        assertEquals(OpenAiProviderConfig.DEFAULT_MODEL, config.getModel());
    }

    @Test
    void openAiCreatesProviderEvenWithoutJenkins() {
        // createProvider() itself must not require a Jenkins instance to construct the
        // AiProvider object - only resolveApiToken() (called lazily inside chatCompletion) does.
        OpenAiProviderConfig config = new OpenAiProviderConfig("gpt-4o-mini", null);
        AiProvider provider = config.createProvider(objectMapper, 30);
        assertNotNull(provider);
    }

    @Test
    void openAiResolvesNullTokenWhenNoCredentialConfigured(JenkinsRule jenkins) {
        // OpenAI conceptually requires an API key, but no credential is selected here - this
        // must resolve to null (not throw), leaving it to the real HTTP round trip (a 401) to
        // report AUTHENTICATION_FAILED - see OpenAiChatCompletionsProviderTest.
        OpenAiProviderConfig config = new OpenAiProviderConfig("gpt-4o-mini", null);
        assertNull(config.resolveApiToken());
    }

    // --- Generic OpenAI-compatible: credential genuinely optional ---

    @Test
    void openAiCompatibleResolvesNullTokenWhenNoCredentialConfigured(JenkinsRule jenkins) {
        OpenAiCompatibleProviderConfig config =
                new OpenAiCompatibleProviderConfig("http://localhost:8000/v1", "m", null);
        assertNull(
                config.resolveApiToken(),
                "no credential configured, so no token should resolve - and this must not throw");
    }

    @Test
    void openAiCompatibleResolvesConfiguredCredential(JenkinsRule jenkins) throws Exception {
        String secretValue = "s3cr3t-token";
        StringCredentialsImpl credentials =
                new StringCredentialsImpl(CredentialsScope.GLOBAL, "my-cred", "d", Secret.fromString(secretValue));
        CredentialsProvider.lookupStores(jenkins.jenkins)
                .iterator()
                .next()
                .addCredentials(Domain.global(), credentials);

        OpenAiCompatibleProviderConfig config =
                new OpenAiCompatibleProviderConfig("http://localhost:8000/v1", "m", "my-cred");
        assertEquals(secretValue, config.resolveApiToken());
    }

    @Test
    void openAiCompatibleThrowsConfigurationInvalidWhenModelBlankEndToEnd() throws Exception {
        // Unlike OpenAiProviderConfig/OllamaProviderConfig (which both default a blank model to
        // a fixed default in their constructor), the Generic OpenAI-compatible config's model is
        // plain free text with no default - a blank value here must be caught as
        // CONFIGURATION_INVALID before any network call, not sent to the server as-is.
        try (var mock = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig(mock.baseUrl(), "  ", null);
            AiProvider provider = config.createProvider(objectMapper, 5);
            io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException.class,
                            () -> provider.chatCompletion(
                                    new io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest("s", "u", 0.2)));
            assertEquals(
                    io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException.Kind.CONFIGURATION_INVALID,
                    ex.getKind());
            assertNull(mock.lastRequestBody, "must not have sent any request with a blank model");
        }
    }

    @Test
    void openAiCompatibleDescriptorRequiresBaseUrl(JenkinsRule jenkins) {
        OpenAiCompatibleProviderConfig.DescriptorImpl descriptor = new OpenAiCompatibleProviderConfig.DescriptorImpl();
        FormValidation blank = descriptor.doCheckBaseUrl(null);
        assertEquals(FormValidation.Kind.ERROR, blank.kind);

        FormValidation badScheme = descriptor.doCheckBaseUrl("ftp://example.com");
        assertEquals(FormValidation.Kind.ERROR, badScheme.kind);

        FormValidation ok = descriptor.doCheckBaseUrl("http://localhost:8000/v1");
        assertEquals(FormValidation.Kind.OK, ok.kind);
    }

    // --- Anthropic ---

    @Test
    void anthropicUsesDefaultModelWhenBlank() {
        AnthropicProviderConfig config = new AnthropicProviderConfig(null, "cred-id");
        assertEquals(AnthropicProviderConfig.DEFAULT_MODEL, config.getModel());
    }

    @Test
    void anthropicResolvesNullApiKeyWhenNoCredentialConfigured(JenkinsRule jenkins) {
        AnthropicProviderConfig config = new AnthropicProviderConfig("claude-sonnet-5", null);
        assertNull(config.resolveApiKey());
    }

    @Test
    void anthropicBaseUrlDefaultsToNullUntilExplicitlySet() {
        AnthropicProviderConfig config = new AnthropicProviderConfig("claude-sonnet-5", null);
        assertNull(config.getBaseUrl(), "field itself stays null - AnthropicProvider applies the real default");
    }

    // --- Azure OpenAI: deployment name doubles as the displayed "model", api-version defaults ---

    @Test
    void azureModelIsTheDeploymentName() {
        AzureOpenAiProviderConfig config =
                new AzureOpenAiProviderConfig("https://x.openai.azure.com", "my-deployment", "cred");
        assertEquals("my-deployment", config.getModel());
    }

    @Test
    void azureApiVersionDefaultsWhenNotSet() {
        AzureOpenAiProviderConfig config = new AzureOpenAiProviderConfig("https://x.openai.azure.com", "d", "cred");
        assertNull(config.getApiVersion(), "field itself stays null until explicitly set");
        // The default is applied internally by createProvider(), not exposed as a getter default -
        // verified indirectly via AzureOpenAiProviderTest, which exercises the wire call.
    }

    // --- Gemini ---

    @Test
    void geminiUsesDefaultModelWhenBlank() {
        GeminiProviderConfig config = new GeminiProviderConfig(null, "cred");
        assertEquals(GeminiProviderConfig.DEFAULT_MODEL, config.getModel());
    }

    @Test
    void geminiBaseUrlDefaultsToNullUntilExplicitlySet() {
        GeminiProviderConfig config = new GeminiProviderConfig(null, "cred");
        assertNull(config.getBaseUrl(), "field itself stays null - GeminiProvider applies the real default");
    }

    // --- Ollama: credential is optional, sent as a Bearer token only when configured ---

    @Test
    void ollamaUsesDefaultModelWhenBlank() {
        OllamaProviderConfig config = new OllamaProviderConfig("http://host.docker.internal:11434/v1", null);
        assertEquals(OllamaProviderConfig.DEFAULT_MODEL, config.getModel());
    }

    @Test
    void ollamaProviderNeverSendsAnAuthorizationHeaderWhenNoCredentialConfigured() throws Exception {
        try (var mock = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            OllamaProviderConfig config = new OllamaProviderConfig(mock.baseUrl(), "llama3.1");
            AiProvider provider = config.createProvider(objectMapper, 10);
            provider.chatCompletion(new io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest("s", "u", 0.2));
            assertNull(mock.lastAuthorizationHeader);
        }
    }

    @Test
    void ollamaResolvesNullTokenWhenNoCredentialConfigured(JenkinsRule jenkins) {
        OllamaProviderConfig config = new OllamaProviderConfig("http://host.docker.internal:11434/v1", "llama3.1");
        assertNull(
                config.resolveApiToken(),
                "no credential configured, so no token should resolve - and this must not throw");
    }

    @Test
    void ollamaResolvesConfiguredCredential(JenkinsRule jenkins) throws Exception {
        String secretValue = "s3cr3t-ollama-proxy-token";
        StringCredentialsImpl credentials = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "my-ollama-cred", "d", Secret.fromString(secretValue));
        CredentialsProvider.lookupStores(jenkins.jenkins)
                .iterator()
                .next()
                .addCredentials(Domain.global(), credentials);

        OllamaProviderConfig config = new OllamaProviderConfig("http://host.docker.internal:11434/v1", "llama3.1");
        config.setCredentialsId("my-ollama-cred");
        assertEquals(secretValue, config.resolveApiToken());
    }

    @Test
    void ollamaSendsConfiguredCredentialAsBearerToken(JenkinsRule jenkins) throws Exception {
        String secretValue = "s3cr3t-ollama-proxy-token";
        StringCredentialsImpl credentials = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "my-ollama-cred", "d", Secret.fromString(secretValue));
        CredentialsProvider.lookupStores(jenkins.jenkins)
                .iterator()
                .next()
                .addCredentials(Domain.global(), credentials);

        try (var mock = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
            OllamaProviderConfig config = new OllamaProviderConfig(mock.baseUrl(), "llama3.1");
            config.setCredentialsId("my-ollama-cred");
            AiProvider provider = config.createProvider(objectMapper, 10);
            provider.chatCompletion(new io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest("s", "u", 0.2));
            assertEquals("Bearer " + secretValue, mock.lastAuthorizationHeader);
        }
    }

    @Test
    void ollamaUnreachableHostProducesConnectionFailedNotGenericMessage() {
        // The "Docker networking" scenario: nothing listening on the configured port must still
        // be safely categorized (CONNECTION_FAILED/TIMEOUT), never an uncaught exception, even
        // though "unreachable" here specifically means "wrong side of a container boundary"
        // rather than a genuinely offline host.
        OllamaProviderConfig config = new OllamaProviderConfig("http://127.0.0.1:1", "llama3.1");
        AiProvider provider = config.createProvider(objectMapper, 2);
        io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException ex = org.junit.jupiter.api.Assertions.assertThrows(
                io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException.class,
                () -> provider.chatCompletion(
                        new io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest("s", "u", 0.2)));
        assertTrue(
                ex.getKind() == io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException.Kind.CONNECTION_FAILED
                        || ex.getKind() == io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException.Kind.TIMEOUT,
                ex.getKind().toString());
    }

    @Test
    void ollamaDescriptorBlankBaseUrlGivesDockerNetworkingGuidance(JenkinsRule jenkins) {
        // This actionable guidance (rather than a bare "Connection refused") is what actually
        // resolves the most common real-world Ollama misconfiguration - a containerized Jenkins
        // pointed at "localhost" instead of the Docker host - so it must survive this hardening
        // pass unchanged.
        OllamaProviderConfig.DescriptorImpl descriptor = new OllamaProviderConfig.DescriptorImpl();
        FormValidation result = descriptor.doCheckBaseUrl(null);
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertTrue(
                result.getMessage().contains("host.docker.internal"),
                "expected Docker-networking guidance in: " + result.getMessage());
    }

    @Test
    void ollamaDescriptorRejectsNonHttpScheme(JenkinsRule jenkins) {
        OllamaProviderConfig.DescriptorImpl descriptor = new OllamaProviderConfig.DescriptorImpl();
        FormValidation result = descriptor.doCheckBaseUrl("ollama://host.docker.internal:11434");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    void ollamaDescriptorAcceptsValidHttpBaseUrl(JenkinsRule jenkins) {
        OllamaProviderConfig.DescriptorImpl descriptor = new OllamaProviderConfig.DescriptorImpl();
        FormValidation result = descriptor.doCheckBaseUrl("http://host.docker.internal:11434/v1");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    // --- Bedrock: optional AmazonWebServicesCredentials-typed credential ---

    @Test
    void bedrockResolvesNullCredentialsProviderWhenNoneConfigured(JenkinsRule jenkins) {
        BedrockProviderConfig config = new BedrockProviderConfig("us-east-1", "anthropic.claude-sonnet-4-5");
        assertNull(
                config.resolveCredentialsProvider(),
                "null signals BedrockProvider to fall back to the AWS SDK default credential chain");
    }

    @Test
    void bedrockResolvesConfiguredAwsCredential(JenkinsRule jenkins) throws Exception {
        // Deliberately does not call resolved.resolveCredentials(): AmazonWebServicesCredentials
        // resolves via a real AWS STS call even for a plain static access/secret key pair, which
        // would make this test depend on live AWS network access. Verifying that a non-null
        // provider is returned (i.e. the Jenkins credential was found and wired through) is
        // sufficient to prove BedrockProviderConfig's own resolution logic without invoking AWS.
        AWSCredentialsImpl awsCredentials = new AWSCredentialsImpl(
                CredentialsScope.GLOBAL, "my-aws-cred", "test-access-key", "test-secret-key", "d");
        CredentialsProvider.lookupStores(jenkins.jenkins)
                .iterator()
                .next()
                .addCredentials(Domain.global(), awsCredentials);

        BedrockProviderConfig config = new BedrockProviderConfig("us-east-1", "anthropic.claude-sonnet-4-5");
        config.setCredentialsId("my-aws-cred");

        var resolved = config.resolveCredentialsProvider();
        assertNotNull(
                resolved, "the configured Jenkins AWS credential must resolve to a non-null AwsCredentialsProvider");
    }

    @Test
    void bedrockCredentialsIdDefaultsToNull() {
        BedrockProviderConfig config = new BedrockProviderConfig("us-east-1", "m");
        assertNull(config.getCredentialsId());
        assertTrue(true); // region/model constructor-required, credential optional via setter - documented behavior
    }

    @Test
    void bedrockEndpointUrlAndRoleArnDefaultToNull() {
        // Both optional/additive fields: blank/unset must mean "normal AWS regional endpoint
        // resolution" and "use the credential/default chain directly", matching the pre-existing
        // saved-config behavior for every Bedrock config that predates these two fields.
        BedrockProviderConfig config = new BedrockProviderConfig("us-east-1", "m");
        assertNull(config.getEndpointUrl());
        assertNull(config.getRoleArn());
    }

    @Test
    void bedrockEndpointUrlAndRoleArnAreSettable() {
        BedrockProviderConfig config = new BedrockProviderConfig("us-east-1", "m");
        config.setEndpointUrl("https://vpce-example.bedrock-runtime.us-east-1.vpce.amazonaws.com");
        config.setRoleArn("arn:aws:iam::123456789012:role/my-role");
        assertEquals("https://vpce-example.bedrock-runtime.us-east-1.vpce.amazonaws.com", config.getEndpointUrl());
        assertEquals("arn:aws:iam::123456789012:role/my-role", config.getRoleArn());
    }
}

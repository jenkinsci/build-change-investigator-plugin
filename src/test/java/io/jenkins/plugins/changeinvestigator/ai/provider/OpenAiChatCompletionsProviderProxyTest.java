package io.jenkins.plugins.changeinvestigator.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.ProxyConfiguration;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisRequest;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisResult;
import io.jenkins.plugins.changeinvestigator.testutil.MockAiServer;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Confirms {@link HttpAiClientSupport} is actually built through
 * {@link ProxyConfiguration#newHttpClientBuilder()} rather than a raw
 * {@code HttpClient.newBuilder()}: with a bogus proxy configured, a request to an otherwise
 * directly-reachable server must still fail, because a client built the old way would have
 * ignored Jenkins' proxy configuration and connected directly (and succeeded).
 *
 * <p>Kept separate from {@link OpenAiChatCompletionsProviderTest} (which deliberately runs
 * without a Jenkins instance, since {@code ProxyConfiguration.newHttpClientBuilder()} must
 * degrade gracefully to an unproxied builder in that case) so the fast, Jenkins-free unit tests
 * there aren't slowed down by JenkinsRule bootstrap. Exercised once here via the OpenAI-family
 * provider; every other provider shares the same {@link HttpAiClientSupport#post} call path, so
 * this is not repeated per provider.
 */
@WithJenkins
class OpenAiChatCompletionsProviderProxyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void succeedsDirectlyWhenJenkinsHasNoProxyConfigured(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setProxy(null);

        try (MockAiServer mock = MockAiServer.start(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"mostLikelyCause\\\":\\\"x\\\"}\"}}]}")) {
            var provider = new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 5, objectMapper);
            AiAnalysisResult result = provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2));
            assertEquals("{\"mostLikelyCause\":\"x\"}", result.responseText());
        }
    }

    @Test
    void routesRequestsThroughTheJenkinsConfiguredProxy(JenkinsRule jenkins) throws Exception {
        // Nothing listens on 127.0.0.1:1 (a reserved, privileged port), so any request that
        // actually goes through this "proxy" must fail - even though the real MockAiServer
        // below is directly reachable and would otherwise answer immediately.
        jenkins.jenkins.setProxy(new ProxyConfiguration("127.0.0.1", 1));
        try {
            try (MockAiServer mock = MockAiServer.start("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")) {
                var provider =
                        new OpenAiChatCompletionsProvider("OpenAI", mock.baseUrl(), "m", "token", 3, objectMapper);
                AiAnalysisException ex = assertThrows(
                        AiAnalysisException.class, () -> provider.chatCompletion(new AiAnalysisRequest("s", "u", 0.2)));
                assertTrue(
                        ex.getKind() == AiAnalysisException.Kind.CONNECTION_FAILED
                                || ex.getKind() == AiAnalysisException.Kind.TIMEOUT,
                        ex.getKind().toString());
            }
        } finally {
            jenkins.jenkins.setProxy(null);
        }
    }
}

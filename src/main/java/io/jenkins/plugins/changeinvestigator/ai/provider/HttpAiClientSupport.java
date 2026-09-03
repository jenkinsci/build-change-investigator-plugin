package io.jenkins.plugins.changeinvestigator.ai.provider;

import hudson.ProxyConfiguration;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP plumbing for every {@code AiProvider} implementation that speaks plain
 * request/response JSON over HTTPS (all of them except AWS Bedrock, which uses the AWS SDK's
 * own transport and SigV4 signing instead). Centralizing this avoids re-implementing the same
 * proxy-awareness, timeout handling, and error-kind mapping in every adapter.
 */
final class HttpAiClientSupport {

    private HttpAiClientSupport() {}

    /**
     * A Jenkins-aware {@link HttpClient} so outbound calls to any AI provider honor the
     * instance's configured HTTP proxy; see
     * https://javadoc.jenkins.io/hudson/ProxyConfiguration.html#newHttpClientBuilder(). Safe to
     * call with no Jenkins instance present (e.g. plain unit tests): it degrades to an
     * unproxied builder rather than throwing.
     */
    static HttpClient newHttpClient(Duration timeout) {
        return ProxyConfiguration.newHttpClientBuilder().connectTimeout(timeout).build();
    }

    /**
     * Sends {@code body} as a POST to {@code url} with the given headers, returning the raw
     * response body on any 2xx status, and throwing a correctly-{@code Kind}ed
     * {@link AiAnalysisException} for every other outcome (network failure, timeout, non-2xx
     * status). Callers are responsible for parsing the response body into their own
     * provider-specific shape.
     */
    static String post(String providerLabel, String url, Map<String, String> headers, String body, Duration timeout)
            throws AiAnalysisException {
        HttpClient client = newHttpClient(timeout);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(requestBuilder::header);
        HttpRequest request = requestBuilder.build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.TIMEOUT, "Timed out waiting for " + providerLabel + ".", e);
        } catch (IOException e) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONNECTION_FAILED,
                    "Could not connect to " + providerLabel + ": " + e.getMessage(),
                    e);
        } catch (java.io.UncheckedIOException e) {
            // HttpClient's internal async plumbing can surface connection failures wrapped this
            // way rather than as a plain IOException; treat it the same.
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONNECTION_FAILED,
                    "Could not connect to " + providerLabel + ": " + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.CONNECTION_FAILED, "AI analysis was interrupted.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.HTTP_ERROR,
                    providerLabel + " returned HTTP " + response.statusCode() + ": " + snippet(response.body()));
        }
        return response.body();
    }

    static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}

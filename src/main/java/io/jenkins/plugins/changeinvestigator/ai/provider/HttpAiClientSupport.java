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

        HttpRequest request;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(requestBuilder::header);
            request = requestBuilder.build();
        } catch (IllegalArgumentException e) {
            // URI.create(...) and HttpRequest.Builder both throw this (unchecked) for a malformed
            // or unsupported endpoint URL (bad syntax, missing/unsupported scheme, ...) rather
            // than a checked exception - without this, a misconfigured base URL/endpoint would
            // propagate as a raw IllegalArgumentException instead of a properly-kinded,
            // user-facing AiAnalysisException.
            throw new AiAnalysisException(
                    AiAnalysisException.Kind.INVALID_ENDPOINT,
                    "The configured endpoint URL for " + providerLabel + " is invalid: " + e.getMessage(),
                    e);
        }

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

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new AiAnalysisException(
                    kindForStatus(status),
                    providerLabel + " returned HTTP " + status + ": " + snippet(response.body()));
        }
        return response.body();
    }

    /**
     * Maps an HTTP status code to the most specific {@link AiAnalysisException.Kind} that can be
     * determined from the status alone. Providers whose JSON error body distinguishes further
     * (e.g. OpenAI's HTTP 429 covering both rate limiting and insufficient-quota) may re-classify
     * the caught exception after inspecting the body themselves; this shared mapping only
     * guarantees the coarse category every caller can rely on without knowing any provider's
     * specific error-body shape.
     */
    private static AiAnalysisException.Kind kindForStatus(int status) {
        return switch (status) {
            case 401 -> AiAnalysisException.Kind.AUTHENTICATION_FAILED;
            case 403 -> AiAnalysisException.Kind.AUTHORIZATION_FAILED;
            case 404 -> AiAnalysisException.Kind.MODEL_NOT_FOUND;
            case 429 -> AiAnalysisException.Kind.RATE_LIMITED;
            default ->
                status >= 500 ? AiAnalysisException.Kind.PROVIDER_UNAVAILABLE : AiAnalysisException.Kind.HTTP_ERROR;
        };
    }

    static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}

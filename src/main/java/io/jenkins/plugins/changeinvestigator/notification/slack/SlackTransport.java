package io.jenkins.plugins.changeinvestigator.notification.slack;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ProxyConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Fixed-origin Slack bot transport. It never retries a possibly accepted submission. */
public final class SlackTransport {
    private static final URI ORIGIN = URI.create("https://slack.com/api/");
    private static final int MAX_BYTES = 65536;
    private static final Map<String, Long> GATES = new HashMap<>();
    private static final ThreadPoolExecutor DNS =
            new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "bci-slack-address-check");
                thread.setDaemon(true);
                return thread;
            });
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> PERMANENT = Set.of(
            "invalid_auth",
            "not_authed",
            "token_revoked",
            "token_expired",
            "account_inactive",
            "missing_scope",
            "no_permission",
            "access_denied",
            "channel_not_found",
            "invalid_channel",
            "not_in_channel",
            "is_archived",
            "invalid_arguments",
            "invalid_arg_name",
            "invalid_blocks",
            "invalid_blocks_format",
            "invalid_json",
            "msg_too_long",
            "no_text",
            "restricted_action",
            "ekm_access_denied",
            "team_access_not_granted");

    static {
        JSON.getFactory()
                .setStreamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(24)
                        .maxStringLength(MAX_BYTES)
                        .maxNumberLength(32)
                        .build());
    }

    private final URI origin;
    private final HttpClient client;
    private final Duration timeout;

    public SlackTransport() {
        this.origin = ORIGIN;
        this.client = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.timeout = Duration.ofSeconds(15);
    }

    /** Package-private loopback seam; there is no configurable production endpoint. */
    SlackTransport(URI origin, Duration timeout) {
        if (!"http".equals(origin.getScheme())
                || !"127.0.0.1".equals(origin.getHost())
                || origin.getPort() < 1
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || !"/api/".equals(origin.getPath())
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("Invalid local test transport");
        }
        this.origin = origin;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public enum Status {
        SENT,
        RETRYABLE,
        PERMANENT_FAILURE,
        UNKNOWN_OUTCOME,
        THREAD_UNAVAILABLE
    }

    public record Receipt(String workspaceId, String channelId, String messageTs) {}

    public record Outcome(Status status, String safeCode, long retryAfterMillis, Receipt receipt) {}

    public record ConnectionResult(boolean usable, String safeCode, String workspaceId, String channelId) {}

    public Outcome send(String token, String workspaceId, String channelId, ObjectNode payload, String threadTs) {
        if (!validToken(token)
                || !id(workspaceId, "T")
                || !id(channelId, "CG")
                || payload == null
                || (threadTs != null && !timestamp(threadTs))) {
            return outcome(Status.PERMANENT_FAILURE, "INVALID_CONFIGURATION");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            Wire auth = request("auth.test", token, new byte[] {'{', '}'}, workspaceId, null, deadline);
            Outcome authFailure = classify(auth);
            if (authFailure != null) return beforeSubmission(authFailure);
            if (!workspaceId.equals(auth.body().path("team_id").asText())
                    || !id(auth.body().path("bot_id").asText(), "B")) {
                return outcome(Status.PERMANENT_FAILURE, "WORKSPACE_IDENTITY_MISMATCH");
            }
            if (threadTs != null) {
                ObjectNode lookup = JSON.createObjectNode()
                        .put("channel", channelId)
                        .put("oldest", threadTs)
                        .put("latest", threadTs)
                        .put("inclusive", true)
                        .put("limit", 1);
                Wire parent = request(
                        "conversations.history",
                        token,
                        JSON.writeValueAsBytes(lookup),
                        workspaceId,
                        channelId,
                        deadline);
                Outcome parentFailure = classify(parent);
                if (parentFailure != null) {
                    if (parentFailure.status() == Status.RETRYABLE) return parentFailure;
                    return outcome(Status.THREAD_UNAVAILABLE, "THREAD_UNAVAILABLE");
                }
                JsonNode messages = parent.body().path("messages");
                if (!messages.isArray()
                        || messages.size() != 1
                        || !threadTs.equals(messages.get(0).path("ts").asText())
                        || "message_deleted"
                                .equals(messages.get(0).path("subtype").asText())) {
                    return outcome(Status.THREAD_UNAVAILABLE, "THREAD_UNAVAILABLE");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Status.RETRYABLE, "PRE_SUBMISSION_UNAVAILABLE", 30000, null);
        } catch (Exception | LinkageError e) {
            return new Outcome(Status.RETRYABLE, "PRE_SUBMISSION_UNAVAILABLE", 30000, null);
        }
        try {
            ObjectNode body = payload.deepCopy();
            body.put("channel", channelId)
                    .put("reply_broadcast", false)
                    .put("parse", "none")
                    .put("link_names", false)
                    .put("unfurl_links", false)
                    .put("unfurl_media", false);
            body.remove("thread_ts");
            if (threadTs != null) body.put("thread_ts", threadTs);
            byte[] bytes = JSON.writeValueAsBytes(body);
            if (bytes.length > MAX_BYTES) return outcome(Status.PERMANENT_FAILURE, "PAYLOAD_LIMIT");
            if (new String(bytes, StandardCharsets.UTF_8).contains(token)) {
                return outcome(Status.PERMANENT_FAILURE, "SECRET_PAYLOAD_WITHHELD");
            }
            Wire result = request("chat.postMessage", token, bytes, workspaceId, channelId, deadline);
            Outcome failure = classify(result);
            if (failure != null) return failure;
            JsonNode json = result.body();
            String returnedChannel = json.path("channel").asText();
            String ts = json.path("ts").asText();
            String team =
                    json.path("team").asText(json.path("message").path("team").asText(workspaceId));
            if (!channelId.equals(returnedChannel) || !workspaceId.equals(team) || !timestamp(ts)) {
                return outcome(Status.UNKNOWN_OUTCOME, "INVALID_RECEIPT");
            }
            if (threadTs != null
                    && json.path("message").has("thread_ts")
                    && !threadTs.equals(json.path("message").path("thread_ts").asText())) {
                return outcome(Status.UNKNOWN_OUTCOME, "INVALID_THREAD_RECEIPT");
            }
            return new Outcome(Status.SENT, "ACCEPTED", 0, new Receipt(workspaceId, channelId, ts));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return outcome(Status.UNKNOWN_OUTCOME, "TRANSPORT_UNCERTAIN");
        } catch (Exception | LinkageError e) {
            return outcome(Status.UNKNOWN_OUTCOME, "TRANSPORT_UNCERTAIN");
        }
    }

    /** Non-posting validation confirms bot identity and membership, not every workspace posting policy. */
    public ConnectionResult testConnection(String token, String channelId) {
        if (!validToken(token) || !id(channelId, "CG")) return connectionFailure("INVALID_CONFIGURATION");
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            Wire auth = request("auth.test", token, new byte[] {'{', '}'}, "connection", null, deadline);
            Outcome authFailure = classify(auth);
            if (authFailure != null) return connectionFailure(authFailure.safeCode());
            String team = auth.body().path("team_id").asText();
            if (!id(team, "T") || !id(auth.body().path("bot_id").asText(), "B")) {
                return connectionFailure("BOT_IDENTITY_REQUIRED");
            }
            Wire info = request(
                    "conversations.info",
                    token,
                    JSON.writeValueAsBytes(JSON.createObjectNode().put("channel", channelId)),
                    team,
                    channelId,
                    deadline);
            Outcome infoFailure = classify(info);
            if (infoFailure != null) return connectionFailure(infoFailure.safeCode());
            JsonNode channel = info.body().path("channel");
            if (!channelId.equals(channel.path("id").asText())
                    || !channel.path("is_member").asBoolean(false)
                    || channel.path("is_archived").asBoolean(false)
                    || channel.path("is_im").asBoolean(false)
                    || channel.path("is_mpim").asBoolean(false)
                    || (channel.has("context_team_id")
                            && !team.equals(channel.path("context_team_id").asText()))) {
                return connectionFailure("CHANNEL_UNAVAILABLE");
            }
            return new ConnectionResult(true, "BOT_AND_CHANNEL_VERIFIED", team, channelId);
        } catch (Exception | LinkageError e) {
            return connectionFailure("CONNECTION_UNAVAILABLE");
        }
    }

    private static Outcome beforeSubmission(Outcome result) {
        return result.status() == Status.UNKNOWN_OUTCOME
                ? new Outcome(Status.RETRYABLE, "PRE_SUBMISSION_UNAVAILABLE", 30000, null)
                : result;
    }

    private Wire request(String method, String token, byte[] bytes, String workspace, String channel, long deadline)
            throws Exception {
        if (ORIGIN.equals(origin)) {
            var lookup = DNS.submit(() -> InetAddress.getAllByName("slack.com"));
            InetAddress[] addresses;
            try {
                long lookupMillis = Math.min(5000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (lookupMillis < 1) throw new IllegalStateException("Attempt deadline");
                addresses = lookup.get(lookupMillis, TimeUnit.MILLISECONDS);
            } finally {
                if (!lookup.isDone()) lookup.cancel(true);
            }
            for (InetAddress address : addresses) {
                byte[] raw = address.getAddress();
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || (raw.length == 16 && (raw[0] & 0xfe) == 0xfc)
                        || (raw.length == 4
                                && (raw[0] & 255) == 100
                                && (raw[1] & 255) >= 64
                                && (raw[1] & 255) <= 127)) {
                    throw new IllegalStateException("API address unavailable");
                }
            }
            pace(workspace, channel, method, deadline);
        }
        long remaining = Math.min(timeout.toMillis(), TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (remaining < 1) throw new IllegalStateException("Attempt deadline");
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(method))
                .timeout(Duration.ofMillis(remaining))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.MILLISECONDS);
            long retry = retryAfter(response.headers().firstValue("Retry-After").orElse(""));
            JsonNode parsed = null;
            try {
                parsed = JSON.readTree(response.body());
            } catch (Exception ignored) {
                /* No raw response leaves transport. */
            }
            return new Wire(response.statusCode(), retry, parsed);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    static void pace(String workspace, String channel, String method, long deadline) throws InterruptedException {
        long wait;
        synchronized (GATES) {
            long now = System.nanoTime();
            GATES.entrySet().removeIf(entry -> entry.getValue() < now);
            String methodKey = workspace + ":" + method;
            String channelKey = workspace + ":channel:" + channel;
            long due = Math.max(now, GATES.getOrDefault(methodKey, now));
            if (channel != null) due = Math.max(due, GATES.getOrDefault(channelKey, now));
            if (GATES.size() >= 2048 || due >= deadline) throw new IllegalStateException("Request budget unavailable");
            GATES.put(methodKey, due + TimeUnit.SECONDS.toNanos(1));
            if (channel != null) GATES.put(channelKey, due + TimeUnit.SECONDS.toNanos(1));
            wait = due - now;
        }
        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
    }

    private static Outcome classify(Wire response) {
        if (response.status() == 429) return new Outcome(Status.RETRYABLE, "RATE_LIMITED", response.retry(), null);
        if (response.status() == 401 || response.status() == 403)
            return outcome(Status.PERMANENT_FAILURE, "AUTHORIZATION_REJECTED");
        if (Set.of(400, 404, 405, 413, 415, 422).contains(response.status()))
            return outcome(Status.PERMANENT_FAILURE, "REQUEST_REJECTED");
        if (response.status() != 200
                || response.body() == null
                || !response.body().path("ok").isBoolean()) {
            return outcome(Status.UNKNOWN_OUTCOME, "RESPONSE_UNCERTAIN");
        }
        if (response.body().path("ok").asBoolean()) return null;
        String error = response.body().path("error").asText();
        if ("ratelimited".equals(error)) return new Outcome(Status.RETRYABLE, "RATE_LIMITED", response.retry(), null);
        if (Set.of("thread_not_found", "message_not_found", "cannot_reply_to_message")
                .contains(error)) {
            return outcome(Status.THREAD_UNAVAILABLE, "THREAD_UNAVAILABLE");
        }
        if (PERMANENT.contains(error)) return outcome(Status.PERMANENT_FAILURE, "SLACK_REJECTED");
        return outcome(Status.UNKNOWN_OUTCOME, "RESPONSE_UNCERTAIN");
    }

    private static boolean validToken(String token) {
        return token != null && token.length() >= 10 && token.length() <= 4096 && token.matches("[A-Za-z0-9._-]+");
    }

    private static boolean id(String value, String prefixes) {
        return value != null && value.matches("[" + prefixes + "][A-Z0-9]{2,63}");
    }

    private static boolean timestamp(String value) {
        return value != null && value.matches("[0-9]{1,16}\\.[0-9]{6}");
    }

    private static long retryAfter(String raw) {
        try {
            return Math.multiplyExact(Math.max(1, Math.min(86400, Long.parseLong(raw))), 1000);
        } catch (NumberFormatException | ArithmeticException e) {
            return 30000;
        }
    }

    private static Outcome outcome(Status status, String code) {
        return new Outcome(status, code, 0, null);
    }

    private static ConnectionResult connectionFailure(String code) {
        return new ConnectionResult(false, code, null, null);
    }

    private record Wire(int status, long retry, JsonNode body) {}

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer part : buffers) {
                if (part.remaining() > MAX_BYTES - buffer.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalStateException("Response limit"));
                    return;
                }
                byte[] bytes = new byte[part.remaining()];
                part.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(new IllegalStateException("Response unavailable"));
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toByteArray());
        }
    }
}

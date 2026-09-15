package io.jenkins.plugins.changeinvestigator.slack.transport;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.ProxyConfiguration;
import hudson.security.ACL;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/** Fixed-origin Slack Web API transport. All public results contain only allowlisted status text. */
@Extension
public class SlackTransport implements ExtensionPoint {
    private static final URI API = URI.create("https://slack.com/api/");
    private static final int MAX_BODY = 262144;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(20)
                            .maxStringLength(MAX_BODY)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint;
    private final Function<String, String> tokens;
    private volatile HttpClient httpClient;

    public SlackTransport() {
        this(API, SlackTransport::credential);
    }

    // Trusted plugin subclasses may supply an endpoint; this is not a Jenkins configuration option.
    protected SlackTransport(URI endpoint, Function<String, String> tokens) {
        this.endpoint = endpoint;
        this.tokens = tokens;
    }

    public static SlackTransport get() {
        return ExtensionList.lookup(SlackTransport.class).get(0);
    }

    public static boolean isValidChannel(String value) {
        return value != null && (value.matches("[CG][A-Z0-9]{8,31}") || value.matches("#[a-z0-9_-]{1,80}"));
    }

    public static boolean isValidUserId(String value) {
        return value != null && value.matches("[UW][A-Z0-9]{8,31}");
    }

    /** Opaque binding for cached workspace verification; no token is returned or retained. */
    public String credentialFingerprint(String credentialId) {
        try {
            String token = tokens.apply(credentialId);
            return validToken(token) ? fingerprint(token) : "";
        } catch (RuntimeException | LinkageError failure) {
            return "";
        }
    }

    private static String fingerprint(String token) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required SHA-256 support unavailable", unavailable);
        }
    }

    /** Refuse persistence of a payload that echoes the selected bot secret. */
    public boolean isSafeToStore(String credentialId, String text) {
        try {
            String token = tokens.apply(credentialId);
            return validToken(token) && text != null && !text.contains(token);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Verify only an explicitly mapped ID; this never enumerates the workspace directory. */
    public MemberVerification verifyMember(String credentialId, String userId) {
        if (!isValidUserId(userId))
            return new MemberVerification(false, "", "", "", "", MemberVerification.Status.INVALID);
        try {
            String token = tokens.apply(credentialId);
            if (!validToken(token)) return unverifiedMember();
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            Reply auth = call(token, "auth.test", "{}", deadline);
            String team = auth.json.optString("team_id");
            if (!auth.ok()
                    || !team.matches("T[A-Z0-9]{8,31}")
                    || auth.json.optString("bot_id").isEmpty()) return unverifiedMember();
            Reply reply = call(token, "users.info?user=" + userId, null, deadline);
            if (!reply.ok()) {
                if (reply.status == 200 && "user_not_found".equals(reply.json.optString("error")))
                    return invalidMember(userId, team, token);
                return unverifiedMember();
            }
            JSONObject user = reply.json.optJSONObject("user");
            if (user == null
                    || !isValidUserId(user.optString("id"))
                    || !user.optString("team_id").matches("T[A-Z0-9]{8,31}")) return unverifiedMember();
            if (!userId.equals(user.optString("id"))
                    || !team.equals(user.optString("team_id"))
                    || user.optBoolean("deleted")
                    || user.optBoolean("is_bot")) return invalidMember(userId, team, token);
            JSONObject profile = user.optJSONObject("profile");
            String label = profile == null ? "" : profile.optString("display_name");
            if (label.isBlank()) label = profile == null ? "" : profile.optString("real_name");
            if (label.isBlank()) label = user.optString("real_name");
            return new MemberVerification(
                    true, userId, team, fingerprint(token), display(label.replace(token, "[redacted]")));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unverifiedMember();
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            return unverifiedMember();
        }
    }

    private static MemberVerification unverifiedMember() {
        return new MemberVerification(false, "", "", "", "");
    }

    private static MemberVerification invalidMember(String userId, String team, String token) {
        return new MemberVerification(false, userId, team, fingerprint(token), "", MemberVerification.Status.INVALID);
    }

    /** Delivery-time guard for an explicitly mapped member and the episode's resolved workspace. */
    public boolean verifyMappedUser(String credentialId, SlackRoute route, String userId) {
        if (!isValidUserId(userId) || route == null) return false;
        try {
            String token = tokens.apply(credentialId);
            if (!validToken(token)) return false;
            Reply reply = call(
                    token,
                    "users.info?user=" + userId,
                    null,
                    System.nanoTime() + Duration.ofSeconds(4).toNanos());
            JSONObject user = reply.json.optJSONObject("user");
            return reply.ok()
                    && user != null
                    && userId.equals(user.optString("id"))
                    && route.teamId().equals(user.optString("team_id"))
                    && !user.optBoolean("deleted")
                    && !user.optBoolean("is_bot");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    public ConnectionResult checkConnection(String credentialId, String channel) {
        if (!isValidChannel(channel)) return failed("Enter a channel name beginning with # or a Slack channel ID.");
        try {
            String token = tokens.apply(credentialId);
            if (!validToken(token)) return failed("Select an available Slack bot Secret text credential.");
            long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
            Reply auth = call(token, "auth.test", "{}", deadline);
            if (auth.limited()) return connectionRateLimited(auth);
            if (!auth.ok()) return failed("Slack authentication failed. Check the selected credential.");
            String team = auth.json.optString("team_id");
            if (!team.matches("T[A-Z0-9]{8,31}")
                    || auth.json.optString("bot_id").isEmpty())
                return failed("Use a Slack bot credential for a workspace.");
            if (!List.of(auth.scopes.split(",")).stream().map(String::trim).anyMatch("chat:write"::equals))
                return failed("The Slack bot needs the chat:write permission.");
            String id = channel;
            if (channel.startsWith("#")) {
                id = null;
                String cursor = "";
                for (int page = 0; page < 5; page++) {
                    Reply listing = call(
                            token,
                            "conversations.list?types=public_channel,private_channel&exclude_archived=true&limit=200&cursor="
                                    + URLEncoder.encode(cursor, StandardCharsets.UTF_8),
                            null,
                            deadline);
                    if (listing.limited()) return connectionRateLimited(listing);
                    if (!listing.ok()) return inaccessible(channel);
                    for (Object value : listing.json.optJSONArray("channels") == null
                            ? List.of()
                            : listing.json.getJSONArray("channels")) {
                        JSONObject candidate = (JSONObject) value;
                        if (channel.substring(1).equals(candidate.optString("name"))) {
                            id = candidate.optString("id");
                            break;
                        }
                    }
                    if (id != null) break;
                    JSONObject metadata = listing.json.optJSONObject("response_metadata");
                    cursor = metadata == null ? "" : metadata.optString("next_cursor");
                    if (cursor.isBlank() || cursor.length() > 2048) break;
                }
                if (id == null) return failed("Channel not found in the bounded lookup. Try its Slack channel ID.");
            }
            if (!id.matches("[CG][A-Z0-9]{8,31}")) return failed("Unable to resolve this Slack channel.");
            Reply info = call(token, "conversations.info?channel=" + id, null, deadline);
            if (info.limited()) return connectionRateLimited(info);
            JSONObject conversation = info.json.optJSONObject("channel");
            if (!info.ok()
                    || conversation == null
                    || !id.equals(conversation.optString("id"))
                    || !conversation.optBoolean("is_member")
                    || conversation.optBoolean("is_archived")
                    || conversation.optBoolean("is_im")
                    || conversation.optBoolean("is_mpim")) return inaccessible(channel);
            return new ConnectionResult(
                    true,
                    "Connected and ready to post.",
                    display(auth.json.optString("team").replace(token, "[redacted]")),
                    "#" + display(conversation.optString("name").replace(token, "[redacted]")),
                    new SlackRoute(team, id),
                    0,
                    fingerprint(token));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("Slack connection check was interrupted.");
        } catch (IOException | RuntimeException | LinkageError e) {
            return failed("Unable to complete the Slack connection check. Check the credential, channel and network.");
        }
    }

    public DeliveryResult post(
            String credentialId, SlackRoute route, String frozenPayload, String threadTs, String clientMessageId) {
        boolean posting = false;
        try {
            if (route == null
                    || !route.teamId().matches("T[A-Z0-9]{8,31}")
                    || !route.channelId().matches("[CG][A-Z0-9]{8,31}")
                    || frozenPayload == null
                    || frozenPayload.length() > 48000
                    || (threadTs != null && !threadTs.isEmpty() && !threadTs.matches("[0-9]{1,20}\\.[0-9]{6}")))
                return result(DeliveryResult.Outcome.PERMANENT, "Invalid Slack delivery data.");
            UUID.fromString(clientMessageId);
            String token = tokens.apply(credentialId);
            if (!validToken(token)) return result(DeliveryResult.Outcome.PERMANENT, "Slack credential is unavailable.");
            if (frozenPayload.contains(token))
                return result(DeliveryResult.Outcome.PERMANENT, "Slack payload contains protected data.");
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            Reply auth = call(token, "auth.test", "{}", deadline);
            if (auth.limited()) return retry(auth);
            if (auth.status >= 500) return result(DeliveryResult.Outcome.RETRY, "Slack is temporarily unavailable.");
            if (!auth.ok() || !route.teamId().equals(auth.json.optString("team_id")))
                return result(DeliveryResult.Outcome.PERMANENT, "Slack credential or workspace changed.");
            JSONObject supplied;
            try {
                supplied = parse(frozenPayload);
            } catch (IOException e) {
                return result(DeliveryResult.Outcome.PERMANENT, "Invalid Slack message data.");
            }
            JSONObject body = new JSONObject();
            body.put("text", supplied.getString("text"));
            body.put("blocks", supplied.getJSONArray("blocks"));
            body.put("channel", route.channelId());
            body.put("client_msg_id", clientMessageId);
            body.put("parse", "none");
            body.put("mrkdwn", false);
            body.put("link_names", false);
            body.put("unfurl_links", false);
            body.put("unfurl_media", false);
            if (threadTs != null && !threadTs.isEmpty()) body.put("thread_ts", threadTs);
            posting = true;
            Reply sent = call(token, "chat.postMessage", body.toString(), deadline);
            if (sent.limited()) return retry(sent);
            if (sent.ok()
                    && route.channelId().equals(sent.json.optString("channel"))
                    && sent.json.optString("ts").matches("[0-9]{1,20}\\.[0-9]{6}"))
                return new DeliveryResult(DeliveryResult.Outcome.ACCEPTED, sent.json.getString("ts"), 0, "Sent.");
            String error = sent.json.optString("error");
            if (List.of(
                                    "invalid_auth",
                                    "not_authed",
                                    "token_revoked",
                                    "account_inactive",
                                    "missing_scope",
                                    "channel_not_found",
                                    "not_in_channel",
                                    "is_archived",
                                    "restricted_action",
                                    "thread_not_found",
                                    "msg_too_long",
                                    "invalid_blocks")
                            .contains(error)
                    || sent.status == 401
                    || sent.status == 403)
                return result(
                        DeliveryResult.Outcome.PERMANENT,
                        "Slack rejected this message. Check the bot and channel permissions.");
            return result(DeliveryResult.Outcome.UNKNOWN, "Delivery outcome is unknown; automatic resend is paused.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(
                    posting ? DeliveryResult.Outcome.UNKNOWN : DeliveryResult.Outcome.RETRY,
                    "Slack delivery was interrupted.");
        } catch (IOException e) {
            return result(
                    posting ? DeliveryResult.Outcome.UNKNOWN : DeliveryResult.Outcome.RETRY,
                    "Unable to confirm Slack delivery.");
        } catch (RuntimeException | LinkageError e) {
            return result(
                    posting ? DeliveryResult.Outcome.UNKNOWN : DeliveryResult.Outcome.PERMANENT,
                    "Unable to complete Slack delivery safely.");
        }
    }

    private Reply call(String token, String method, String body, long deadline)
            throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Deadline reached");
        HttpClient client = client();
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.resolve(method))
                .timeout(Duration.ofNanos(remaining))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) request.GET();
        else
            request.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        CompletableFuture<HttpResponse<byte[]>> pending =
                client.sendAsync(request.build(), ignored -> new BoundedBody());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("Slack request deadline reached", e);
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("Slack request failed", e);
        }
        JSONObject json;
        try {
            json = parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            json = new JSONObject();
        }
        long retry = 60;
        try {
            retry = Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"));
        } catch (NumberFormatException ignored) {
            /* Use the conservative fallback. */
        }
        return new Reply(
                response.statusCode(),
                json,
                Math.max(1, retry),
                response.headers().firstValue("x-oauth-scopes").orElse(""));
    }

    private static String credential(String id) {
        if (id == null || id.isBlank() || id.length() > 256) return null;
        StringCredentials value = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(id));
        return value == null ? null : value.getSecret().getPlainText();
    }

    private static JSONObject parse(String text) throws IOException {
        var tree = JSON.readTree(text);
        if (tree == null || !tree.isObject()) throw new IOException("Expected Slack response object");
        return JSONObject.fromObject(tree.toString());
    }

    private synchronized HttpClient client() {
        if (httpClient == null) {
            httpClient = ProxyConfiguration.newHttpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        return httpClient;
    }

    private static boolean validToken(String token) {
        return token != null && token.length() <= 2048 && token.matches("(?:xoxe\\.)?xoxb-[A-Za-z0-9-]+");
    }

    private static String display(String value) {
        String clean = value.replaceAll("[\\p{Cntrl}<>]", "");
        return clean.substring(0, Math.min(80, clean.length()));
    }

    private static ConnectionResult failed(String text) {
        return new ConnectionResult(false, text, "", "", null);
    }

    private static ConnectionResult inaccessible(String channel) {
        return failed("Unable to access " + (channel.startsWith("#") ? channel : "the selected channel")
                + " with this bot credential.");
    }

    private static ConnectionResult connectionRateLimited(Reply reply) {
        return new ConnectionResult(
                false, "Slack is temporarily unavailable. Try Test Connection again later.", "", "", null, reply.retry);
    }

    private static DeliveryResult result(DeliveryResult.Outcome outcome, String text) {
        return new DeliveryResult(outcome, "", 60, text);
    }

    private static DeliveryResult retry(Reply reply) {
        return new DeliveryResult(DeliveryResult.Outcome.RETRY, "", reply.retry, "Slack rate limit; retry scheduled.");
    }

    private record Reply(int status, JSONObject json, long retry, String scopes) {
        boolean ok() {
            return status == 200 && json.optBoolean("ok");
        }

        boolean limited() {
            return status == 429
                    || "ratelimited".equals(json.optString("error"))
                    || "rate_limited".equals(json.optString("error"));
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return future;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY - bytes.size()) {
                    subscription.cancel();
                    future.completeExceptionally(new IOException("Response too large"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            future.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            future.complete(bytes.toByteArray());
        }
    }
}

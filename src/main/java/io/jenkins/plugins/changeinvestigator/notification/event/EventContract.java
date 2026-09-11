package io.jenkins.plugins.changeinvestigator.notification.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

/** Validator for the bundled, fixed v1 contract. Never resolves external schemas or Java types. */
final class EventContract {
    private static final JsonNode SCHEMA = load();

    private EventContract() {}

    static void validate(JsonNode event) {
        if (!matches(SCHEMA, event, 0)) throw new IllegalArgumentException("Invalid notification event v1");
        JsonNode boundary = event.path("boundary");
        if (boundary.path("firstBadVerified").asBoolean()) {
            long good = boundary.path("lastKnownGood").path("number").asLong();
            long bad = boundary.path("firstBad").path("number").asLong();
            if (good >= bad
                    || bad > event.path("build").path("number").asLong()
                    || !"SUCCESS"
                            .equals(boundary.path("lastKnownGood")
                                    .path("result")
                                    .asText())
                    || !"FAILURE"
                            .equals(boundary.path("firstBad").path("result").asText()))
                throw new IllegalArgumentException("Invalid notification boundary order");
        }
    }

    private static JsonNode load() {
        try (var stream = EventContract.class.getResourceAsStream(
                "/io/jenkins/plugins/changeinvestigator/notification/event-v1.schema.json")) {
            if (stream == null) throw new IllegalStateException("Notification contract unavailable");
            return new ObjectMapper().readTree(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Notification contract unavailable", e);
        }
    }

    private static boolean matches(JsonNode rule, JsonNode value, int depth) {
        if (depth > 40 || value == null) return false;
        if (rule.has("$ref")) return matches(SCHEMA.at(rule.get("$ref").asText().substring(1)), value, depth + 1);
        if (rule.has("type")) {
            JsonNode types = rule.get("type");
            boolean valid = types.isArray() ? false : type(types.asText(), value);
            if (types.isArray()) for (JsonNode t : types) valid |= type(t.asText(), value);
            if (!valid) return false;
        }
        if (rule.has("const") && !rule.get("const").equals(value)) return false;
        if (rule.has("enum")) {
            boolean found = false;
            for (JsonNode option : rule.get("enum")) found |= option.equals(value);
            if (!found) return false;
        }
        if (rule.has("not") && matches(rule.get("not"), value, depth + 1)) return false;
        for (String mode : new String[] {"allOf", "anyOf", "oneOf"}) {
            if (!rule.has(mode)) continue;
            int count = 0;
            for (JsonNode child : rule.get(mode)) if (matches(child, value, depth + 1)) count++;
            if ((mode.equals("allOf") && count != rule.get(mode).size())
                    || (mode.equals("anyOf") && count == 0)
                    || (mode.equals("oneOf") && count != 1)) return false;
        }
        if (rule.has("if")) {
            String branch = matches(rule.get("if"), value, depth + 1) ? "then" : "else";
            if (rule.has(branch) && !matches(rule.get(branch), value, depth + 1)) return false;
        }
        if (value.isObject()) {
            if (rule.has("required"))
                for (JsonNode required : rule.get("required")) if (!value.has(required.asText())) return false;
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode child = rule.path("properties").get(field.getKey());
                if (child != null && !matches(child, field.getValue(), depth + 1)) return false;
                if (child == null
                        && rule.has("additionalProperties")
                        && !rule.get("additionalProperties").asBoolean()) return false;
            }
        }
        if (value.isArray()) {
            if (value.size() < rule.path("minItems").asInt(0)
                    || value.size() > rule.path("maxItems").asInt(Integer.MAX_VALUE)) return false;
            var unique = new HashSet<JsonNode>();
            for (JsonNode item : value) {
                if (rule.path("uniqueItems").asBoolean() && !unique.add(item)) return false;
                if (rule.has("items") && !matches(rule.get("items"), item, depth + 1)) return false;
            }
        }
        if (value.isTextual()) {
            String text = value.asText();
            int length = text.codePointCount(0, text.length());
            if (length < rule.path("minLength").asInt(0)
                    || length > rule.path("maxLength").asInt(32768)) return false;
            if (rule.has("pattern")
                    && !java.util.regex.Pattern.compile(rule.get("pattern").asText())
                            .matcher(text)
                            .find()) return false;
            try {
                switch (rule.path("format").asText()) {
                    case "uuid" -> {
                        if (!UUID.fromString(text).toString().equals(text)) return false;
                    }
                    case "date-time" -> Instant.parse(text);
                    case "uri" -> {
                        URI uri = URI.create(text);
                        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null) return false;
                    }
                    default -> {}
                }
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                return false;
            }
        }
        return !value.isNumber()
                || (value.asDouble() >= rule.path("minimum").asDouble(-Double.MAX_VALUE)
                        && value.asDouble() <= rule.path("maximum").asDouble(Double.MAX_VALUE));
    }

    private static boolean type(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}

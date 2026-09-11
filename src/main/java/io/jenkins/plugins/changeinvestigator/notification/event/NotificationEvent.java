package io.jenkins.plugins.changeinvestigator.notification.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Frozen, redacted JSON snapshot. No transport, credentials or Jenkins object is retained. */
public record NotificationEvent(String json) {
    public static final int MAX_BYTES = 32 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(32)
                            .maxStringLength(32768)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public NotificationEvent {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Notification event limit");
        try {
            JsonNode tree = MAPPER.readTree(json);
            EventContract.validate(tree);
            rejectUnsafe(tree, "");
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid notification event", e);
        }
    }

    public ObjectNode snapshot() {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid frozen event", e);
        }
    }

    public static NotificationEvent freeze(ObjectNode source) {
        bound(source, 0, new int[] {0});
        ObjectNode event = source.deepCopy();
        List<String> omitted = new ArrayList<>();
        sanitize(event, "", omitted);
        cap(event, "topCandidates", 5, omitted);
        cap(event, "suggestedResponders", 3, omitted);
        cap(event, "suggestedChecks", 3, omitted);
        cap(event, "relatedInvestigations", 5, omitted);
        ObjectNode redaction = event.putObject("redaction");
        redaction.put("policyVersion", 1).put("applied", true);
        ArrayNode fields = redaction.putArray("truncatedFields");
        omitted.stream().distinct().limit(30).forEach(fields::add);
        if (event.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            event.putArray("topCandidates");
            event.putArray("suggestedResponders");
            fields.add("topCandidates").add("suggestedResponders");
        }
        return new NotificationEvent(event.toString());
    }

    private static void bound(JsonNode node, int depth, int[] count) {
        if (node == null
                || node.isPojo()
                || node.isBinary()
                || depth > 24
                || ++count[0] > 4000
                || node.isTextual() && node.asText().length() > 8192)
            throw new IllegalArgumentException("Notification projection input limit");
        for (JsonNode child : node) bound(child, depth + 1, count);
    }

    private static void cap(ObjectNode event, String field, int count, List<String> omitted) {
        if (event.get(field) instanceof ArrayNode array && array.size() > count) {
            while (array.size() > count) array.remove(array.size() - 1);
            omitted.add(field);
        }
    }

    private static void sanitize(JsonNode node, String path, List<String> omitted) {
        if (node instanceof ObjectNode object) {
            var names = new ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                String field = path.isEmpty() ? name : path + "." + name;
                if (value.isTextual()) {
                    String safe = SafeContent.text(value.asText(), limit(name));
                    if (!safe.equals(value.asText())) {
                        if (field.startsWith("failureSignature.identityFields") || name.equals("digest"))
                            throw new IllegalArgumentException("Unsafe critical identity");
                        object.put(name, safe);
                        omitted.add(field.length() > 100 ? field.substring(0, 100) : field);
                    }
                } else sanitize(value, field, omitted);
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isTextual()) {
                    String safe = SafeContent.text(value.asText(), path.equals("suggestedChecks") ? 240 : 128);
                    if (!safe.equals(value.asText())) {
                        array.set(i, MAPPER.getNodeFactory().textNode(safe));
                        omitted.add(path);
                    }
                } else sanitize(value, path, omitted);
            }
        }
    }

    private static int limit(String name) {
        return switch (name) {
            case "diagnostic" -> 1200;
            case "path", "location", "reportedLocation" -> 512;
            case "limitation", "relationship", "summary", "correctiveAction", "validationBasis" -> 400;
            case "note" -> 500;
            case "nextCheck", "suggestedCheck" -> 240;
            case "label", "authorLabel", "actorLabel" -> 100;
            case "jobLabel", "stageLabel" -> 160;
            default -> 2048;
        };
    }

    private static void rejectUnsafe(JsonNode node, String path) {
        if (node.isTextual()) {
            String value = node.asText();
            if (!SafeContent.text(value, 32768).equals(value))
                throw new IllegalArgumentException("Unredacted event content");
        } else {
            for (JsonNode child : node) rejectUnsafe(child, path);
        }
    }
}

package io.jenkins.plugins.changeinvestigator.notification.identity;

import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Notification-only signature; the existing page signature and historical actions are untouched. */
public record FailureSignatureV1(
        int failureSignatureVersion,
        String extractorVersion,
        String normalizationProfile,
        String sourceObservationId,
        Quality quality,
        Category category,
        Map<String, String> identityFields,
        ContextFields contextFields,
        List<String> reasonCodes,
        String digest) {
    public enum Quality {
        SPECIFIC,
        UNRESOLVED
    }

    public enum Category {
        COMPILER,
        API_LINKAGE,
        TEST,
        EXCEPTION,
        GENERIC
    }

    public record ContextFields(Integer line, Integer column, String stageLabel, String reportedLocation) {
        public ContextFields {
            if (line != null && line < 1 || column != null && column < 1)
                throw new IllegalArgumentException("Invalid location");
            stageLabel = stageLabel == null ? null : FailureSignal.safe(stageLabel, 160);
            reportedLocation = reportedLocation == null ? null : FailureSignal.safe(reportedLocation, 512);
        }

        public static ContextFields empty() {
            return new ContextFields(null, null, null, null);
        }
    }

    public FailureSignatureV1 {
        sourceObservationId = IdentityCanonicalizer.critical(sourceObservationId, 128);
        extractorVersion = IdentityCanonicalizer.critical(extractorVersion, 64);
        if (failureSignatureVersion != 1 || !"BCI_SIGNATURE_V1".equals(normalizationProfile))
            throw new IllegalArgumentException("VERSION_INCOMPATIBLE");
        if (quality == null || category == null || contextFields == null || reasonCodes == null)
            throw new IllegalArgumentException("Missing signature metadata");
        reasonCodes = List.copyOf(reasonCodes);
        if (reasonCodes.stream().distinct().count() != reasonCodes.size())
            throw new IllegalArgumentException("Duplicate reason codes");
        if (quality == Quality.SPECIFIC) {
            identityFields = validate(category, identityFields);
            if (!reasonCodes.isEmpty() || !canonicalDigest(identityFields).equals(digest))
                throw new IllegalArgumentException("Invalid signature digest");
        } else {
            if (identityFields != null || digest != null || reasonCodes.isEmpty() || reasonCodes.size() > 8)
                throw new IllegalArgumentException("Invalid unresolved signature");
            for (String reason : reasonCodes)
                if (!REASONS.contains(reason)) throw new IllegalArgumentException("Unsupported reason");
        }
    }

    private static final java.util.Set<String> REASONS = java.util.Set.of(
            "MISSING_CONTEXT",
            "AMBIGUOUS_PATH",
            "GENERIC_DIAGNOSTIC",
            "REDACTED_CRITICAL_FIELD",
            "TRUNCATED_CRITICAL_FIELD",
            "UNSUPPORTED_PROFILE",
            "HISTORY_GAP",
            "VERSION_INCOMPATIBLE");

    public static FailureSignatureV1 create(
            Category category, Map<String, String> fields, ContextFields context, String observationId) {
        try {
            Map<String, String> valid = validate(category, fields);
            return new FailureSignatureV1(
                    1,
                    "BCI_STRUCTURED_V1",
                    "BCI_SIGNATURE_V1",
                    observationId,
                    Quality.SPECIFIC,
                    category,
                    valid,
                    context,
                    List.of(),
                    canonicalDigest(valid));
        } catch (IllegalArgumentException e) {
            String reason = REASONS.contains(e.getMessage()) ? e.getMessage() : "MISSING_CONTEXT";
            return unresolved(category, context, observationId, reason);
        }
    }

    public static FailureSignatureV1 unresolved(
            Category category, ContextFields context, String observationId, String reason) {
        return new FailureSignatureV1(
                1,
                "BCI_STRUCTURED_V1",
                "BCI_SIGNATURE_V1",
                observationId,
                Quality.UNRESOLVED,
                category,
                null,
                context,
                List.of(reason),
                null);
    }

    private static Map<String, String> validate(Category category, Map<String, String> fields) {
        if (category == Category.GENERIC) throw new IllegalArgumentException("GENERIC_DIAGNOSTIC");
        Map<String, Integer> profile =
                switch (category) {
                    case COMPILER ->
                        Map.of(
                                "profile",
                                32,
                                "scmSourceId",
                                128,
                                "repositoryPath",
                                512,
                                "diagnosticKind",
                                128,
                                "discriminant",
                                512);
                    case API_LINKAGE ->
                        Map.of("profile", 32, "exceptionType", 256, "apiIdentity", 512, "moduleIdentity", 256);
                    case TEST ->
                        Map.of(
                                "profile",
                                32,
                                "testIdentity",
                                512,
                                "assertionKind",
                                256,
                                "assertionDiagnostic",
                                1024,
                                "moduleIdentity",
                                256);
                    case EXCEPTION ->
                        Map.of(
                                "profile",
                                32,
                                "exceptionType",
                                256,
                                "applicationMethod",
                                512,
                                "diagnosticDiscriminant",
                                1024,
                                "moduleIdentity",
                                256);
                    default -> throw new IllegalArgumentException("UNSUPPORTED_PROFILE");
                };
        if (fields == null
                || !fields.keySet().equals(profile.keySet())
                || !category.name().equals(fields.get("profile")))
            throw new IllegalArgumentException("MISSING_CONTEXT");
        Map<String, String> result = new TreeMap<>();
        profile.forEach((key, max) -> result.put(key, IdentityCanonicalizer.critical(fields.get(key), max)));
        if (category == Category.COMPILER)
            result.put("repositoryPath", SourcePathResolver.relative(result.get("repositoryPath")));
        if (result.containsKey("exceptionType")
                && !result.get("exceptionType")
                        .matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+"))
            throw new IllegalArgumentException("GENERIC_DIAGNOSTIC");
        if (category == Category.EXCEPTION
                && !result.get("applicationMethod")
                        .matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*){2,}"))
            throw new IllegalArgumentException("GENERIC_DIAGNOSTIC");
        if (category == Category.API_LINKAGE) {
            String api = result.get("apiIdentity");
            String exception = result.get("exceptionType");
            if (exception.equals("java.lang.NoSuchMethodError")
                    && (!api.contains(".") || !api.contains("(") || !api.contains(")")))
                throw new IllegalArgumentException("GENERIC_DIAGNOSTIC");
            if (exception.equals("java.lang.NoClassDefFoundError") && !api.contains(".") && !api.contains("/"))
                throw new IllegalArgumentException("GENERIC_DIAGNOSTIC");
            if (!List.of("java.lang.NoSuchMethodError", "java.lang.NoClassDefFoundError")
                    .contains(exception)) throw new IllegalArgumentException("UNSUPPORTED_PROFILE");
        }
        if (category == Category.TEST && !result.get("testIdentity").contains("."))
            throw new IllegalArgumentException("GENERIC_DIAGNOSTIC");
        for (String key : List.of("diagnosticDiscriminant", "assertionDiagnostic")) {
            String diagnostic = result.get(key);
            if (diagnostic != null
                    && List.of("error", "failed", "failure", "exception", "build failed", "unknown", "null")
                            .contains(diagnostic.toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("GENERIC_DIAGNOSTIC");
        }
        if (canonicalIdentity(result).getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("TRUNCATED_CRITICAL_FIELD");
        return Collections.unmodifiableMap(result);
    }

    public String canonicalIdentity() {
        return identityFields == null ? null : canonicalIdentity(identityFields);
    }

    private static String canonicalIdentity(Map<String, String> fields) {
        List<String> order =
                switch (fields.get("profile")) {
                    case "COMPILER" ->
                        List.of("profile", "scmSourceId", "repositoryPath", "diagnosticKind", "discriminant");
                    case "API_LINKAGE" -> List.of("profile", "exceptionType", "apiIdentity", "moduleIdentity");
                    case "TEST" ->
                        List.of("profile", "testIdentity", "assertionKind", "assertionDiagnostic", "moduleIdentity");
                    case "EXCEPTION" ->
                        List.of(
                                "profile",
                                "exceptionType",
                                "applicationMethod",
                                "diagnosticDiscriminant",
                                "moduleIdentity");
                    default -> throw new IllegalArgumentException("UNSUPPORTED_PROFILE");
                };
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        for (String key : order) ordered.put(key, fields.get(key));
        return "{\"failureSignatureVersion\":1,\"normalizationProfile\":\"BCI_SIGNATURE_V1\",\"identityFields\":"
                + IdentityCanonicalizer.canonicalOrdered(ordered) + "}";
    }

    private static String canonicalDigest(Map<String, String> fields) {
        return IdentityCanonicalizer.digest(canonicalIdentity(fields));
    }

    public boolean sameIdentity(FailureSignatureV1 other) {
        return other != null
                && quality == Quality.SPECIFIC
                && other.quality == Quality.SPECIFIC
                && failureSignatureVersion == other.failureSignatureVersion
                && digest.equals(other.digest)
                && canonicalIdentity().equals(other.canonicalIdentity());
    }
}

package io.jenkins.plugins.changeinvestigator.notification.identity;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable first-observed anchor; changing first-bad proof does not alter this key. */
public record InvestigationKeyV1(
        int keyVersion,
        String jobId,
        String contextDigest,
        int failureSignatureVersion,
        String signatureDigest,
        String episodeAnchorRunId,
        String digest) {
    public InvestigationKeyV1 {
        if (!UUID.fromString(jobId).toString().equals(jobId))
            throw new IllegalArgumentException("Noncanonical job identity");
        if (!UUID.fromString(episodeAnchorRunId).toString().equals(episodeAnchorRunId))
            throw new IllegalArgumentException("Noncanonical run identity");
        if (keyVersion != 1
                || failureSignatureVersion != 1
                || !validDigest(contextDigest)
                || signatureDigest != null && !validDigest(signatureDigest))
            throw new IllegalArgumentException("Invalid investigation key");
        if (!compute(jobId, contextDigest, signatureDigest, episodeAnchorRunId).equals(digest))
            throw new IllegalArgumentException("Invalid key digest");
    }

    public static InvestigationKeyV1 create(
            String jobId, String contextDigest, FailureSignatureV1 signature, String anchorRunId) {
        return new InvestigationKeyV1(
                1,
                jobId,
                contextDigest,
                signature.failureSignatureVersion(),
                signature.digest(),
                anchorRunId,
                compute(jobId, contextDigest, signature.digest(), anchorRunId));
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String compute(String jobId, String context, String signature, String anchor) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("keyVersion", "1");
        fields.put("jobId", jobId);
        fields.put("contextDigest", context);
        fields.put("failureSignatureVersion", "1");
        fields.put("signatureDigest", signature);
        fields.put("correlationQuality", signature == null ? "UNKNOWN" : "SPECIFIC");
        fields.put("episodeAnchorRunId", anchor);
        return IdentityCanonicalizer.digest(IdentityCanonicalizer.canonical(fields));
    }
}

package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Bounded structured material facts, excluding build numbers, display text and log noise. */
public record MaterialFacts(
        String verifiedFirstBadRunId,
        List<Candidate> topCandidates,
        List<String> relevantCommits,
        String responderIdentity,
        String recoveryCandidateIdentity,
        String correctionIdentity)
        implements Serializable {
    public static final int VERSION = 1;

    public MaterialFacts {
        verifiedFirstBadRunId = identifier(verifiedFirstBadRunId);
        topCandidates = List.copyOf(Objects.requireNonNull(topCandidates));
        if (topCandidates.size() > 50) {
            throw new IllegalArgumentException("Too many candidates");
        }
        relevantCommits = canonicalList(relevantCommits, 50);
        responderIdentity = identifier(responderIdentity);
        recoveryCandidateIdentity = identifier(recoveryCandidateIdentity);
        correctionIdentity = identifier(correctionIdentity);
    }

    public static MaterialFacts empty() {
        return new MaterialFacts("", List.of(), List.of(), "", "", "");
    }

    public String canonical() {
        List<String> candidateKeys = topCandidates.stream()
                .map(Candidate::canonical)
                .distinct()
                .sorted()
                .toList();
        return field(Integer.toString(VERSION))
                + field(verifiedFirstBadRunId)
                + fields(candidateKeys)
                + fields(relevantCommits)
                + field(responderIdentity)
                + field(recoveryCandidateIdentity)
                + field(correctionIdentity);
    }

    public String fingerprint() {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean materiallyEquals(MaterialFacts other) {
        return other != null && canonical().equals(other.canonical());
    }

    public record Candidate(String repository, String path, String commit, String strength, List<String> relationships)
            implements Serializable {
        public Candidate {
            repository = identifier(repository);
            path = identifier(path);
            commit = identifier(commit);
            strength = identifier(strength);
            relationships = canonicalList(relationships, 20);
        }

        private String canonical() {
            return field(repository) + field(path) + field(commit) + field(strength) + fields(relationships);
        }
    }

    private static List<String> canonicalList(List<String> values, int limit) {
        Objects.requireNonNull(values);
        if (values.size() > limit) {
            throw new IllegalArgumentException("Material fact bound exceeded");
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            normalized.add(identifier(value));
        }
        return normalized.stream().distinct().sorted().toList();
    }

    private static String identifier(String value) {
        value = Normalizer.normalize(Objects.requireNonNullElse(value, ""), Normalizer.Form.NFC);
        if (value.length() > 512
                || (!value.isEmpty() && value.isBlank())
                || value.chars().anyMatch(Character::isISOControl)
                || !value.equals(
                        io.jenkins.plugins.changeinvestigator.notification.event.SafeContent.text(value, 512))) {
            throw new IllegalArgumentException("Invalid material identifier");
        }
        return value;
    }

    private static String fields(List<String> values) {
        StringBuilder result = new StringBuilder(field(Integer.toString(values.size())));
        values.forEach(value -> result.append(field(value)));
        return result.toString();
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }
}

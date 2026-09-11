package io.jenkins.plugins.changeinvestigator.notification.identity;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Trusted context excludes revisions, credentials, display names and URLs. */
public record ExecutionContextV1(
        int contextVersion,
        String jobId,
        List<ScmSource> sources,
        Origin origin,
        boolean trusted,
        boolean ancestryKnown,
        String digest) {
    public enum Origin {
        NORMAL,
        REPLAY
    }

    public record ScmSource(
            String slotId, String repositoryIdentity, String headKind, String headIdentity, String pullRequestTarget) {
        public ScmSource {
            slotId = IdentityCanonicalizer.critical(slotId, 128);
            repositoryIdentity = IdentityCanonicalizer.critical(repositoryIdentity, 512);
            if (repositoryIdentity.contains("://")
                    || repositoryIdentity.contains("@")
                    || repositoryIdentity.contains("?"))
                throw new IllegalArgumentException("Repository identity must be an opaque trusted ID");
            if (!List.of("BRANCH", "PULL_REQUEST", "TAG", "NO_SCM").contains(headKind))
                throw new IllegalArgumentException("Invalid head kind");
            headIdentity = IdentityCanonicalizer.critical(headIdentity, 256);
            pullRequestTarget =
                    pullRequestTarget == null ? null : IdentityCanonicalizer.critical(pullRequestTarget, 256);
            if (headKind.equals("PULL_REQUEST") && pullRequestTarget == null)
                throw new IllegalArgumentException("Missing PR target");
        }
    }

    public ExecutionContextV1 {
        if (!UUID.fromString(jobId).toString().equals(jobId))
            throw new IllegalArgumentException("Noncanonical job identity");
        if (contextVersion != 1 || origin == null || sources == null || sources.size() > 8)
            throw new IllegalArgumentException("Invalid context");
        sources = List.copyOf(sources);
        if (sources.stream().map(ScmSource::slotId).distinct().count() != sources.size())
            throw new IllegalArgumentException("Duplicate SCM slot");
        if (trusted && sources.isEmpty()) throw new IllegalArgumentException("Missing trusted context");
        if (!compute(jobId, sources, origin).equals(digest))
            throw new IllegalArgumentException("Invalid context digest");
    }

    public static ExecutionContextV1 create(
            String jobId, List<ScmSource> sources, Origin origin, boolean trusted, boolean ancestryKnown) {
        return new ExecutionContextV1(
                1, jobId, sources, origin, trusted, ancestryKnown, compute(jobId, sources, origin));
    }

    public static ExecutionContextV1 unknown(String jobId) {
        return create(jobId, List.of(), Origin.NORMAL, false, false);
    }

    private static String compute(String jobId, List<ScmSource> sources, Origin origin) {
        Map<String, String> map = new TreeMap<>();
        map.put("contextVersion", "1");
        map.put("jobId", jobId);
        map.put("origin", origin.name());
        int index = 0;
        for (ScmSource source : sources.stream()
                .sorted(java.util.Comparator.comparing(ScmSource::slotId))
                .toList()) {
            String prefix = "source" + index++ + ".";
            map.put(prefix + "slot", source.slotId());
            map.put(prefix + "repository", source.repositoryIdentity());
            map.put(prefix + "headKind", source.headKind());
            map.put(prefix + "head", source.headIdentity());
            map.put(prefix + "target", source.pullRequestTarget());
        }
        return IdentityCanonicalizer.digest(IdentityCanonicalizer.canonical(map));
    }
}

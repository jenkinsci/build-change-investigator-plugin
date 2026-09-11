package io.jenkins.plugins.changeinvestigator.notification.identity;

import java.util.List;

/** Resolves against a complete trusted source inventory, never just changed files. */
public final class SourcePathResolver {
    private SourcePathResolver() {}

    public static String relative(String path) {
        String value = IdentityCanonicalizer.critical(path, 512).replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("AMBIGUOUS_PATH");
        for (String segment : value.split("/", -1))
            if (segment.equals("..") || segment.equals(".") || segment.isEmpty())
                throw new IllegalArgumentException("AMBIGUOUS_PATH");
        return value;
    }

    public static String resolve(
            String reported, String trustedWorkspaceRoot, List<String> completeInventory, boolean complete) {
        if (!complete || completeInventory == null || completeInventory.size() > 10000)
            throw new IllegalArgumentException("AMBIGUOUS_PATH");
        String path = IdentityCanonicalizer.critical(reported, 512).replace('\\', '/');
        if (trustedWorkspaceRoot != null) {
            String root =
                    IdentityCanonicalizer.critical(trustedWorkspaceRoot, 512).replace('\\', '/');
            if (!root.endsWith("/")) root += "/";
            if (path.startsWith(root)) path = path.substring(root.length());
        }
        path = relative(path);
        String match = null;
        boolean basename = !path.contains("/");
        long bytes = 0;
        java.util.Map<String, String> rawByCanonical = new java.util.HashMap<>();
        for (String entry : completeInventory) {
            String candidate = relative(entry);
            String priorRaw = rawByCanonical.putIfAbsent(candidate, entry);
            if (priorRaw != null && !priorRaw.equals(entry)) throw new IllegalArgumentException("AMBIGUOUS_PATH");
            bytes += candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 2 * 1024 * 1024) throw new IllegalArgumentException("TRUNCATED_CRITICAL_FIELD");
            if (candidate.equals(path) || basename && candidate.endsWith("/" + path)) {
                if (match != null && !match.equals(candidate)) throw new IllegalArgumentException("AMBIGUOUS_PATH");
                match = candidate;
            }
        }
        if (match == null) throw new IllegalArgumentException("AMBIGUOUS_PATH");
        return match;
    }
}

package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Suggestions and explicit mapping checks only; no recipient lookup, routing or delivery. */
public final class ResponderResolution {
    private ResponderResolution() {}

    public enum Tier {
        JOB_TEAM,
        CODEOWNERS,
        MODULE,
        HIGHEST_RANKED_AUTHOR,
        COMMIT_AUTHOR,
        FALLBACK_CHANNEL
    }

    public enum BindingSource {
        EXPLICIT_VERIFIED,
        VERIFIED_JENKINS_PROFILE,
        SCM_EMAIL,
        UNVERIFIED_PROFILE
    }

    public record Suggestion(
            String identity, String label, Tier tier, String basis, String sourceRevision, boolean trustedSource)
            implements Serializable {
        public Suggestion {
            identity = safe(identity, 160);
            label = safe(label, 160);
            Objects.requireNonNull(tier);
            basis = safe(basis, 512);
            sourceRevision = safe(sourceRevision, 160);
        }
    }

    public record Result(List<Suggestion> suggestions, boolean ambiguous, int totalCandidates) implements Serializable {
        public Result {
            suggestions = List.copyOf(suggestions);
        }
    }

    public record Binding(
            String suggestedIdentity,
            String jenkinsUserId,
            String destinationIdentity,
            BindingSource source,
            boolean verified,
            boolean revoked)
            implements Serializable {
        public Binding {
            suggestedIdentity = safe(suggestedIdentity, 160);
            jenkinsUserId = safe(jenkinsUserId, 160);
            destinationIdentity = safe(destinationIdentity, 160);
            Objects.requireNonNull(source);
        }
    }

    public static Result resolve(List<Suggestion> input) {
        Objects.requireNonNull(input);
        if (input.size() > 50) {
            throw new IllegalArgumentException("Responder candidate bound exceeded");
        }
        List<Suggestion> eligible = input.stream()
                .filter(s -> !s.identity().isBlank() && !s.basis().isBlank())
                .filter(s -> s.trustedSource() && !s.sourceRevision().isBlank())
                .sorted(Comparator.comparing(Suggestion::tier).thenComparing(Suggestion::identity))
                .toList();
        if (eligible.isEmpty()) {
            return new Result(List.of(), false, 0);
        }
        Tier tier = eligible.get(0).tier();
        List<Suggestion> selected =
                eligible.stream().filter(s -> s.tier() == tier).distinct().toList();
        long distinctPeople =
                selected.stream().map(Suggestion::identity).distinct().count();
        List<Suggestion> unique = selected.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Suggestion::identity, s -> s, (a, b) -> a, java.util.LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        return new Result(unique.stream().limit(3).toList(), distinctPeople > 1, unique.size());
    }

    /** Must be rerun with the current binding at dispatch; author email equality is never proof. */
    public static boolean directMentionAllowed(
            Result result, Binding current, boolean mentionsEnabled, boolean newEvent) {
        if (!mentionsEnabled
                || !newEvent
                || result.ambiguous()
                || result.suggestions().size() != 1
                || current == null) {
            return false;
        }
        return current.verified()
                && !current.revoked()
                && (current.source() == BindingSource.EXPLICIT_VERIFIED
                        || current.source() == BindingSource.VERIFIED_JENKINS_PROFILE)
                && !current.jenkinsUserId().isBlank()
                && !current.destinationIdentity().isBlank()
                && current.suggestedIdentity()
                        .equals(result.suggestions().get(0).identity());
    }

    private static String safe(String value, int limit) {
        value = Objects.requireNonNullElse(value, "");
        if (value.length() > limit || !value.equals(SafeContent.text(value, limit))) {
            throw new IllegalArgumentException("Unsafe responder metadata");
        }
        return value;
    }
}

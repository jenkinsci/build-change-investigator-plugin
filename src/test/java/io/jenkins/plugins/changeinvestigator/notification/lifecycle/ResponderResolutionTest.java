package io.jenkins.plugins.changeinvestigator.notification.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResponderResolutionTest {
    private static ResponderResolution.Suggestion suggestion(
            String id, ResponderResolution.Tier tier, boolean trusted) {
        return new ResponderResolution.Suggestion(
                id, "Synthetic " + id, tier, "Configured source basis", "revision", trusted);
    }

    @Test
    void highestJustifiedTrustedTierWins() {
        var result = ResponderResolution.resolve(List.of(
                suggestion("author", ResponderResolution.Tier.HIGHEST_RANKED_AUTHOR, true),
                suggestion("module", ResponderResolution.Tier.MODULE, true),
                suggestion("codeowners", ResponderResolution.Tier.CODEOWNERS, true),
                suggestion("job", ResponderResolution.Tier.JOB_TEAM, true)));
        assertEquals("job", result.suggestions().get(0).identity());
        assertEquals("revision", result.suggestions().get(0).sourceRevision());
        assertFalse(result.ambiguous());
    }

    @Test
    void untrustedCodeownersCannotOverrideApprovedSource() {
        var result = ResponderResolution.resolve(List.of(
                suggestion("attacker", ResponderResolution.Tier.CODEOWNERS, false),
                suggestion("approved", ResponderResolution.Tier.MODULE, true)));
        assertEquals("approved", result.suggestions().get(0).identity());
    }

    @Test
    void tiesStayAmbiguousAndBounded() {
        var result = ResponderResolution.resolve(List.of(
                suggestion("a", ResponderResolution.Tier.CODEOWNERS, true),
                suggestion("b", ResponderResolution.Tier.CODEOWNERS, true),
                suggestion("c", ResponderResolution.Tier.CODEOWNERS, true),
                suggestion("d", ResponderResolution.Tier.CODEOWNERS, true)));
        assertTrue(result.ambiguous());
        assertEquals(3, result.suggestions().size());
        assertEquals(4, result.totalCandidates());
        assertFalse(ResponderResolution.directMentionAllowed(result, null, true, true));
    }

    @Test
    void forgedScmEmailAndUnverifiedProfileCannotEstablishMapping() {
        var result = ResponderResolution.resolve(
                List.of(suggestion("author", ResponderResolution.Tier.HIGHEST_RANKED_AUTHOR, true)));
        for (var source : List.of(
                ResponderResolution.BindingSource.SCM_EMAIL, ResponderResolution.BindingSource.UNVERIFIED_PROFILE)) {
            var binding = new ResponderResolution.Binding("author", "jenkins", "slack", source, true, false);
            assertFalse(ResponderResolution.directMentionAllowed(result, binding, true, true));
        }
    }

    @Test
    void explicitVerifiedMappingStillRequiresNewEventAndOptIn() {
        var result = ResponderResolution.resolve(
                List.of(suggestion("author", ResponderResolution.Tier.HIGHEST_RANKED_AUTHOR, true)));
        var binding = new ResponderResolution.Binding(
                "author", "jenkins", "slack", ResponderResolution.BindingSource.EXPLICIT_VERIFIED, true, false);
        assertFalse(ResponderResolution.directMentionAllowed(result, binding, false, true));
        assertFalse(ResponderResolution.directMentionAllowed(result, binding, true, false));
        assertTrue(ResponderResolution.directMentionAllowed(result, binding, true, true));
    }

    @Test
    void revokedOrChangedIdentityCannotUseStaleMapping() {
        var result = ResponderResolution.resolve(
                List.of(suggestion("author", ResponderResolution.Tier.HIGHEST_RANKED_AUTHOR, true)));
        var revoked = new ResponderResolution.Binding(
                "author", "jenkins", "slack", ResponderResolution.BindingSource.EXPLICIT_VERIFIED, true, true);
        var other = new ResponderResolution.Binding(
                "other", "jenkins", "slack", ResponderResolution.BindingSource.EXPLICIT_VERIFIED, true, false);
        assertFalse(ResponderResolution.directMentionAllowed(result, revoked, true, true));
        assertFalse(ResponderResolution.directMentionAllowed(result, other, true, true));
    }

    @Test
    void absentOrUnsafeSourcesDoNotInventAResponder() {
        assertTrue(ResponderResolution.resolve(List.of()).suggestions().isEmpty());
        assertTrue(
                ResponderResolution.resolve(List.of(suggestion("untrusted", ResponderResolution.Tier.JOB_TEAM, false)))
                        .suggestions()
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponderResolution.Suggestion(
                        "id",
                        "password=synthetic-secret",
                        ResponderResolution.Tier.JOB_TEAM,
                        "basis",
                        "revision",
                        true));
    }
}

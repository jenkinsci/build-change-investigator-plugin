package io.jenkins.plugins.changeinvestigator.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SafeAiFailureTest {
    @Test
    void everyCategoryHasActionablePublicGuidance() {
        for (AiAnalysisException.Kind kind : AiAnalysisException.Kind.values()) {
            assertNotNull(SafeAiFailure.describe(kind));
            assertTrue(SafeAiFailure.describe(kind).length() > 15, kind.name());
        }
        assertTrue(
                SafeAiFailure.describe(AiAnalysisException.Kind.QUOTA_EXCEEDED).contains("credits"));
        assertTrue(SafeAiFailure.describe(AiAnalysisException.Kind.RATE_LIMITED).contains("Wait"));
        assertTrue(SafeAiFailure.describe(AiAnalysisException.Kind.DEPLOYMENT_NOT_FOUND)
                .contains("Azure"));
        assertTrue(SafeAiFailure.describe(AiAnalysisException.Kind.ASSUME_ROLE_FAILED)
                .contains("trust policy"));
        assertTrue(SafeAiFailure.describe(AiAnalysisException.Kind.CONNECTION_FAILED)
                .contains("proxy"));
        assertTrue(SafeAiFailure.describe(null).contains("internal provider error"));
    }
}

package io.jenkins.plugins.changeinvestigator.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.RecordComponent;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Structural guardrail: {@link AiProviderConfig} must never grow a secret-bearing field again.
 * As a {@code record}, any field added here is automatically exposed by the generated
 * {@code toString()}/{@code equals()}/{@code hashCode()} with no way to opt out selectively -
 * see the class javadoc. The API token is deliberately routed around this type entirely
 * (passed directly to {@link OpenAiCompatibleClient} instead), so this test fails loudly if a
 * future change reintroduces it here.
 */
class AiProviderConfigTest {

    @Test
    void hasNoComponentThatLooksLikeASecret() {
        for (RecordComponent component : AiProviderConfig.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertFalse(
                    name.contains("token")
                            || name.contains("secret")
                            || name.contains("password")
                            || name.contains("apikey"),
                    "AiProviderConfig must not carry a secret-shaped field: " + component.getName());
        }
    }

    @Test
    void toStringDoesNotMentionTokenOrSecret() {
        AiProviderConfig config = new AiProviderConfig("https://example.test/v1", "gpt-4o-mini", 30, 0.2, 8000);
        String text = config.toString().toLowerCase(Locale.ROOT);
        assertFalse(text.contains("token"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("bearer"));
    }

    @Test
    void carriesExactlyTheNonSecretSettingComponents() {
        AiProviderConfig config = new AiProviderConfig("https://example.test/v1", "gpt-4o-mini", 30, 0.2, 8000);
        assertEquals("https://example.test/v1", config.baseUrl());
        assertEquals("gpt-4o-mini", config.model());
        assertEquals(30, config.timeoutSeconds());
        assertEquals(0.2, config.temperature(), 0.0001);
        assertEquals(8000, config.maxLogContextChars());
    }
}

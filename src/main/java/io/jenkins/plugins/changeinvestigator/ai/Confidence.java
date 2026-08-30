package io.jenkins.plugins.changeinvestigator.ai;

/** Confidence level the AI assessment reports for its regression hypothesis. */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH;

    /** Case-insensitive, whitespace-tolerant parse; returns {@code null} rather than throwing. */
    public static Confidence parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (Confidence c : values()) {
            if (c.name().equals(normalized)) {
                return c;
            }
        }
        return null;
    }
}

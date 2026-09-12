package io.jenkins.plugins.changeinvestigator.notification.feedback;

/** Server-derived actor. The stable identifier is local audit data only. */
public record FeedbackActor(String id, String label) {
    public FeedbackActor {
        id = FeedbackRequest.text(id, 128);
        label = FeedbackRequest.text(label, 100);
        if (id.isBlank() || label.isBlank()) throw new IllegalArgumentException("Feedback actor required");
    }
}

package io.jenkins.plugins.changeinvestigator.notification.feedback;

import java.util.UUID;

public record FeedbackResult(long revision, UUID recordId, boolean duplicate, String code) {}

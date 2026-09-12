package io.jenkins.plugins.changeinvestigator.notification.feedback;

import hudson.model.Job;
import io.jenkins.plugins.changeinvestigator.notification.email.EmailMessage;
import io.jenkins.plugins.changeinvestigator.notification.email.config.EmailConfiguration;
import io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** A request-local withholding predicate; credentials never enter feedback or audit records. */
public final class FeedbackSecrets {
    private FeedbackSecrets() {}

    /** Resolve current approved credentials inside the mutation, only when content is checked. */
    public static Predicate<String> forJob(Job<?, ?> job) {
        return new Predicate<>() {
            private List<String> secrets;

            @Override
            public boolean test(String text) {
                if (secrets == null) {
                    var current = new ArrayList<String>();
                    var email = EmailConfiguration.get();
                    for (var destination : email.resolvedDestinations(job)) {
                        var settings = email.resolveSettings(job, destination)
                                .orElseThrow(() -> new IllegalStateException("Approved mail credential unavailable"));
                        if (settings.password() != null && !settings.password().isEmpty())
                            current.add(settings.password());
                    }
                    var slack = SlackConfiguration.get();
                    for (var destination : slack.resolvedDestinations(job)) {
                        var secret = slack.resolveToken(job, destination)
                                .orElseThrow(() -> new IllegalStateException("Approved Slack credential unavailable"));
                        if (secret.getPlainText().isEmpty())
                            throw new IllegalStateException("Approved Slack credential unavailable");
                        current.add(secret.getPlainText());
                    }
                    if (current.size() > 20) throw new IllegalStateException("Feedback disclosure limit");
                    secrets = List.copyOf(current);
                }
                return secrets.stream().anyMatch(secret -> EmailMessage.containsSecret(text, secret));
            }
        };
    }
}

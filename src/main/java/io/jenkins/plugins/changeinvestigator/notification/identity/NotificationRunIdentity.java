package io.jenkins.plugins.changeinvestigator.notification.identity;

import hudson.model.InvisibleAction;
import java.util.UUID;

/** Persisted independently of mutable build number and page evidence. */
public final class NotificationRunIdentity extends InvisibleAction {
    private final String id;

    public NotificationRunIdentity() {
        id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }
}

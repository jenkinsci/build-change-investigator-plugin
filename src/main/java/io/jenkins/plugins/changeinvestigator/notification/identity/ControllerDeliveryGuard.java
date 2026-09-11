package io.jenkins.plugins.changeinvestigator.notification.identity;

import io.jenkins.plugins.changeinvestigator.notification.persistence.NotificationStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import jenkins.model.Jenkins;

/** Persisted approval is bound to this controller boot, preventing restored queues from auto-delivery. */
public final class ControllerDeliveryGuard {
    public record Approval(int schemaVersion, UUID controllerId, UUID approvedBootId) {}

    private final Path controllerRoot;
    private final UUID controllerId;
    private final UUID bootId;

    public ControllerDeliveryGuard(Path controllerRoot, UUID controllerId, UUID bootId) {
        this.controllerRoot = java.util.Objects.requireNonNull(controllerRoot);
        this.controllerId = java.util.Objects.requireNonNull(controllerId);
        this.bootId = java.util.Objects.requireNonNull(bootId);
    }

    public static boolean canDispatch(Approval approval, UUID controllerId, UUID bootId) {
        return approval != null
                && approval.schemaVersion() == 1
                && controllerId != null
                && bootId != null
                && controllerId.equals(approval.controllerId())
                && bootId.equals(approval.approvedBootId());
    }

    public boolean canDispatch() {
        try {
            var saved = store().load(controllerId);
            if (saved.isEmpty()) return false;
            var value = saved.get().aggregate();
            if (value.size() != 3
                    || !value.path("schemaVersion").isInt()
                    || !value.path("controllerId").isTextual()
                    || !value.path("approvedBootId").isTextual()) return false;
            return canDispatch(
                    new Approval(
                            value.path("schemaVersion").intValue(),
                            UUID.fromString(value.path("controllerId").textValue()),
                            UUID.fromString(value.path("approvedBootId").textValue())),
                    controllerId,
                    bootId);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
    /** Internal authenticated approval hook only; core activation must never invoke it. */
    public void approve() throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        var store = store();
        long revision = store.load(controllerId)
                .map(NotificationStore.Snapshot::revision)
                .orElse(0L);
        store.update(controllerId, revision, value -> {
            value.removeAll();
            value.put("schemaVersion", 1)
                    .put("controllerId", controllerId.toString())
                    .put("approvedBootId", bootId.toString());
            return value;
        });
    }

    private NotificationStore store() throws IOException {
        return new NotificationStore(controllerRoot.resolve("bci-notification-delivery-guard"), controllerId);
    }
}
